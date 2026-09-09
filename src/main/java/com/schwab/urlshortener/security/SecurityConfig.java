package com.schwab.urlshortener.security;

import com.schwab.urlshortener.dto.ErrorResponse;
import com.schwab.urlshortener.dto.ErrorResponseWriter;
import com.schwab.urlshortener.ratelimit.RateLimitFilter;
import com.schwab.urlshortener.ratelimit.TenantRateLimiterService;
import com.schwab.urlshortener.tenant.TenantService;
import com.schwab.urlshortener.util.ShortCodeFormat;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Stateless, API-key-based security for the management API. The public
 * redirect endpoint, tenant self-registration, health checks, and API
 * docs (dev only — see application-prod.properties) are explicitly
 * permitted without a key; everything else under /api/v1/** requires
 * either a tenant identity or, for /api/v1/admin/**, the separate admin
 * credential. The catchall is denyAll(), not permitAll() — a route this
 * app doesn't explicitly account for should be refused by default, not
 * silently open. (This was a real gap a production review found:
 * /actuator/metrics and /actuator/prometheus were reachable
 * unauthenticated purely because nothing claimed them and the old
 * catchall defaulted to open.)
 *
 * Note on protecting metrics/prometheus with ROLE_ADMIN specifically:
 * this is a pragmatic fit for this app's existing identity model (there
 * is no separate "monitoring/scrape" role), not a complete answer — a
 * real deployment would more likely put these on a private
 * network/port a scraper reaches without going through this filter
 * chain at all, or use a dedicated scrape credential rather than the
 * same one used for tenant administration. Documented as a roadmap item
 * (README.md "Production Readiness Roadmap"), not silently left as if
 * ROLE_ADMIN were the final answer.
 *
 * Ordering matters:
 *  1. {@link AdminAuthenticationFilter} and {@link ApiKeyAuthenticationFilter}
 *     both resolve identity from different headers (X-Admin-Key /
 *     X-API-Key) — order between the two doesn't matter functionally,
 *     since a request only presents one or the other in practice.
 *  2. {@link RateLimitFilter} runs after both, and only acts on a resolved
 *     TenantPrincipal — an admin request has no TenantPrincipal, so it
 *     passes through untouched. Fair-share limits are a tenant concept;
 *     admin access isn't rate-limited by tenant plan.
 */
@Configuration
public class SecurityConfig {

    private final TenantService tenantService;
    private final TenantRateLimiterService rateLimiterService;
    private final AdminSecurityProperties adminSecurityProperties;
    private final MeterRegistry meterRegistry;

    public SecurityConfig(TenantService tenantService, TenantRateLimiterService rateLimiterService,
                           AdminSecurityProperties adminSecurityProperties, MeterRegistry meterRegistry) {
        this.tenantService = tenantService;
        this.rateLimiterService = rateLimiterService;
        this.adminSecurityProperties = adminSecurityProperties;
        this.meterRegistry = meterRegistry;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless JSON API: no CSRF token concept applies (no cookie-based session).
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // H2 console renders itself in a frame; harmless to allow same-origin framing
                // in this prototype (H2 console itself is disabled entirely in prod — see
                // application-prod.properties).
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(authorize -> authorize
                        // Public redirect surface — anyone with a short link can use it.
                        .requestMatchers(HttpMethod.GET, "/{shortCode:" + ShortCodeFormat.CHARSET_AND_LENGTH + "}").permitAll()
                        // Tenant self-registration must be reachable with no key yet.
                        .requestMatchers(HttpMethod.POST, "/api/v1/tenants").permitAll()
                        // Spring's internal error-view forwarding must stay reachable even under a
                        // denyAll() catchall, or an unrelated failure elsewhere can get masked by a
                        // confusing secondary 403 from Security intercepting the forward itself —
                        // a well-known gotcha when moving a catchall from permitAll to denyAll.
                        .requestMatchers("/error").permitAll()
                        // Ops/infra endpoints — health/info only. Metrics and Prometheus are NOT
                        // permitAll: they're internal operational detail (redirect volume,
                        // rate-limit rejections by plan, JVM internals), gated behind the admin
                        // identity rather than left open to anyone (see class Javadoc above).
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/metrics", "/actuator/metrics/**", "/actuator/prometheus").hasRole("ADMIN")
                        .requestMatchers("/h2-console/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // Admin surface — a distinct role, checked before the broader tenant rule below
                        // since Spring Security's DSL matches rules in declaration order.
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // Everything else under the management API requires a tenant identity.
                        .requestMatchers("/api/v1/**").authenticated()
                        // Deliberately denyAll, not permitAll — see class Javadoc. Note for
                        // whoever tests this: a caller presenting NO credential at all gets
                        // 401 here, not 403 — Spring Security routes a denied anonymous
                        // principal to the AuthenticationEntryPoint (401), reserving the
                        // AccessDeniedHandler (403) for a real-but-insufficient credential
                        // (see AdminIntegrationTest's wrong-key cases for that 403 case, and
                        // UrlShortenerIntegrationTest#rootPath_matchesNoRoute_isRejectedCleanly_notAnUnhandled500
                        // for the full reasoning this comment summarizes).
                        .anyRequest().denyAll()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(this::handleUnauthenticated)
                        .accessDeniedHandler(this::handleAccessDenied)
                )
                .addFilterBefore(new AdminAuthenticationFilter(adminSecurityProperties.apiKeyHash()), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new ApiKeyAuthenticationFilter(tenantService), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new RateLimitFilter(rateLimiterService, meterRegistry), ApiKeyAuthenticationFilter.class);

        return http.build();
    }

    /** Consistent JSON error envelope for 401s, matching GlobalExceptionHandler's shape. */
    private void handleUnauthenticated(jakarta.servlet.http.HttpServletRequest request,
                                        jakarta.servlet.http.HttpServletResponse response,
                                        org.springframework.security.core.AuthenticationException ex) throws IOException {
        writeError(response, request.getRequestURI(), HttpStatus.UNAUTHORIZED,
                "Authentication required. Provide a valid X-API-Key (or X-Admin-Key) header.");
    }

    /** A tenant key was valid but the route requires ROLE_ADMIN (or vice versa). */
    private void handleAccessDenied(jakarta.servlet.http.HttpServletRequest request,
                                     jakarta.servlet.http.HttpServletResponse response,
                                     org.springframework.security.access.AccessDeniedException ex) throws IOException {
        writeError(response, request.getRequestURI(), HttpStatus.FORBIDDEN, "Access denied.");
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse response, String path, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = new ErrorResponse(status.value(), status.getReasonPhrase(), message, path);
        ErrorResponseWriter.write(response.getWriter(), body);
    }
}
