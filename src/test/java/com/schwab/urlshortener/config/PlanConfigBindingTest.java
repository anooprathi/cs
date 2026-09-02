package com.schwab.urlshortener.config;

import com.schwab.urlshortener.billing.BillingProperties;
import com.schwab.urlshortener.ratelimit.RateLimitProperties;
import com.schwab.urlshortener.tenant.RateLimitPlan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real Spring context and asserts the enum-keyed
 * {@code Map<RateLimitPlan, ...>} @ConfigurationProperties actually bind
 * from application*.properties as expected. This is the highest-risk
 * change in the SOLID refactor pass: Spring Boot's relaxed binding is
 * documented to support enum map keys, but a boot-level assertion here
 * catches a binding regression immediately and explicitly, rather than
 * failing later and confusingly inside TenantRateLimiterService/
 * BillingService with an IllegalStateException whose real cause is
 * "the map came back empty."
 */
@SpringBootTest
@ActiveProfiles("test")
class PlanConfigBindingTest {

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private BillingProperties billingProperties;

    @Test
    void rateLimitProperties_bindsBothPlanTiers() {
        assertThat(rateLimitProperties.plans())
                .containsKeys(RateLimitPlan.STANDARD, RateLimitPlan.PREMIUM);
        assertThat(rateLimitProperties.plans().get(RateLimitPlan.STANDARD).apiPermitsPerMinute()).isPositive();
        assertThat(rateLimitProperties.plans().get(RateLimitPlan.PREMIUM).apiPermitsPerMinute()).isPositive();
    }

    @Test
    void billingProperties_bindsBothPlanTiers() {
        assertThat(billingProperties.plans())
                .containsKeys(RateLimitPlan.STANDARD, RateLimitPlan.PREMIUM);
        assertThat(billingProperties.plans().get(RateLimitPlan.PREMIUM).baseFeeCents()).isPositive();
    }
}
