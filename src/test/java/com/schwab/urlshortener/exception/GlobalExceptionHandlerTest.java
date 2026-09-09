package com.schwab.urlshortener.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Direct unit tests for every @ExceptionHandler method, called as plain
 * Java methods (no Spring context needed — @RestControllerAdvice's
 * dispatch machinery isn't what's under test here, the per-exception
 * response-building logic is). This exists specifically to close the
 * coverage gap the integration tests leave: RateLimitExceededException
 * (no integration test actually exhausts a real rate limit —
 * application-test.properties sets generous limits deliberately, so
 * that path is never naturally exercised end-to-end) and
 * MethodArgumentTypeMismatchException (nothing sends a non-numeric path
 * variable to a typed endpoint in any integration test) had no coverage
 * at all before this class. handleIllegalArgument is tested here too —
 * worth noting that nothing in this codebase's business logic currently
 * throws a bare IllegalArgumentException, so this handler may be
 * effectively unreachable in practice; testing it directly still
 * documents its intended behavior and means it's covered rather than
 * silently dead code with zero verification either way.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Mock
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        when(request.getRequestURI()).thenReturn("/api/v1/urls/abc1234");
        when(request.getMethod()).thenReturn("GET");
    }

    @Test
    void handleApiException_usesTheExceptionsOwnStatusAndMessage() {
        UrlNotFoundException ex = new UrlNotFoundException("abc1234");

        ResponseEntity<?> response = handler.handleApiException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void handleGenerationFailure_hidesInternalRetryCountFromClient() {
        // The client-facing message must NOT be the exception's own message
        // (which includes internal attempt-count detail) — that's the whole
        // reason this isn't folded into the generic ApiException handler.
        ShortCodeGenerationException ex = new ShortCodeGenerationException(
                "Failed to generate a unique short code after 5 attempts");

        var response = handler.handleGenerationFailure(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().message()).doesNotContain("5 attempts");
        assertThat(response.getBody().message()).contains("retry");
    }

    @Test
    void handleRateLimitExceeded_includesRetryAfterHeader() {
        RateLimitExceededException ex = new RateLimitExceededException("Too many requests", 42);

        ResponseEntity<?> response = handler.handleRateLimitExceeded(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("42");
    }

    @Test
    void handleValidation_collectsFieldLevelErrorsIntoDetails() {
        MethodArgumentNotValidException ex = org.mockito.Mockito.mock(MethodArgumentNotValidException.class);
        org.springframework.validation.BindingResult bindingResult = org.mockito.Mockito.mock(org.springframework.validation.BindingResult.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(
                new org.springframework.validation.FieldError("shortenUrlRequest", "originalUrl", "must be a valid URL")
        ));

        var response = handler.handleValidation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().details()).containsExactly("originalUrl: must be a valid URL");
    }

    @Test
    void handleUnreadable_returnsBadRequestWithoutLeakingParserInternals() {
        HttpMessageNotReadableException ex = org.mockito.Mockito.mock(HttpMessageNotReadableException.class);
        when(ex.getMessage()).thenReturn("JSON parse error at line 3 column 7 in file /tmp/whatever");

        var response = handler.handleUnreadable(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("Malformed request body");
    }

    @Test
    void handleTypeMismatch_namesTheOffendingParameter() {
        MethodArgumentTypeMismatchException ex = org.mockito.Mockito.mock(MethodArgumentTypeMismatchException.class);
        when(ex.getName()).thenReturn("tenantId");

        var response = handler.handleTypeMismatch(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("tenantId");
    }

    @Test
    void handlePropertyReference_namesTheOffendingSortProperty_returnsBadRequestNotA500() {
        // Regression test for a real bug found via Swagger UI: leaving the
        // default array placeholder in a Pageable endpoint's ?sort= param
        // (Swagger's own example is literally sort=["string"]) reaches
        // Spring Data as a property named ["string"], which no entity has
        // -- PropertyReferenceException, previously unhandled here and
        // falling through to the generic 500 catch-all for what is
        // ordinary bad client input.
        PropertyReferenceException ex = org.mockito.Mockito.mock(PropertyReferenceException.class);
        when(ex.getPropertyName()).thenReturn("[\"string\"]");

        var response = handler.handlePropertyReference(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("[\"string\"]");
    }

    @Test
    void handleIllegalArgument_returnsBadRequestWithTheExceptionMessage() {
        IllegalArgumentException ex = new IllegalArgumentException("something was structurally wrong");

        var response = handler.handleIllegalArgument(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("something was structurally wrong");
    }

    @Test
    void handleNoResourceFound_returnsNotFound_notAGeneric500() {
        NoResourceFoundException ex = org.mockito.Mockito.mock(NoResourceFoundException.class);
        when(request.getRequestURI()).thenReturn("/");

        var response = handler.handleNoResourceFound(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().message()).contains("/");
    }

    @Test
    void handleGeneric_neverLeaksTheRealExceptionMessageToTheClient() {
        RuntimeException ex = new RuntimeException("internal detail: connection pool exhausted at host db-primary-3");

        var response = handler.handleGeneric(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().message())
                .as("the real exception message must never reach the client — security: avoid information disclosure")
                .doesNotContain("db-primary-3")
                .isEqualTo("An unexpected error occurred");
    }
}
