package com.schwab.urlshortener.billing;

import com.schwab.urlshortener.tenant.RateLimitPlan;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Binds {@code app.billing.plans.*} pricing, keyed by {@link RateLimitPlan}
 * — same Open/Closed rationale as RateLimitProperties: BillingService
 * does a map lookup, not a per-plan ternary, so a new plan tier is a
 * config + enum addition, not a change to BillingService's logic.
 *
 * All monetary amounts are integer cents (never floating point) to avoid
 * rounding drift — standard practice for money in software. Picked up
 * automatically via {@code @ConfigurationPropertiesScan}.
 */
@ConfigurationProperties(prefix = "app.billing")
public record BillingProperties(Map<RateLimitPlan, PlanPricing> plans) {

    public record PlanPricing(
            long baseFeeCents,
            long includedApiCalls,
            long includedRedirects,
            long overageApiCallCents,
            long overageRedirectCents
    ) {
    }
}
