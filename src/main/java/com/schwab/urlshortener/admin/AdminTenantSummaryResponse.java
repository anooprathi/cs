package com.schwab.urlshortener.admin;

import com.schwab.urlshortener.tenant.RateLimitPlan;

import java.time.Instant;

public record AdminTenantSummaryResponse(
        Long tenantId,
        String name,
        RateLimitPlan plan,
        boolean active,
        Instant createdAt
) {
}
