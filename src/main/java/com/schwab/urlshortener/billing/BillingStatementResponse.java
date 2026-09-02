package com.schwab.urlshortener.billing;

import com.schwab.urlshortener.tenant.RateLimitPlan;

/**
 * A rated usage statement for the current billing period. All amounts are
 * integer cents. This is a computed figure for visibility/estimation —
 * see {@link BillingService} Javadoc for what this system does and does
 * not do with it.
 */
public record BillingStatementResponse(
        Long tenantId,
        RateLimitPlan plan,
        String billingPeriod,
        long apiCallsUsed,
        long apiCallsIncluded,
        long redirectsUsed,
        long redirectsIncluded,
        long baseFeeCents,
        long overageChargeCents,
        long totalChargeCents
) {
}
