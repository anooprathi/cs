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
}
