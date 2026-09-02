package com.schwab.urlshortener.billing;

import com.schwab.urlshortener.tenant.RateLimitPlan;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A generated invoice: a frozen snapshot of {@link BillingStatementResponse}
 * for one tenant/period, taken at {@code issuedAt}. Deliberately NOT a live
 * view over {@code TenantUsageRecord} — usage for the current period keeps
 * changing as the tenant keeps using the service, but an issued invoice
 * must not silently change amounts after the fact. Once generated, an
 * invoice's monetary fields are fixed; regenerating for the same period is
 * rejected (see InvoiceService) rather than overwritten.
 *
 * status is a single-value placeholder (ISSUED) today — there is no
 * payment processing in this system (see BillingService Javadoc), so
 * there is nothing yet that could move an invoice to PAID/OVERDUE. The
 * field exists so that transition has somewhere to go if a real payment
 * integration is added later, without an invoice schema migration.
 */
@Entity
@Table(
        name = "invoice",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_invoice_tenant_period", columnNames = {"tenantId", "billingPeriod"}),
        indexes = @Index(name = "idx_invoice_tenant_id", columnList = "tenantId")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-facing identifier, e.g. "INV-2026-08-000042" — derived from id after first save; see InvoiceService. */
    @Column(nullable = false, unique = true, length = 40)
    private String invoiceNumber;

    @Column(nullable = false)
    private Long tenantId;

    /** "yyyy-MM" — the billing period this invoice covers. */
    @Column(nullable = false, length = 7)
    private String billingPeriod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RateLimitPlan plan;

    @Column(nullable = false)
    private long apiCallsUsed;

    @Column(nullable = false)
    private long apiCallsIncluded;

    @Column(nullable = false)
    private long redirectsUsed;

    @Column(nullable = false)
    private long redirectsIncluded;

    @Column(nullable = false)
    private long baseFeeCents;

    @Column(nullable = false)
    private long overageChargeCents;

    @Column(nullable = false)
    private long totalChargeCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private InvoiceStatus status = InvoiceStatus.ISSUED;

    @Column(nullable = false, updatable = false)
    private Instant issuedAt;

    @PrePersist
    void onCreate() {
        if (issuedAt == null) {
            issuedAt = Instant.now();
        }
    }
}
