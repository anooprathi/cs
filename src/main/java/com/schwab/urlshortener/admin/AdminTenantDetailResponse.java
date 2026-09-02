package com.schwab.urlshortener.admin;

import com.schwab.urlshortener.tenant.RateLimitPlan;

import java.time.Instant;

/** Tenant summary plus a snapshot of current-period activity — the kind
 *  of at-a-glance view an admin drilling into one tenant would want,
 *  without having to separately query billing/usage for it. */
public record AdminTenantDetailResponse(
        Long tenantId,
        String name,
        RateLimitPlan plan,
        boolean active,
        Instant createdAt,
        long totalLinkCount,
        String currentBillingPeriod,
        long apiCallsThisPeriod,
        long redirectsThisPeriod
) {
}
