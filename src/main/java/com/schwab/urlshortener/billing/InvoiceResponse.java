package com.schwab.urlshortener.billing;

import com.schwab.urlshortener.tenant.RateLimitPlan;

import java.time.Instant;

public record InvoiceResponse(
        Long invoiceId,
        String invoiceNumber,
        String billingPeriod,
        RateLimitPlan plan,
        long apiCallsUsed,
        long apiCallsIncluded,
        long redirectsUsed,
        long redirectsIncluded,
        long baseFeeCents,
        long overageChargeCents,
        long totalChargeCents,
        InvoiceStatus status,
        Instant issuedAt
) {
}
