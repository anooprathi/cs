package com.schwab.urlshortener.security;

import com.schwab.urlshortener.dto.ErrorResponse;
import com.schwab.urlshortener.dto.ErrorResponseWriter;
import com.schwab.urlshortener.tenant.TenantPrincipal;
import com.schwab.urlshortener.tenant.TenantService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Stateless API-key authentication: reads {@code X-API-Key}, resolves it
 * to a tenant, and — if valid — populates the SecurityContext with a
 * {@link TenantPrincipal} for the rest of the request. No session, no
 * cookie; every request re-authenticates independently (see
 * SecurityConfig's SessionCreationPolicy.STATELESS).
 *
 * Requests with no key, or a key that doesn't resolve, are simply left
 * unauthenticated here — SecurityConfig's authorizeHttpRequests rules
 * (and its AuthenticationEntryPoint) are what turn "unauthenticated" into
 * an actual 401 for the endpoints that require it, keeping this filter
 * focused on one job: resolving the caller's identity.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final TenantService tenantService;

    public ApiKeyAuthenticationFilter(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String apiKey = request.getHeader(API_KEY_HEADER);

        if (apiKey != null && !apiKey.isBlank()) {
            TenantPrincipal principal = tenantService.authenticate(apiKey);
            if (principal != null) {
                Authentication auth = new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_TENANT")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else {
                // A key was presented but didn't resolve — fail fast and explicitly
                // rather than silently falling through to "unauthenticated" (which
                // would produce a less specific 401 with no explanation).
                writeUnauthorized(response, request, "Invalid API key");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, HttpServletRequest request, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                message,
                request.getRequestURI()
        );
        ErrorResponseWriter.write(response.getWriter(), body);
    }
}
