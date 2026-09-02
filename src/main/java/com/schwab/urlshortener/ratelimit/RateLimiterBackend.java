package com.schwab.urlshortener.ratelimit;

/**
 * The actual bucket/counter mechanics behind rate limiting, kept
 * separate from {@link TenantRateLimiterService} (which owns plan lookup
 * and bucket-key construction — the *policy*) so the *mechanism* is
 * swappable per deployment shape:
 *  - {@link LocalRateLimiterBackend} — in-process, correct for a single
 *    instance.
 *  - {@link RedisRateLimiterBackend} — shared state across instances/DCs,
 *    the actual prerequisite for one true fair-share limit per tenant
 *    once there's more than one app instance enforcing it (see
 *    ARCHITECTURE.md "Multi-Datacenter").
 * Selected via app.rate-limit.backend (local|redis); exactly one
 * implementation is active at a time via mutually-exclusive
 * {@code @ConditionalOnProperty} conditions on each — the same pattern
 * already used for UrlSafetyChecker's two implementations.
 */
public interface RateLimiterBackend {
    RateLimitResult tryConsume(String bucketKey, long permitsPerMinute);
}
