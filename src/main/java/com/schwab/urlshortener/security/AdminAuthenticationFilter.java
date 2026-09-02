package com.schwab.urlshortener.security;

import com.schwab.urlshortener.dto.ErrorResponse;
import com.schwab.urlshortener.dto.ErrorResponseWriter;
import com.schwab.urlshortener.tenant.ApiKeyGenerator;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Stateless admin authentication: reads {@code X-Admin-Key}, and — if it
 * matches the configured hash — populates the SecurityContext with
 * {@link AdminPrincipal} and {@code ROLE_ADMIN}.
 *
 * Fails CLOSED, not open: if no admin key hash is configured
 * (application*.properties leaves {@code app.admin.api-key-hash} blank,
 * as the common application.properties deliberately does), presenting
 * ANY key is rejected outright rather than silently granting access —
 * the opposite failure mode would mean admin endpoints become
 * unauthenticated the moment someone forgets to set the property.
 *
 * Hash comparison uses MessageDigest.isEqual (constant-time) rather than
 * String.equals, to avoid a timing side-channel on the comparison itself
 * — a small, cheap hardening step worth taking for the one credential in
 * this system with unrestricted cross-tenant access.
 */
public class AdminAuthenticationFilter extends OncePerRequestFilter {

    public static final String ADMIN_KEY_HEADER = "X-Admin-Key";

    private final String configuredKeyHash;

    public AdminAuthenticationFilter(String configuredKeyHash) {
        this.configuredKeyHash = configuredKeyHash;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String presentedKey = request.getHeader(ADMIN_KEY_HEADER);

        if (presentedKey != null && !presentedKey.isBlank()) {
            if (configuredKeyHash == null || configuredKeyHash.isBlank()) {
                writeUnauthorized(response, request, "Admin access is not configured on this deployment");
                return;
            }

            String presentedHash = ApiKeyGenerator.hash(presentedKey);
            boolean matches = MessageDigest.isEqual(
                    presentedHash.getBytes(StandardCharsets.UTF_8),
                    configuredKeyHash.getBytes(StandardCharsets.UTF_8));

            if (matches) {
                Authentication auth = new UsernamePasswordAuthenticationToken(
                        AdminPrincipal.INSTANCE, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else {
                writeUnauthorized(response, request, "Invalid admin key");
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
