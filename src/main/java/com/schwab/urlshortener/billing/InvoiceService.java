package com.schwab.urlshortener.billing;

import com.schwab.urlshortener.exception.DuplicateInvoiceException;
import com.schwab.urlshortener.exception.InvalidBillingPeriodException;
import com.schwab.urlshortener.exception.InvoiceNotFoundException;
import com.schwab.urlshortener.tenant.RateLimitPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Generates and retrieves {@link Invoice} snapshots. Generation is
 * idempotent per (tenant, billing period): calling it twice for the same
 * period is rejected with {@link DuplicateInvoiceException} rather than
 * silently creating a duplicate or overwriting the first one — real
 * invoices, once issued, don't get quietly regenerated.
 */
@Service
@Slf4j
public class InvoiceService {

    private final InvoiceRepository repository;
    private final BillingService billingService;
    private final UsageMeteringService usageMeteringService;

    public InvoiceService(InvoiceRepository repository, BillingService billingService, UsageMeteringService usageMeteringService) {
        this.repository = repository;
        this.billingService = billingService;
        this.usageMeteringService = usageMeteringService;
    }

    @Transactional
    public InvoiceResponse generateInvoice(Long tenantId, RateLimitPlan plan, String requestedPeriod) {
        String period = requestedPeriod != null ? requestedPeriod : usageMeteringService.currentPeriod();
        validatePeriod(period);

        Optional<Invoice> existing = repository.findByTenantIdAndBillingPeriod(tenantId, period);
        if (existing.isPresent()) {
            throw new DuplicateInvoiceException(period, existing.get().getInvoiceNumber());
        }

        BillingStatementResponse statement = billingService.getStatementForPeriod(tenantId, plan, period);

        Invoice invoice = Invoice.builder()
                .invoiceNumber(String.format("INV-%s-%06d", period, tenantId))
                .tenantId(tenantId)
                .billingPeriod(period)
                .plan(plan)
                .apiCallsUsed(statement.apiCallsUsed())
                .apiCallsIncluded(statement.apiCallsIncluded())
                .redirectsUsed(statement.redirectsUsed())
                .redirectsIncluded(statement.redirectsIncluded())
                .baseFeeCents(statement.baseFeeCents())
                .overageChargeCents(statement.overageChargeCents())
                .totalChargeCents(statement.totalChargeCents())
                .status(InvoiceStatus.ISSUED)
                .build();

        Invoice saved = repository.save(invoice);
        log.info("Generated invoice {} for tenantId={} period={} totalChargeCents={}",
                saved.getInvoiceNumber(), tenantId, period, saved.getTotalChargeCents());

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> listInvoices(Long tenantId) {
        return repository.findByTenantIdOrderByIssuedAtDesc(tenantId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public InvoiceResponse getInvoice(Long invoiceId, Long tenantId) {
        return toResponse(getInvoiceEntity(invoiceId, tenantId));
    }

    /** Tenant-scoped entity fetch — used directly by the PDF endpoint, which needs the full entity. */
    @Transactional(readOnly = true)
    public Invoice getInvoiceEntity(Long invoiceId, Long tenantId) {
        return repository.findByIdAndTenantId(invoiceId, tenantId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
    }

    /**
     * "yyyy-MM" strings compare lexicographically in the same order as
     * chronologically, since the format is fixed-width and zero-padded —
     * used below for the future-period check without needing to parse a
     * real date for that comparison.
     *
     * The format itself DOES need real calendar validation, not just a
     * structural regex: "\\d{4}-\\d{2}" matches "2025-99" just as happily
     * as "2025-08" — four digits, a dash, two digits, with no concept of
     * "and the second group must be a valid month 01-12". YearMonth.parse
     * enforces that as a normal part of java.time's field-range validation
     * (MONTH_OF_YEAR strictly rejects values outside 1-12 regardless of
     * resolver style — this isn't the kind of leniency day-of-month
     * sometimes gets), so it does both the format AND the range check in
     * one place instead of a hand-rolled regex trying to encode calendar
     * rules a regex isn't well suited to expressing.
     */
    private static final java.time.format.DateTimeFormatter PERIOD_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM");

    private void validatePeriod(String period) {
        if (period == null) {
            throw new InvalidBillingPeriodException("billingPeriod must be in yyyy-MM format: null");
        }
        try {
            java.time.YearMonth.parse(period, PERIOD_FORMATTER);
        } catch (java.time.format.DateTimeParseException e) {
            throw new InvalidBillingPeriodException("billingPeriod must be a valid yyyy-MM month: " + period);
        }

        String current = usageMeteringService.currentPeriod();
        if (period.compareTo(current) > 0) {
            throw new InvalidBillingPeriodException("Cannot generate an invoice for a future period: " + period);
        }
    }

    private InvoiceResponse toResponse(Invoice invoice) {
        return new InvoiceResponse(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getBillingPeriod(),
                invoice.getPlan(),
                invoice.getApiCallsUsed(),
                invoice.getApiCallsIncluded(),
                invoice.getRedirectsUsed(),
                invoice.getRedirectsIncluded(),
                invoice.getBaseFeeCents(),
                invoice.getOverageChargeCents(),
                invoice.getTotalChargeCents(),
                invoice.getStatus(),
                invoice.getIssuedAt()
        );
    }
}
