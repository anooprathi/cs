package com.schwab.urlshortener.tenant;

/**
 * Subscription/rate-limit tier for a tenant. Kept intentionally small
 * (two tiers) for this prototype; a real system would likely load plan
 * definitions from config/DB rather than an enum. See
 * {@code com.schwab.urlshortener.ratelimit.RateLimitProperties} for the
 * actual permits-per-minute values, which are externalized to
 * application*.properties rather than hardcoded here.
 */
public enum RateLimitPlan {
    STANDARD,
    PREMIUM
}
