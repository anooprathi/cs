package com.schwab.urlshortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.dto.PageResponse;
import com.schwab.urlshortener.dto.ShortenUrlRequest;
import com.schwab.urlshortener.dto.ShortenUrlResponse;
import com.schwab.urlshortener.dto.UpdateUrlRequest;
import com.schwab.urlshortener.dto.UrlStatsResponse;
import com.schwab.urlshortener.exception.InvalidUrlException;
import com.schwab.urlshortener.exception.UrlExpiredException;
import com.schwab.urlshortener.exception.UrlNotFoundException;
import com.schwab.urlshortener.service.UrlShortenerService;
import com.schwab.urlshortener.tenant.RateLimitPlan;
import com.schwab.urlshortener.tenant.TenantPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * @WebMvcTest slice: loads only the web layer (controllers + GlobalExceptionHandler)
 * with the service mocked, so these run fast and isolate the REST contract
 * from persistence concerns (already covered by the integration tests).
 *
 * Security filters are disabled here (addFilters = false) — this slice is
 * about the controller/exception-handler contract, not authentication.
 * Full auth-filter behavior (missing/invalid API key, rate limiting) is
 * covered by the security-focused integration tests.
 *
 * @AuthenticationPrincipal resolution — history worth recording, since this
 * broke FOUR separate times across three different "fixes," and only the
 * last one was actually confirmed against a real stack trace:
 *  1st/2nd attempt: @Import(SecurityConfig.class) plus mocking its
 *  dependencies — wrong theory (that a SecurityFilterChain bean's presence
 *  is what triggers argument-resolver wiring), and the import also caused
 *  an unrelated Spring Boot 4 regression chased separately.
 *  3rd attempt: registering AuthenticationPrincipalArgumentResolver via a
 *  nested @TestConfiguration implementing WebMvcConfigurer — this part was
 *  actually CORRECT (confirmed by the resulting stack trace: the resolver
 *  ran and returned null, rather than Spring failing to find a resolver at
 *  all) — but paired with `.with(authentication(auth))` from
 *  spring-security-test, which turned out to write the SecurityContext via
 *  the session-backed SecurityContextRepository, relying on a filter
 *  (SecurityContextHolderFilter) to load it into SecurityContextHolder for
 *  the request thread. With addFilters=false that filter never runs, so
 *  the context was written to the mock session (visible in the failure's
 *  "Session Attrs" dump) but never reached SecurityContextHolder, where
 *  the resolver actually looks.
 *  Actual fix: keep the resolver registration (still needed and correct),
 *  and set SecurityContextHolder directly via a local RequestPostProcessor
 *  — the exact API AuthenticationPrincipalArgumentResolver reads from, no
 *  filter or session involved. Cleared in @AfterEach to avoid the
 *  ThreadLocal leaking into other tests sharing the same fork.
 */
@WebMvcTest(controllers = {UrlShortenerController.class, RedirectController.class})
@AutoConfigureMockMvc(addFilters = false)
class UrlShortenerControllerWebMvcTest {

    private static final TenantPrincipal TENANT = new TenantPrincipal(7L, "acme", RateLimitPlan.STANDARD);

    // Locally constructed rather than @Autowired: this test only needs to
    // serialize one simple request record, and building it here removes
    // any dependency on the app's JSON bean configuration.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TestConfiguration
    static class ArgumentResolverConfig implements WebMvcConfigurer {
        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new AuthenticationPrincipalArgumentResolver());
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UrlShortenerService service;

    @AfterEach
    void clearSecurityContext() {
        // MockMvc's default execution runs synchronously on the test thread,
        // and with addFilters=false nothing else clears this — leaking it
        // would let one test's principal bleed into the next.
        SecurityContextHolder.clearContext();
    }

    private static Authentication tenantAuth() {
        return new UsernamePasswordAuthenticationToken(TENANT, null, List.of(new SimpleGrantedAuthority("ROLE_TENANT")));
    }

    /**
     * Sets SecurityContextHolder directly — bypassing spring-security-test's
     * session-backed authentication(...) postprocessor, which requires a
     * live filter chain we've deliberately disabled. See class Javadoc.
     */
    private static RequestPostProcessor authenticatedAsTenant() {
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(tenantAuth());
            SecurityContextHolder.setContext(context);
            return request;
        };
    }

    @Test
    void createShortUrl_delegatesToServiceAndReturns201() throws Exception {
        ShortenUrlRequest request = new ShortenUrlRequest("https://example.com", null, null);
        ShortenUrlResponse response = new ShortenUrlResponse(
                "abc1234", "http://localhost:8080/abc1234", "https://example.com", Instant.now(), null);
        when(service.createShortUrl(any(ShortenUrlRequest.class), eq(TENANT.tenantId()))).thenReturn(response);

        mockMvc.perform(post("/api/v1/urls")
                        .with(authenticatedAsTenant())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/urls/abc1234"))
                .andExpect(jsonPath("$.shortCode", is("abc1234")));
    }

    @Test
    void redirect_serviceThrowsNotFound_mapsTo404WithEnvelope() throws Exception {
        when(service.resolveAndRecordHit("missing")).thenThrow(new UrlNotFoundException("missing"));

        mockMvc.perform(get("/{shortCode}", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.path", is("/missing")));
    }

    @Test
    void redirect_serviceThrowsExpired_mapsTo410() throws Exception {
        when(service.resolveAndRecordHit("expired1")).thenThrow(new UrlExpiredException("expired1"));

        mockMvc.perform(get("/{shortCode}", "expired1"))
                .andExpect(status().isGone());
    }

    @Test
    void getStats_returnsServiceResult() throws Exception {
        UrlStatsResponse stats = new UrlStatsResponse("abc1234", "https://example.com", 10L,
                Instant.now(), Instant.now(), null, true);
        when(service.getStats("abc1234", TENANT.tenantId())).thenReturn(stats);

        mockMvc.perform(get("/api/v1/urls/{shortCode}/stats", "abc1234")
                        .with(authenticatedAsTenant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clickCount", is(10)));
    }

    @Test
    void createShortUrl_missingBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .with(authenticatedAsTenant())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deactivate_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/urls/{shortCode}", "abc1234")
                        .with(authenticatedAsTenant()))
                .andExpect(status().isNoContent());
    }

    @Test
    void listMyUrls_returnsServiceResult() throws Exception {
        UrlStatsResponse stats = new UrlStatsResponse("abc1234", "https://example.com", 10L,
                Instant.now(), Instant.now(), null, true);
        when(service.listMyUrls(eq(TENANT.tenantId()), any()))
                .thenReturn(new PageResponse<>(List.of(stats), 0, 50, 1, 1));

        mockMvc.perform(get("/api/v1/urls").with(authenticatedAsTenant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].shortCode", is("abc1234")))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    void updateUrl_delegatesToServiceAndReturnsUpdatedStats() throws Exception {
        UpdateUrlRequest request = new UpdateUrlRequest("https://new.example.com", null);
        UrlStatsResponse updated = new UrlStatsResponse("abc1234", "https://new.example.com", 10L,
                Instant.now(), Instant.now(), null, true);
        when(service.updateUrl(eq("abc1234"), eq(TENANT.tenantId()), any(UpdateUrlRequest.class))).thenReturn(updated);

        mockMvc.perform(patch("/api/v1/urls/{shortCode}", "abc1234")
                        .with(authenticatedAsTenant())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalUrl", is("https://new.example.com")));
    }

    @Test
    void updateUrl_serviceThrowsInvalidUrl_mapsTo400() throws Exception {
        when(service.updateUrl(eq("abc1234"), eq(TENANT.tenantId()), any(UpdateUrlRequest.class)))
                .thenThrow(new InvalidUrlException("At least one of originalUrl or expiresAt must be provided"));

        mockMvc.perform(patch("/api/v1/urls/{shortCode}", "abc1234")
                        .with(authenticatedAsTenant())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reactivate_delegatesToServiceAndReturnsStats() throws Exception {
        UrlStatsResponse stats = new UrlStatsResponse("old1234", "https://example.com", 5L,
                Instant.now(), Instant.now(), null, true);
        when(service.reactivate("old1234", TENANT.tenantId())).thenReturn(stats);

        mockMvc.perform(patch("/api/v1/urls/{shortCode}/reactivate", "old1234")
                        .with(authenticatedAsTenant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(true)));
    }
}
