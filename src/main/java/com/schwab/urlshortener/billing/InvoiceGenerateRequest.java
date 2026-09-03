package com.schwab.urlshortener.billing;

import jakarta.validation.constraints.Pattern;

/**
 * billingPeriod is optional — omit it to generate an invoice for the
 * current calendar month. A null value passes @Pattern validation (only
 * non-null values are checked); the "not a future period" business rule
 * is enforced in InvoiceService, since that depends on the current date,
 * not just the field's shape.
 *
 * The pattern constrains the month digits to 01-12, not just "any two
 * digits" — an earlier version used "\\d{4}-\\d{2}", which happily
 * matched something like "2025-99" at this layer (catching it only later,
 * in InvoiceService's real calendar-aware validation). Tightening it here
 * too means a plainly-invalid month gets a field-level 400 at the normal
 * Bean Validation layer, consistent with every other malformed-input
 * case, rather than only being caught one layer deeper.
 */
public record InvoiceGenerateRequest(
        @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "billingPeriod must be in yyyy-MM format with a valid month (01-12)")
        String billingPeriod
) {
}
