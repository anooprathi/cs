package com.schwab.urlshortener.admin;

import java.util.List;

/** System-wide usage for the current billing period — what an admin would
 *  check for platform-level capacity planning or spotting an outlier
 *  tenant, as opposed to BillingService's per-tenant statement.
 *
 *  activeTenantCount is scoped to THIS PERIOD's activity — the same scope
 *  as byTenant — not the platform's total registered tenant count. A
 *  tenant that registered but hasn't made a single call/redirect this
 *  month is absent from byTenant and correctly excluded here too; a field
 *  named merely "totalTenants" sitting next to a period-scoped breakdown
 *  was ambiguous about which count it meant, and silently meant the
 *  wrong one (every tenant ever registered, not just active ones). */
public record AdminUsageSummaryResponse(
        String billingPeriod,
        long activeTenantCount,
        long totalApiCalls,
        long totalRedirects,
        List<AdminTenantUsageBreakdown> byTenant
) {
}
