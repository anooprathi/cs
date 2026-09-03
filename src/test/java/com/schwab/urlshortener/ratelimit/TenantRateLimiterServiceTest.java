package com.schwab.urlshortener.ratelimit;

import com.schwab.urlshortener.tenant.RateLimitPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the "noisy neighbor" guarantee directly: exhausting one
 * tenant's bucket must have zero effect on another tenant's remaining
 * capacity, and PREMIUM/STANDARD tenants get independently-sized buckets
 * as configured.
 */
class TenantRateLimiterServiceTest {

    private static final long STANDARD_API_PERMITS = 3;
    private static final long STANDARD_REDIRECT_PERMITS = 5;
    private static final long PREMIUM_API_PERMITS = 10;
    private static final long PREMIUM_REDIRECT_PERMITS = 20;

    private TenantRateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        RateLimitProperties properties = new RateLimitProperties(java.util.Map.of(
                RateLimitPlan.STANDARD, new RateLimitProperties.PlanLimits(STANDARD_API_PERMITS, STANDARD_REDIRECT_PERMITS),
                RateLimitPlan.PREMIUM, new RateLimitProperties.PlanLimits(PREMIUM_API_PERMITS, PREMIUM_REDIRECT_PERMITS)
        ));
        rateLimiterService = new TenantRateLimiterService(properties, new LocalRateLimiterBackend());
    }

    @Test
    void withinLimit_permitsAreConsumedAndAllowed() {
        for (int i = 0; i < STANDARD_API_PERMITS; i++) {
            RateLimitResult result =
                    rateLimiterService.tryConsumeApiPermit(1L, RateLimitPlan.STANDARD);
            assertThat(result.allowed()).isTrue();
        }
    }

    @Test
    void exceedingLimit_isDenied_withRetryAfter() {
        for (int i = 0; i < STANDARD_API_PERMITS; i++) {
            rateLimiterService.tryConsumeApiPermit(1L, RateLimitPlan.STANDARD);
        }

        RateLimitResult result =
                rateLimiterService.tryConsumeApiPermit(1L, RateLimitPlan.STANDARD);

        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void noisyNeighbor_oneTenantExhaustingItsBucket_doesNotAffectAnotherTenant() {
        Long noisyTenant = 1L;
        Long quietTenant = 2L;

        // The noisy tenant hammers its own bucket well past its limit.
        for (int i = 0; i < STANDARD_API_PERMITS + 10; i++) {
            rateLimiterService.tryConsumeApiPermit(noisyTenant, RateLimitPlan.STANDARD);
        }

        // The quiet tenant should still get its full, independent allowance.
        for (int i = 0; i < STANDARD_API_PERMITS; i++) {
            RateLimitResult result =
                    rateLimiterService.tryConsumeApiPermit(quietTenant, RateLimitPlan.STANDARD);
            assertThat(result.allowed())
                    .as("quiet tenant's permit #%d should be unaffected by the noisy tenant's usage", i + 1)
                    .isTrue();
        }
    }

    @Test
    void apiAndRedirectBucketsAreIndependentPerTenant() {
        Long tenantId = 5L;

        // Exhaust the API bucket only.
        for (int i = 0; i < STANDARD_API_PERMITS; i++) {
            rateLimiterService.tryConsumeApiPermit(tenantId, RateLimitPlan.STANDARD);
        }
        assertThat(rateLimiterService.tryConsumeApiPermit(tenantId, RateLimitPlan.STANDARD).allowed()).isFalse();

        // The same tenant's redirect bucket is untouched.
        assertThat(rateLimiterService.tryConsumeRedirectPermit(tenantId, RateLimitPlan.STANDARD).allowed()).isTrue();
    }

    @Test
    void premiumPlan_getsALargerBucketThanStandard() {
        Long standardTenant = 10L;
        Long premiumTenant = 11L;

        for (int i = 0; i < STANDARD_API_PERMITS; i++) {
            rateLimiterService.tryConsumeApiPermit(standardTenant, RateLimitPlan.STANDARD);
        }
        assertThat(rateLimiterService.tryConsumeApiPermit(standardTenant, RateLimitPlan.STANDARD).allowed()).isFalse();

        // Premium's limit is higher, so the same number of requests is still allowed.
        for (int i = 0; i < STANDARD_API_PERMITS; i++) {
            assertThat(rateLimiterService.tryConsumeApiPermit(premiumTenant, RateLimitPlan.PREMIUM).allowed()).isTrue();
        }
    }

    @Test
    void planUpgradeMidSession_immediatelyGetsTheNewLimit_notStuckOnTheOldOne() {
        // Regression test: LocalRateLimiterBackend used to cache a Bucket4j
        // Bucket keyed only by bucketKey, and Bucket4j bakes its bandwidth
        // limit in at construction time. A tenant upgraded from STANDARD to
        // PREMIUM mid-session would keep hitting the SAME cached bucket
        // built with STANDARD's lower limit, since the cache key never
        // changed even though the caller now passes PREMIUM's higher one.
        Long tenantId = 20L;

        // Exhaust the STANDARD bucket completely.
        for (int i = 0; i < STANDARD_API_PERMITS; i++) {
            rateLimiterService.tryConsumeApiPermit(tenantId, RateLimitPlan.STANDARD);
        }
        assertThat(rateLimiterService.tryConsumeApiPermit(tenantId, RateLimitPlan.STANDARD).allowed())
                .as("sanity check: the STANDARD bucket really is exhausted before the 'upgrade'")
                .isFalse();

        // Simulate an admin plan upgrade: the very next call for the SAME
        // tenant id now arrives with RateLimitPlan.PREMIUM instead.
        RateLimitResult afterUpgrade = rateLimiterService.tryConsumeApiPermit(tenantId, RateLimitPlan.PREMIUM);

        assertThat(afterUpgrade.allowed())
                .as("an upgraded tenant must not stay stuck behind their old plan's exhausted bucket")
                .isTrue();
        assertThat(afterUpgrade.limitPerMinute()).isEqualTo(PREMIUM_API_PERMITS);
    }
}
