package com.schwab.urlshortener.ratelimit;

import com.schwab.urlshortener.tenant.RateLimitPlan;
import org.springframework.stereotype.Component;

/**
 * "Noisy neighbor" protection: gives every tenant its own independent
 * rate-limit bucket, so one tenant hammering the API (a traffic spike, a
 * bug in their integration, or a viral link) can only ever exhaust its
 * OWN bucket — it has no way to consume another tenant's capacity, and
 * therefore no way to degrade another tenant's fair share of the
 * service.
 *
 * Owns the *policy* — which plan gets which limit, and how bucket keys
 * are constructed — and delegates the actual bucket/counter *mechanism*
 * to an injected {@link RateLimiterBackend} (in-process for a single
 * instance, or Redis for multi-instance/multi-DC — see that interface's
 * Javadoc and ARCHITECTURE.md "Multi-Datacenter").
 *
 * Two bucket families per tenant:
 *  - "api"      — governs the management API (create/stats/deactivate).
 *  - "redirect" — governs the public redirect hot path, keyed by the
 *                 *owning* tenant of the short link being redirected
 *                 (the caller of GET /{shortCode} is anonymous, but the
 *                 capacity being protected belongs to whoever created
 *                 the link).
 */
@Component
public class TenantRateLimiterService {

    private final RateLimitProperties properties;
    private final RateLimiterBackend backend;

    public TenantRateLimiterService(RateLimitProperties properties, RateLimiterBackend backend) {
        this.properties = properties;
        this.backend = backend;
    }

    public RateLimitResult tryConsumeApiPermit(Long tenantId, RateLimitPlan plan) {
        return backend.tryConsume("api:" + tenantId, planLimits(plan).apiPermitsPerMinute());
    }

    public RateLimitResult tryConsumeRedirectPermit(Long tenantId, RateLimitPlan plan) {
        return backend.tryConsume("redirect:" + tenantId, planLimits(plan).redirectPermitsPerMinute());
    }

    private RateLimitProperties.PlanLimits planLimits(RateLimitPlan plan) {
        RateLimitProperties.PlanLimits limits = properties.plans().get(plan);
        if (limits == null) {
            throw new IllegalStateException(
                    "No rate-limit configuration bound for plan " + plan + " — check app.rate-limit.plans.* properties");
        }
        return limits;
    }
}
