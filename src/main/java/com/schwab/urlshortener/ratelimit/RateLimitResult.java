package com.schwab.urlshortener.ratelimit;

/**
 * Outcome of a rate-limit check, including the info needed for response
 * headers (X-RateLimit-Limit / X-RateLimit-Remaining / Retry-After).
 *
 * Extracted to its own file (was previously nested inside
 * TenantRateLimiterService) once a second RateLimiterBackend
 * implementation (Redis, for multi-instance/multi-DC deployments — see
 * ARCHITECTURE.md) needed to produce the same shape independently of the
 * original local-only implementation.
 */
public record RateLimitResult(boolean allowed, long remainingPermits, long limitPerMinute, long retryAfterSeconds) {
}
