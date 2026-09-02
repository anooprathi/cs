package com.schwab.urlshortener.billing;

import com.schwab.urlshortener.tenant.RateLimitPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    private static final BillingProperties.PlanPricing STANDARD_PRICING =
            new BillingProperties.PlanPricing(0, 50, 500, 2, 1);
    private static final BillingProperties.PlanPricing PREMIUM_PRICING =
            new BillingProperties.PlanPricing(4900, 1000, 20000, 1, 0);

    @Mock
    private TenantUsageRecordRepository usageRepository;

    @Mock
    private UsageMeteringService usageMeteringService;

    private BillingService billingService;

    @BeforeEach
    void setUp() {
        when(usageMeteringService.currentPeriod()).thenReturn("2026-08");
        BillingProperties properties = new BillingProperties(java.util.Map.of(
                RateLimitPlan.STANDARD, STANDARD_PRICING,
                RateLimitPlan.PREMIUM, PREMIUM_PRICING
        ));
        billingService = new BillingService(usageRepository, usageMeteringService, properties);
    }

    @Test
    void usageWithinIncludedQuota_chargesOnlyBaseFee() {
        TenantUsageRecord usage = TenantUsageRecord.builder()
                .tenantId(1L).periodYearMonth("2026-08").apiCallCount(10).redirectCount(100).build();
        when(usageRepository.findByTenantIdAndPeriodYearMonth(1L, "2026-08")).thenReturn(Optional.of(usage));

        BillingStatementResponse statement = billingService.getCurrentStatement(1L, RateLimitPlan.STANDARD);

        assertThat(statement.overageChargeCents()).isZero();
        assertThat(statement.totalChargeCents()).isEqualTo(STANDARD_PRICING.baseFeeCents());
    }

    @Test
    void usageOverQuota_chargesOveragePerUnit() {
        // 50 included api calls, used 60 -> 10 over at 2 cents each = 20 cents
        // 500 included redirects, used 700 -> 200 over at 1 cent each = 200 cents
        TenantUsageRecord usage = TenantUsageRecord.builder()
                .tenantId(1L).periodYearMonth("2026-08").apiCallCount(60).redirectCount(700).build();
        when(usageRepository.findByTenantIdAndPeriodYearMonth(1L, "2026-08")).thenReturn(Optional.of(usage));

        BillingStatementResponse statement = billingService.getCurrentStatement(1L, RateLimitPlan.STANDARD);

        assertThat(statement.overageChargeCents()).isEqualTo(20 + 200);
        assertThat(statement.totalChargeCents()).isEqualTo(STANDARD_PRICING.baseFeeCents() + 220);
    }

    @Test
    void noUsageRecordYet_treatsUsageAsZero() {
        when(usageRepository.findByTenantIdAndPeriodYearMonth(1L, "2026-08")).thenReturn(Optional.empty());

        BillingStatementResponse statement = billingService.getCurrentStatement(1L, RateLimitPlan.STANDARD);

        assertThat(statement.apiCallsUsed()).isZero();
        assertThat(statement.redirectsUsed()).isZero();
        assertThat(statement.totalChargeCents()).isEqualTo(STANDARD_PRICING.baseFeeCents());
    }

    @Test
    void premiumPlan_usesItsOwnPricingAndBaseFee() {
        TenantUsageRecord usage = TenantUsageRecord.builder()
                .tenantId(2L).periodYearMonth("2026-08").apiCallCount(1500).redirectCount(0).build();
        when(usageRepository.findByTenantIdAndPeriodYearMonth(2L, "2026-08")).thenReturn(Optional.of(usage));

        BillingStatementResponse statement = billingService.getCurrentStatement(2L, RateLimitPlan.PREMIUM);

        // 1000 included, used 1500 -> 500 over at 1 cent = 500 cents overage
        assertThat(statement.overageChargeCents()).isEqualTo(500);
        assertThat(statement.totalChargeCents()).isEqualTo(PREMIUM_PRICING.baseFeeCents() + 500);
        assertThat(statement.plan()).isEqualTo(RateLimitPlan.PREMIUM);
    }
}
