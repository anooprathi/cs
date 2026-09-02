package com.schwab.urlshortener.billing;

import com.schwab.urlshortener.tenant.RateLimitPlan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns metered usage ({@link TenantUsageRecord}) into a rated
 * {@link BillingStatementResponse} for the current calendar-month period.
 *
 * IMPORTANT — scope boundary: this computes what a tenant WOULD owe. It
 * does not charge a card, move money, or integrate a payment processor —
 * doing so would mean handling payment credentials and executing a
 * financial transaction, both of which are out of scope for this
 * exercise (and, per policy, not something this assistant implements
 * directly). In a real system, this statement is exactly the input
 * you'd hand to a payment provider (e.g. Stripe's usage-based billing /
 * metered subscription APIs) to actually collect payment — that
 * integration point is called out in ARCHITECTURE.md rather than faked
 * here.
 */
@Service
public class BillingService {

    private final TenantUsageRecordRepository usageRepository;
    private final UsageMeteringService usageMeteringService;
    private final BillingProperties billingProperties;

    public BillingService(TenantUsageRecordRepository usageRepository,
                           UsageMeteringService usageMeteringService,
                           BillingProperties billingProperties) {
        this.usageRepository = usageRepository;
        this.usageMeteringService = usageMeteringService;
        this.billingProperties = billingProperties;
    }

    @Transactional(readOnly = true)
    public BillingStatementResponse getCurrentStatement(Long tenantId, RateLimitPlan plan) {
        return getStatementForPeriod(tenantId, plan, usageMeteringService.currentPeriod());
    }

    /**
     * Rates a specific billing period (not necessarily the current one) —
     * used directly by {@code InvoiceService} when generating an invoice
     * for an arbitrary past/current period.
     */
    @Transactional(readOnly = true)
    public BillingStatementResponse getStatementForPeriod(Long tenantId, RateLimitPlan plan, String period) {
        TenantUsageRecord usage = usageRepository.findByTenantIdAndPeriodYearMonth(tenantId, period)
                .orElse(TenantUsageRecord.builder()
                        .tenantId(tenantId).periodYearMonth(period)
                        .apiCallCount(0).redirectCount(0).build());

        BillingProperties.PlanPricing pricing = billingProperties.plans().get(plan);
        if (pricing == null) {
            throw new IllegalStateException(
                    "No billing configuration bound for plan " + plan + " — check app.billing.plans.* properties");
        }

        long apiOverageUnits = Math.max(0, usage.getApiCallCount() - pricing.includedApiCalls());
        long redirectOverageUnits = Math.max(0, usage.getRedirectCount() - pricing.includedRedirects());
        long overageChargeCents = apiOverageUnits * pricing.overageApiCallCents()
                + redirectOverageUnits * pricing.overageRedirectCents();
        long totalChargeCents = pricing.baseFeeCents() + overageChargeCents;

        return new BillingStatementResponse(
                tenantId,
                plan,
                period,
                usage.getApiCallCount(),
                pricing.includedApiCalls(),
                usage.getRedirectCount(),
                pricing.includedRedirects(),
                pricing.baseFeeCents(),
                overageChargeCents,
                totalChargeCents
        );
    }
}
