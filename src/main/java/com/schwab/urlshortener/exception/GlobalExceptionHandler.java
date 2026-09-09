package com.schwab.urlshortener.exception;

import com.schwab.urlshortener.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Centralized REST exception handling.
 *
 * Design notes:
 *  - Every handler returns the shared {@link ErrorResponse} envelope so
 *    clients get a consistent shape regardless of failure type.
 *  - handleApiException is a SINGLE handler for the whole ApiException
 *    family (UrlNotFoundException, DuplicateAliasException,
 *    InvoiceNotFoundException, etc.) — adding a new "status X with this
 *    message" business exception needs a new subclass, not a new handler
 *    method here. ShortCodeGenerationException and
 *    RateLimitExceededException are the two deliberate exceptions to
 *    that: each carries behavior beyond "status + message" (a translated
 *    client message, and a Retry-After header, respectively), so each
 *    keeps its own handler.
 *  - The catch-all handler never leaks internal exception messages/stack
 *    traces to the client (security: avoid information disclosure); it
 *    logs the full exception server-side instead.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest req) {
        log.warn("{} on {}: {}", ex.getStatus(), req.getRequestURI(), ex.getMessage());
        return build(ex.getStatus(), ex.getMessage(), req);
    }

    /**
     * Deliberately not folded into ApiException: the message shown to the
     * client ("Please retry") is intentionally different from the
     * exception's own message (which includes the internal retry-attempt
     * count) — we don't want to expose that operational detail, only log
     * it server-side.
     */
    @ExceptionHandler(ShortCodeGenerationException.class)
    public ResponseEntity<ErrorResponse> handleGenerationFailure(ShortCodeGenerationException ex, HttpServletRequest req) {
        log.error("Short code generation failed: {}", ex.getMessage());
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to generate a short URL at this time. Please retry.", req);
    }

    /** Deliberately not folded into ApiException: needs the extra Retry-After response header. */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceeded(RateLimitExceededException ex, HttpServletRequest req) {
        log.warn("Rate limit exceeded on {}: {}", req.getRequestURI(), ex.getMessage());
        ErrorResponse body = new ErrorResponse(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                ex.getMessage(),
                req.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(body);
    }

    /** Bean Validation (@Valid) failures on request bodies -> 400 with field-level details. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        log.warn("Validation failed: {}", details);
        ErrorResponse body = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Request validation failed",
                req.getRequestURI(),
                details
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** Malformed JSON body -> 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        log.warn("Malformed request body: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "Malformed request body", req);
    }

    /** Wrong path-variable/query-param type (e.g. non-numeric id) -> 400. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        String message = "Invalid value for parameter '" + ex.getName() + "'";
        log.warn(message);
        return build(HttpStatus.BAD_REQUEST, message, req);
    }

    /**
     * An invalid ?sort=... value on a Pageable-backed endpoint (e.g.
     * Swagger UI's unedited array-type placeholder, sort=["string"],
     * decoded and handed to Spring Data as a property named literally
     * ["string"], which no entity has) -> 400. Without this handler it
     * falls through to the generic Exception.class catch-all below as an
     * opaque 500 -- this is ordinary bad client input, not a server fault.
     */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ErrorResponse> handlePropertyReference(PropertyReferenceException ex, HttpServletRequest req) {
        String message = "Invalid sort property: " + ex.getPropertyName();
        log.warn(message);
        return build(HttpStatus.BAD_REQUEST, message, req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest req) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
    }

    /**
     * A request that matches no route at all (e.g. GET / on this API, which
     * defines no root mapping). Spring Framework 6.1+ throws this for
     * genuinely unmatched paths — without an explicit handler here it would
     * fall through to the generic Exception.class catch-all below and
     * incorrectly surface as a 500, since NoResourceFoundException IS-A
     * Exception and nothing more specific was registered for it. A missing
     * route is a normal 404, not an unexpected server error.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest req) {
        log.warn("No route matched: {} {}", req.getMethod(), req.getRequestURI());
        return build(HttpStatus.NOT_FOUND, "No route matched: " + req.getRequestURI(), req);
    }

    /** Fallback: anything unhandled becomes a generic 500 — never expose internals to the caller. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", req);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest req) {
        ErrorResponse body = new ErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                message,
                req.getRequestURI()
        );
        return ResponseEntity.status(status).body(body);
    }
}
