package com.schwab.urlshortener.admin;

import com.schwab.urlshortener.billing.TenantUsageRecord;
import com.schwab.urlshortener.billing.TenantUsageRecordRepository;
import com.schwab.urlshortener.billing.UsageMeteringService;
import com.schwab.urlshortener.entity.UrlMapping;
import com.schwab.urlshortener.exception.TenantNotFoundException;
import com.schwab.urlshortener.repository.UrlMappingRepository;
import com.schwab.urlshortener.tenant.RateLimitPlan;
import com.schwab.urlshortener.tenant.Tenant;
import com.schwab.urlshortener.tenant.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminServiceTest {

    @Mock
    private TenantService tenantService;

    @Mock
    private UrlMappingRepository urlMappingRepository;

    @Mock
    private TenantUsageRecordRepository usageRecordRepository;

    @Mock
    private UsageMeteringService usageMeteringService;

    private AdminService adminService;

    @BeforeEach
    void setUp() {
        adminService = new AdminService(tenantService, urlMappingRepository, usageRecordRepository, usageMeteringService);
        when(usageMeteringService.currentPeriod()).thenReturn("2026-08");
    }

    @Test
    void listTenants_mapsAllTenantsToSummaries() {
        Tenant a = Tenant.builder().id(1L).name("acme").plan(RateLimitPlan.STANDARD).active(true).createdAt(Instant.now()).build();
        Tenant b = Tenant.builder().id(2L).name("other").plan(RateLimitPlan.PREMIUM).active(false).createdAt(Instant.now()).build();
        when(tenantService.listAll()).thenReturn(List.of(a, b));

        List<AdminTenantSummaryResponse> result = adminService.listTenants();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("acme");
        assertThat(result.get(1).active()).isFalse();
    }

    @Test
    void getTenantDetail_withUsageThisPeriod_includesCounts() {
        Tenant tenant = Tenant.builder().id(1L).name("acme").plan(RateLimitPlan.STANDARD).active(true).createdAt(Instant.now()).build();
        when(tenantService.getTenantById(1L)).thenReturn(tenant);
        when(usageRecordRepository.findByTenantIdAndPeriodYearMonth(1L, "2026-08"))
                .thenReturn(Optional.of(TenantUsageRecord.builder().tenantId(1L).periodYearMonth("2026-08").apiCallCount(5).redirectCount(50).build()));
        when(urlMappingRepository.countByTenantId(1L)).thenReturn(3L);

        AdminTenantDetailResponse detail = adminService.getTenantDetail(1L);

        assertThat(detail.totalLinkCount()).isEqualTo(3L);
        assertThat(detail.apiCallsThisPeriod()).isEqualTo(5L);
        assertThat(detail.redirectsThisPeriod()).isEqualTo(50L);
        assertThat(detail.currentBillingPeriod()).isEqualTo("2026-08");
    }

    @Test
    void getTenantDetail_noUsageRecordYet_reportsZero() {
        Tenant tenant = Tenant.builder().id(1L).name("acme").plan(RateLimitPlan.STANDARD).active(true).createdAt(Instant.now()).build();
        when(tenantService.getTenantById(1L)).thenReturn(tenant);
        when(usageRecordRepository.findByTenantIdAndPeriodYearMonth(1L, "2026-08")).thenReturn(Optional.empty());
        when(urlMappingRepository.countByTenantId(1L)).thenReturn(0L);

        AdminTenantDetailResponse detail = adminService.getTenantDetail(1L);

        assertThat(detail.apiCallsThisPeriod()).isZero();
        assertThat(detail.redirectsThisPeriod()).isZero();
    }

    @Test
    void getTenantDetail_unknownTenant_propagatesNotFound() {
        when(tenantService.getTenantById(999L)).thenThrow(new TenantNotFoundException(999L));

        assertThatThrownBy(() -> adminService.getTenantDetail(999L))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void updatePlan_delegatesToTenantServiceAndReturnsSummary() {
        Tenant updated = Tenant.builder().id(1L).name("acme").plan(RateLimitPlan.PREMIUM).active(true).createdAt(Instant.now()).build();
        when(tenantService.updatePlan(1L, RateLimitPlan.PREMIUM)).thenReturn(updated);

        AdminTenantSummaryResponse result = adminService.updatePlan(1L, RateLimitPlan.PREMIUM);

        assertThat(result.plan()).isEqualTo(RateLimitPlan.PREMIUM);
    }

    @Test
    void updateStatus_delegatesToTenantServiceAndReturnsSummary() {
        Tenant deactivated = Tenant.builder().id(1L).name("acme").plan(RateLimitPlan.STANDARD).active(false).createdAt(Instant.now()).build();
        when(tenantService.updateActiveStatus(1L, false)).thenReturn(deactivated);

        AdminTenantSummaryResponse result = adminService.updateStatus(1L, false);

        assertThat(result.active()).isFalse();
    }

    @Test
    void listTenantUrls_returnsAllLinksIncludingInactive() {
        when(tenantService.getTenantById(1L)).thenReturn(Tenant.builder().id(1L).name("acme").build());
        UrlMapping active = UrlMapping.builder().shortCode("abc1234").originalUrl("https://example.com").active(true).clickCount(1).createdAt(Instant.now()).build();
        UrlMapping inactive = UrlMapping.builder().shortCode("old1234").originalUrl("https://example.com/old").active(false).clickCount(9).createdAt(Instant.now()).build();
        when(urlMappingRepository.findByTenantIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(active, inactive));

        List<AdminUrlSummaryResponse> result = adminService.listTenantUrls(1L);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(AdminUrlSummaryResponse::active).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void listTenantUrls_unknownTenant_throwsNotFound_beforeQueryingLinks() {
        when(tenantService.getTenantById(999L)).thenThrow(new TenantNotFoundException(999L));

        assertThatThrownBy(() -> adminService.listTenantUrls(999L))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void getUsageSummary_aggregatesAcrossTenants() {
        Tenant a = Tenant.builder().id(1L).name("acme").build();
        Tenant b = Tenant.builder().id(2L).name("other").build();
        when(tenantService.listAll()).thenReturn(List.of(a, b));
        when(usageRecordRepository.findByPeriodYearMonth("2026-08")).thenReturn(List.of(
                TenantUsageRecord.builder().tenantId(1L).periodYearMonth("2026-08").apiCallCount(10).redirectCount(100).build(),
                TenantUsageRecord.builder().tenantId(2L).periodYearMonth("2026-08").apiCallCount(5).redirectCount(50).build()
        ));

        AdminUsageSummaryResponse summary = adminService.getUsageSummary();

        assertThat(summary.totalApiCalls()).isEqualTo(15L);
        assertThat(summary.totalRedirects()).isEqualTo(150L);
        assertThat(summary.byTenant()).hasSize(2);
    }

    @Test
    void getUsageSummary_noUsageThisPeriod_returnsZeroedSummary() {
        when(tenantService.listAll()).thenReturn(List.of());
        when(usageRecordRepository.findByPeriodYearMonth("2026-08")).thenReturn(List.of());

        AdminUsageSummaryResponse summary = adminService.getUsageSummary();

        assertThat(summary.totalApiCalls()).isZero();
        assertThat(summary.byTenant()).isEmpty();
    }
}
