package com.schwab.urlshortener.ratelimit;

import com.schwab.urlshortener.dto.ErrorResponse;
import com.schwab.urlshortener.dto.ErrorResponseWriter;
import com.schwab.urlshortener.tenant.RateLimitPlan;
import com.schwab.urlshortener.tenant.TenantPrincipal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/**
 * Enforces the per-tenant "api" bucket on every request that reached us
 * already authenticated (i.e. after {@code ApiKeyAuthenticationFilter}).
 * Runs as a distinct filter, not inline in the auth filter, so
 * authentication and rate limiting stay separately testable and
 * separately reason-about-able concerns.
 *
 * Unauthenticated requests (redirect, tenant registration, actuator,
 * swagger) pass through untouched here — the redirect path has its own,
 * separate fair-share check inside the service layer, scoped to the
 * short link's owning tenant rather than the (anonymous) caller.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final TenantRateLimiterService rateLimiterService;

    /**
     * One Counter per plan, pre-registered in the constructor rather than
     * built fresh on every rejected request. Micrometer's registry would
     * have deduplicated repeated Counter.builder(...).register() calls for
     * the same name+tags anyway (it's a lookup, not a fresh allocation
     * every time), but pre-registering against the small, fixed
     * RateLimitPlan set is simpler to reason about than trusting that
     * dedup behavior implicitly, and matches the "cache the meter as a
     * field" pattern already used in UrlShortenerServiceImpl — one
     * consistent approach for metrics across this codebase, not two.
     */
    private final Map<RateLimitPlan, Counter> rejectionCountersByPlan;

    public RateLimitFilter(TenantRateLimiterService rateLimiterService, MeterRegistry meterRegistry) {
        this.rateLimiterService = rateLimiterService;
        this.rejectionCountersByPlan = new EnumMap<>(RateLimitPlan.class);
        for (RateLimitPlan plan : RateLimitPlan.values()) {
            // Tagged by plan, deliberately not by tenant id: a per-tenant tag
            // on a Counter is an unbounded-cardinality metric label — fine
            // for a handful of demo tenants, a real liability once there are
            // thousands (every distinct tenant becomes its own permanent
            // time series in the metrics backend). Plan is a small, fixed set.
            rejectionCountersByPlan.put(plan, Counter.builder("rate_limit.rejections")
                    .description("Requests rejected by the per-tenant API rate limiter")
                    .tag("plan", plan.name())
                    .register(meterRegistry));
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof TenantPrincipal tenant)) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitResult result =
                rateLimiterService.tryConsumeApiPermit(tenant.tenantId(), tenant.plan());

        response.setHeader("X-RateLimit-Limit", String.valueOf(result.limitPerMinute()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, result.remainingPermits())));

        if (!result.allowed()) {
            rejectionCountersByPlan.get(tenant.plan()).increment();

            response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ErrorResponse body = new ErrorResponse(
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                    "Rate limit exceeded for tenant '" + tenant.name() + "'. Retry after " + result.retryAfterSeconds() + "s.",
                    request.getRequestURI()
            );
            ErrorResponseWriter.write(response.getWriter(), body);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
