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
 * docs are explicitly permitted without a key; everything else under
 * /api/v1/** requires either a tenant identity or, for /api/v1/admin/**,
 * the separate admin credential.
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
                        // Ops/infra endpoints.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/h2-console/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // Admin surface — a distinct role, checked before the broader tenant rule below
                        // since Spring Security's DSL matches rules in declaration order.
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // Everything else under the management API requires a tenant identity.
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll()
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
