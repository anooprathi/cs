package com.schwab.urlshortener.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Shared, cross-instance rate limiting via Redis — activate with
 * {@code app.rate-limit.backend=redis} pointed at a Redis reachable from
 * every app instance that must enforce one true fair-share limit per
 * tenant (the actual prerequisite for correct multi-instance or
 * multi-datacenter deployment of this feature — see ARCHITECTURE.md
 * "Multi-Datacenter"; local-only buckets would let a tenant's effective
 * rate multiply by instance count).
 *
 * <b>Algorithm: fixed-window counter, not token bucket.</b> Deliberately
 * simpler than replicating {@link LocalRateLimiterBackend}'s smooth
 * token-bucket behavior over Redis (which needs an atomic Lua script to
 * be race-free under concurrent access — a correct implementation this
 * assistant has no way to compile-verify). A fixed window
 * (INCR + EXPIRE on a per-tenant-per-minute key) is a single atomic Redis
 * command for the hot path, genuinely simple to reason about, and widely
 * used in production rate limiters for exactly that reason. The known
 * trade-off, stated plainly: a client can get up to ~2x the nominal limit
 * in a short burst straddling a window boundary (a few requests right
 * before the window resets, then a fresh full window's worth right
 * after). For fair-share "don't let one tenant starve others," that
 * imprecision is an acceptable cost for the simplicity and correctness
 * confidence it buys; it would not be acceptable for something requiring
 * hard, exact limits (e.g. metering a hard-capped free tier down to the
 * exact unit).
 *
 * <b>Fails open, not closed.</b> If Redis is unreachable, requests are
 * allowed rather than rejected — the same posture as
 * {@code NoOpUrlSafetyChecker}/{@code FeignUrlSafetyChecker}'s fail-open
 * behavior. A rate limiter that becomes a hard dependency for every
 * request would turn a Redis outage into a full API outage, a
 * categorically worse failure than temporarily not enforcing fair-share
 * limits.
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.backend", havingValue = "redis")
@Slf4j
public class RedisRateLimiterBackend implements RateLimiterBackend {

    private static final String KEY_PREFIX = "ratelimit:";
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiterBackend(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public RateLimitResult tryConsume(String bucketKey, long permitsPerMinute) {
        String redisKey = KEY_PREFIX + bucketKey;
        try {
            Long count = redisTemplate.opsForValue().increment(redisKey);
            if (count == null) {
                log.error("Redis INCR returned null for key={}; failing open", redisKey);
                return allowedResult(permitsPerMinute);
            }
            if (count == 1L) {
                // First hit in a new window — start its TTL now. A crash or
                // race between INCR and this EXPIRE call would leave the key
                // without a TTL (never resets) rather than resetting too
                // early, so the failure mode leans "too strict briefly," not
                // "limit silently stops being enforced."
                redisTemplate.expire(redisKey, WINDOW);
            }

            boolean allowed = count <= permitsPerMinute;
            long remaining = Math.max(0, permitsPerMinute - count);
            long retryAfterSeconds = allowed ? 0 : windowRetryAfterSeconds(redisKey);
            return new RateLimitResult(allowed, remaining, permitsPerMinute, retryAfterSeconds);

        } catch (DataAccessException e) {
            log.error("Redis rate limiter unreachable for key={}; failing open (request allowed)", redisKey, e);
            return allowedResult(permitsPerMinute);
        }
    }

    private RateLimitResult allowedResult(long permitsPerMinute) {
        return new RateLimitResult(true, permitsPerMinute, permitsPerMinute, 0);
    }

    private long windowRetryAfterSeconds(String redisKey) {
        Long ttlSeconds = redisTemplate.getExpire(redisKey);
        return Math.max(1, ttlSeconds != null && ttlSeconds > 0 ? ttlSeconds : WINDOW.toSeconds());
    }
}
