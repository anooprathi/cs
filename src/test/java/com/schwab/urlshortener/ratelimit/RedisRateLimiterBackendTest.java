package com.schwab.urlshortener.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisRateLimiterBackendTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisRateLimiterBackend backend;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        backend = new RedisRateLimiterBackend(redisTemplate);
    }

    @Test
    void firstRequestInWindow_isAllowed_andStartsTtl() {
        when(valueOperations.increment("ratelimit:api:1")).thenReturn(1L);

        RateLimitResult result = backend.tryConsume("api:1", 5);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingPermits()).isEqualTo(4);
        verify(redisTemplate).expire("ratelimit:api:1", java.time.Duration.ofMinutes(1));
    }

    @Test
    void subsequentRequestWithinLimit_isAllowed_doesNotResetTtl() {
        when(valueOperations.increment("ratelimit:api:1")).thenReturn(3L);
        // A healthy, already-ticking TTL — the self-healing check (see
        // nonFirstHit_missingTtl_selfHealsByReSettingExpiry) must NOT fire
        // when there's nothing to heal.
        when(redisTemplate.getExpire("ratelimit:api:1")).thenReturn(42L);

        RateLimitResult result = backend.tryConsume("api:1", 5);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingPermits()).isEqualTo(2);
        verify(redisTemplate, never()).expire(anyString(), any(java.time.Duration.class));
    }

    @Test
    void requestOverLimit_isDenied_withRetryAfterFromTtl() {
        when(valueOperations.increment("ratelimit:api:1")).thenReturn(6L);
        when(redisTemplate.getExpire("ratelimit:api:1")).thenReturn(42L);

        RateLimitResult result = backend.tryConsume("api:1", 5);

        assertThat(result.allowed()).isFalse();
        assertThat(result.remainingPermits()).isZero();
        assertThat(result.retryAfterSeconds()).isEqualTo(42L);
    }

    @Test
    void requestOverLimit_ttlMissing_fallsBackToWindowLength() {
        when(valueOperations.increment("ratelimit:api:1")).thenReturn(6L);
        when(redisTemplate.getExpire("ratelimit:api:1")).thenReturn(null);

        RateLimitResult result = backend.tryConsume("api:1", 5);

        assertThat(result.retryAfterSeconds()).isEqualTo(60L);
    }

    @Test
    void redisUnreachable_failsOpen_requestAllowed() {
        when(valueOperations.increment(anyString())).thenThrow(new QueryTimeoutException("connection refused"));

        RateLimitResult result = backend.tryConsume("api:1", 5);

        assertThat(result.allowed())
                .as("a rate limiter that itself becomes unavailable must not take the whole API down with it")
                .isTrue();
    }

    @Test
    void redisReturnsNull_failsOpen_requestAllowed() {
        when(valueOperations.increment(anyString())).thenReturn(null);

        RateLimitResult result = backend.tryConsume("api:1", 5);

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void nonFirstHit_missingTtl_selfHealsByReSettingExpiry() {
        // Regression test: if a prior request's count==1 INCR succeeded but
        // its EXPIRE call was lost (crash, timeout, dropped connection),
        // this key would carry no TTL — and since count can never equal 1
        // again for this window, the OLD code would never attempt to set
        // one, leaving the key (and whoever it rate-limits) permanently
        // stuck. getExpire() returning -1 signals exactly that scenario.
        when(valueOperations.increment("ratelimit:api:1")).thenReturn(3L);
        when(redisTemplate.getExpire("ratelimit:api:1")).thenReturn(-1L);

        backend.tryConsume("api:1", 5);

        verify(redisTemplate).expire("ratelimit:api:1", java.time.Duration.ofMinutes(1));
    }

    @Test
    void nonFirstHit_ttlAlreadyPresent_doesNotResetIt() {
        // The healthy, common case: don't touch the TTL on every request —
        // only repair it when it's actually missing. Otherwise this would
        // silently turn into "the window always extends on every hit,"
        // never actually resetting.
        when(valueOperations.increment("ratelimit:api:1")).thenReturn(3L);
        when(redisTemplate.getExpire("ratelimit:api:1")).thenReturn(42L);

        backend.tryConsume("api:1", 5);

        verify(redisTemplate, never()).expire(anyString(), any(java.time.Duration.class));
    }
}
