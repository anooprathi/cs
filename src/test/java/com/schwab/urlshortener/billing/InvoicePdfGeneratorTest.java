package com.schwab.urlshortener.billing;

import com.schwab.urlshortener.tenant.RateLimitPlan;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InvoicePdfGeneratorTest {

    private final InvoicePdfGenerator generator = new InvoicePdfGenerator();

    @Test
    void generate_producesNonEmptyValidPdfBytes() {
        Invoice invoice = Invoice.builder()
                .id(1L)
                .invoiceNumber("INV-2026-08-000001")
                .tenantId(1L)
                .billingPeriod("2026-08")
                .plan(RateLimitPlan.STANDARD)
                .apiCallsUsed(60).apiCallsIncluded(50)
                .redirectsUsed(700).redirectsIncluded(500)
                .baseFeeCents(0).overageChargeCents(220).totalChargeCents(220)
                .status(InvoiceStatus.ISSUED)
                .issuedAt(Instant.now())
                .build();

        byte[] pdfBytes = generator.generate(invoice, "acme-corp");

        assertThat(pdfBytes).isNotEmpty();
        // Every valid PDF file starts with this magic header.
        String header = new String(pdfBytes, 0, 4, StandardCharsets.US_ASCII);
        assertThat(header).isEqualTo("%PDF");
    }

    @Test
    void generate_zeroUsageInvoice_stillRendersSuccessfully() {
        Invoice invoice = Invoice.builder()
                .id(2L)
                .invoiceNumber("INV-2026-08-000002")
                .tenantId(2L)
                .billingPeriod("2026-08")
                .plan(RateLimitPlan.PREMIUM)
                .apiCallsUsed(0).apiCallsIncluded(1000)
                .redirectsUsed(0).redirectsIncluded(20000)
                .baseFeeCents(4900).overageChargeCents(0).totalChargeCents(4900)
                .status(InvoiceStatus.ISSUED)
                .issuedAt(Instant.now())
                .build();

        byte[] pdfBytes = generator.generate(invoice, "another-tenant");

        assertThat(pdfBytes).isNotEmpty();
    }
}
