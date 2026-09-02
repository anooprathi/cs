package com.schwab.urlshortener.tenant;

public record TenantProfileResponse(
        Long tenantId,
        String name,
        RateLimitPlan plan
) {
}
