package com.schwab.urlshortener.admin;

import java.util.List;

/** System-wide usage for the current billing period — what an admin would
 *  check for platform-level capacity planning or spotting an outlier
 *  tenant, as opposed to BillingService's per-tenant statement. */
public record AdminUsageSummaryResponse(
        String billingPeriod,
        long totalTenants,
        long totalApiCalls,
        long totalRedirects,
        List<AdminTenantUsageBreakdown> byTenant
) {
}
