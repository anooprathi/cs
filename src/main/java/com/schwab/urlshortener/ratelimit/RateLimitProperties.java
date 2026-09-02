package com.schwab.urlshortener.ratelimit;

import com.schwab.urlshortener.tenant.RateLimitPlan;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Binds {@code app.rate-limit.plans.*} from application*.properties into a
 * type-safe record, keyed by {@link RateLimitPlan} rather than one named
 * field per plan. This is the Open/Closed fix over an earlier version
 * that had separate `standard`/`premium` fields and a
 * `plan == PREMIUM ? ... : ...` ternary in TenantRateLimiterService: a
 * new plan tier is now purely a config addition (plus adding the enum
 * constant) — TenantRateLimiterService's lookup code does not change.
 *
 * Picked up automatically via {@code @ConfigurationPropertiesScan} on
 * {@code UrlShortenerApplication}. Property keys, e.g.:
 * {@code app.rate-limit.plans.standard.api-permits-per-minute=20}
 * (Spring Boot's relaxed binding matches the "standard"/"premium"
 * segment against the RateLimitPlan enum constants case-insensitively.)
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(Map<RateLimitPlan, PlanLimits> plans) {

    public record PlanLimits(long apiPermitsPerMinute, long redirectPermitsPerMinute) {
    }
}
