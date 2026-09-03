package com.schwab.urlshortener.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * In-process token buckets (Bucket4j) cached per bucket key (Caffeine).
 * Correct and simple for a single instance; each instance enforces its
 * own independent bucket, so a tenant's effective limit multiplies by
 * however many instances are running it — the reason
 * {@link RedisRateLimiterBackend} exists for multi-instance/multi-DC
 * deployments. Active by default (matchIfMissing = true), matching this
 * prototype's single-node default deployment shape.
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.backend", havingValue = "local", matchIfMissing = true)
@Slf4j
public class LocalRateLimiterBackend implements RateLimiterBackend {

    private final Cache<String, Bucket> buckets;

    public LocalRateLimiterBackend() {
        // Bounded cache: idle tenants' buckets are evicted rather than
        // growing memory unboundedly as the tenant base grows.
        this.buckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofHours(1))
                .build();
    }

    @Override
    public RateLimitResult tryConsume(String bucketKey, long permitsPerMinute) {
        // Cache key includes the limit itself, not just the bucket identity:
        // Bucket4j's Bucket bakes its Bandwidth (the configured limit) in at
        // construction time and doesn't expose a way to change it later.
        // Caffeine's Cache.get(key, mappingFunction) only invokes the
        // mapping function on a miss — with a bare bucketKey, a tenant
        // upgraded from STANDARD to PREMIUM mid-session would keep hitting
        // the SAME cached Bucket built with the old, lower limit forever,
        // since the key never changed even though the desired limit did.
        // Qualifying the key with permitsPerMinute makes a changed plan
        // look like a genuinely new cache entry, so it gets a fresh bucket
        // sized correctly — the old entry simply ages out via
        // expireAfterAccess instead of leaking indefinitely.
        String cacheKey = bucketKey + ":" + permitsPerMinute;
        Bucket bucket = buckets.get(cacheKey, key -> newBucket(permitsPerMinute));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        long retryAfterSeconds = probe.isConsumed() ? 0 : Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000);
        if (!probe.isConsumed()) {
            log.warn("Rate limit exceeded for bucket={}, retryAfterSeconds={}", cacheKey, retryAfterSeconds);
        }
        return new RateLimitResult(probe.isConsumed(), probe.getRemainingTokens(), permitsPerMinute, retryAfterSeconds);
    }

    private Bucket newBucket(long permitsPerMinute) {
        Bandwidth limit = Bandwidth.classic(permitsPerMinute, Refill.greedy(permitsPerMinute, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }
}
