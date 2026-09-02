package com.schwab.urlshortener.admin;

public record AdminTenantUsageBreakdown(
        Long tenantId,
        String name,
        long apiCalls,
        long redirects
) {
}
