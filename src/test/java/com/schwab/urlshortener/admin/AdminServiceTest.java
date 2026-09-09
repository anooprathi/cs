package com.schwab.urlshortener.admin;

import com.schwab.urlshortener.billing.TenantUsageRecord;
import com.schwab.urlshortener.billing.TenantUsageRecordRepository;
import com.schwab.urlshortener.billing.UsageMeteringService;
import com.schwab.urlshortener.entity.UrlMapping;
import com.schwab.urlshortener.exception.TenantNotFoundException;
import com.schwab.urlshortener.repository.UrlMappingRepository;
import com.schwab.urlshortener.tenant.RateLimitPlan;
import com.schwab.urlshortener.tenant.Tenant;
import com.schwab.urlshortener.tenant.TenantApiKeyRotationResult;
import com.schwab.urlshortener.tenant.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    void listTenants_mapsPageOfTenantsToSummaries() {
        Tenant a = Tenant.builder().id(1L).name("acme").plan(RateLimitPlan.STANDARD).active(true).createdAt(Instant.now()).build();
        Tenant b = Tenant.builder().id(2L).name("other").plan(RateLimitPlan.PREMIUM).active(false).createdAt(Instant.now()).build();
        Pageable pageable = PageRequest.of(0, 50);
        when(tenantService.listAll(pageable)).thenReturn(new PageImpl<>(List.of(a, b), pageable, 2));

        var result = adminService.listTenants(pageable);

        assertThat(result.content()).hasSize(2);
        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.content().get(0).name()).isEqualTo("acme");
        assertThat(result.content().get(1).active()).isFalse();
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
    void rotateApiKey_delegatesToTenantServiceAndReturnsNewRawKey() {
        Tenant tenant = Tenant.builder().id(1L).name("acme").plan(RateLimitPlan.STANDARD).active(true).createdAt(Instant.now()).build();
        String rawKey = "usk_new-key";
        when(tenantService.rotateApiKey(1L)).thenReturn(new TenantApiKeyRotationResult(tenant, rawKey));

        AdminApiKeyRotationResponse result = adminService.rotateApiKey(1L);

        assertThat(result.tenantId()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("acme");
        assertThat(result.apiKey()).isEqualTo(rawKey);
        assertThat(result.rotatedAt()).isNotNull();
    }

    @Test
    void rotateApiKey_unknownTenant_propagatesNotFound() {
        when(tenantService.rotateApiKey(999L)).thenThrow(new TenantNotFoundException(999L));

        assertThatThrownBy(() -> adminService.rotateApiKey(999L))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void updateCustomDomain_delegatesToTenantServiceAndReturnsSummary() {
        Tenant updated = Tenant.builder().id(1L).name("acme").plan(RateLimitPlan.STANDARD).active(true)
                .createdAt(Instant.now()).customDomain("go.acme.com").build();
        when(tenantService.updateCustomDomain(1L, "go.acme.com")).thenReturn(updated);

        AdminTenantSummaryResponse result = adminService.updateCustomDomain(1L, "go.acme.com");

        assertThat(result.customDomain()).isEqualTo("go.acme.com");
    }

    @Test
    void updateCustomDomain_unknownTenant_propagatesNotFound() {
        when(tenantService.updateCustomDomain(999L, "go.acme.com")).thenThrow(new TenantNotFoundException(999L));

        assertThatThrownBy(() -> adminService.updateCustomDomain(999L, "go.acme.com"))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void listTenantUrls_returnsAllLinksIncludingInactive() {
        when(tenantService.getTenantById(1L)).thenReturn(Tenant.builder().id(1L).name("acme").build());
        UrlMapping active = UrlMapping.builder().shortCode("abc1234").originalUrl("https://example.com").active(true).clickCount(1).createdAt(Instant.now()).build();
        UrlMapping inactive = UrlMapping.builder().shortCode("old1234").originalUrl("https://example.com/old").active(false).clickCount(9).createdAt(Instant.now()).build();
        Pageable requested = PageRequest.of(0, 50);
        when(urlMappingRepository.findByTenantId(any(), any()))
                .thenReturn(new PageImpl<>(List.of(active, inactive), requested, 2));

        var result = adminService.listTenantUrls(1L, requested);

        assertThat(result.content()).hasSize(2);
        assertThat(result.content()).extracting(AdminUrlSummaryResponse::active).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void listTenantUrls_unknownTenant_throwsNotFound_beforeQueryingLinks() {
        when(tenantService.getTenantById(999L)).thenThrow(new TenantNotFoundException(999L));

        assertThatThrownBy(() -> adminService.listTenantUrls(999L, PageRequest.of(0, 50)))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void getUsageSummary_aggregatesAcrossTenants_andOnlyLooksUpActiveTenantIds() {
        Tenant a = Tenant.builder().id(1L).name("acme").build();
        Tenant b = Tenant.builder().id(2L).name("other").build();
        when(usageRecordRepository.findByPeriodYearMonth("2026-08")).thenReturn(List.of(
                TenantUsageRecord.builder().tenantId(1L).periodYearMonth("2026-08").apiCallCount(10).redirectCount(100).build(),
                TenantUsageRecord.builder().tenantId(2L).periodYearMonth("2026-08").apiCallCount(5).redirectCount(50).build()
        ));
        when(tenantService.findByIds(List.of(1L, 2L))).thenReturn(List.of(a, b));

        AdminUsageSummaryResponse summary = adminService.getUsageSummary();

        assertThat(summary.totalApiCalls()).isEqualTo(15L);
        assertThat(summary.totalRedirects()).isEqualTo(150L);
        assertThat(summary.byTenant()).hasSize(2);
        // activeTenantCount is scoped to THIS PERIOD's activity (2 usage
        // records), not "every tenant ever registered" — the bug this
        // rename/refactor fixed.
        assertThat(summary.activeTenantCount()).isEqualTo(2L);
        // Bounded lookup, not tenantService.listAll(): only the two
        // tenant ids that actually appear in this period's usage records.
        org.mockito.Mockito.verify(tenantService).findByIds(List.of(1L, 2L));
        org.mockito.Mockito.verify(tenantService, org.mockito.Mockito.never()).listAll();
    }

    @Test
    void getUsageSummary_tenantDeletedSinceUsageWasRecorded_stillIncludesTheRecord() {
        when(usageRecordRepository.findByPeriodYearMonth("2026-08")).thenReturn(List.of(
                TenantUsageRecord.builder().tenantId(99L).periodYearMonth("2026-08").apiCallCount(1).redirectCount(1).build()
        ));
        when(tenantService.findByIds(List.of(99L))).thenReturn(List.of()); // tenant no longer exists

        AdminUsageSummaryResponse summary = adminService.getUsageSummary();

        assertThat(summary.byTenant()).hasSize(1);
        assertThat(summary.byTenant().get(0).name()).contains("deleted tenant");
    }

    @Test
    void getUsageSummary_noUsageThisPeriod_returnsZeroedSummary() {
        when(usageRecordRepository.findByPeriodYearMonth("2026-08")).thenReturn(List.of());

        AdminUsageSummaryResponse summary = adminService.getUsageSummary();

        assertThat(summary.totalApiCalls()).isZero();
        assertThat(summary.activeTenantCount()).isZero();
        assertThat(summary.byTenant()).isEmpty();
    }
}
