package com.schwab.urlshortener.billing;

/**
 * Placeholder lifecycle status — this system does not process payments
 * (see BillingService Javadoc), so ISSUED is the only status it can ever
 * legitimately set today. Modeled as an enum rather than a boolean so a
 * future real payment integration (webhook marking an invoice PAID, a
 * dunning process marking one OVERDUE, a correction marking one VOID)
 * doesn't require an invoice schema migration.
 */
public enum InvoiceStatus {
    ISSUED,
    VOID
}
