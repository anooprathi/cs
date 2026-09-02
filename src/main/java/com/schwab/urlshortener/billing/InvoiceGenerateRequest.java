package com.schwab.urlshortener.billing;

import jakarta.validation.constraints.Pattern;

/**
 * billingPeriod is optional — omit it to generate an invoice for the
 * current calendar month. A null value passes @Pattern validation (only
 * non-null values are checked); the "not a future period" business rule
 * is enforced in InvoiceService, since that depends on the current date,
 * not just the field's shape.
 */
public record InvoiceGenerateRequest(
        @Pattern(regexp = "\\d{4}-\\d{2}", message = "billingPeriod must be in yyyy-MM format")
        String billingPeriod
) {
}
