package com.schwab.urlshortener.billing;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row per (tenant, calendar month) — the metering record that
 * {@link BillingService} rates into a dollar amount. Kept deliberately
 * separate from {@code UrlMapping.clickCount} (which is per-link
 * analytics): this is a tenant-level aggregate for billing, on its own
 * table so metering and link analytics can evolve independently and so
 * a billing-period rollover never touches link data.
 */
@Entity
@Table(
        name = "tenant_usage_record",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_tenant_usage_period", columnNames = {"tenantId", "periodYearMonth"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantUsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tenantId;

    /** e.g. "2026-08" — a calendar-month billing period, UTC. */
    @Column(nullable = false, length = 7)
    private String periodYearMonth;

    @Column(nullable = false)
    @Builder.Default
    private long apiCallCount = 0L;

    @Column(nullable = false)
    @Builder.Default
    private long redirectCount = 0L;
}
