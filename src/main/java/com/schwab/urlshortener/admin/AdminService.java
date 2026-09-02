package com.schwab.urlshortener.admin;

import com.schwab.urlshortener.billing.TenantUsageRecord;
import com.schwab.urlshortener.billing.TenantUsageRecordRepository;
import com.schwab.urlshortener.billing.UsageMeteringService;
import com.schwab.urlshortener.entity.UrlMapping;
import com.schwab.urlshortener.repository.UrlMappingRepository;
import com.schwab.urlshortener.tenant.RateLimitPlan;
import com.schwab.urlshortener.tenant.Tenant;
import com.schwab.urlshortener.tenant.TenantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cross-tenant read/management operations for the admin surface. Kept as
 * its own service (not folded into TenantService) since it composes
 * across three aggregates — tenants, links, and usage — that each already
 * have their own service/repository; this is the orchestration layer for
 * that composition, the same role UrlShortenerServiceImpl plays for its
 * own aggregate rather than a place for admin-specific business rules
 * that don't belong anywhere else.
 */
@Service
public class AdminService {

    private final TenantService tenantService;
    private final UrlMappingRepository urlMappingRepository;
    private final TenantUsageRecordRepository usageRecordRepository;
    private final UsageMeteringService usageMeteringService;

    public AdminService(TenantService tenantService,
                         UrlMappingRepository urlMappingRepository,
                         TenantUsageRecordRepository usageRecordRepository,
                         UsageMeteringService usageMeteringService) {
        this.tenantService = tenantService;
        this.urlMappingRepository = urlMappingRepository;
        this.usageRecordRepository = usageRecordRepository;
        this.usageMeteringService = usageMeteringService;
    }

    @Transactional(readOnly = true)
    public List<AdminTenantSummaryResponse> listTenants() {
        return tenantService.listAll().stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminTenantDetailResponse getTenantDetail(Long tenantId) {
        Tenant tenant = tenantService.getTenantById(tenantId);
        String period = usageMeteringService.currentPeriod();
        TenantUsageRecord usage = usageRecordRepository.findByTenantIdAndPeriodYearMonth(tenantId, period)
                .orElse(null);
        long linkCount = urlMappingRepository.countByTenantId(tenantId);

        return new AdminTenantDetailResponse(
                tenant.getId(),
                tenant.getName(),
                tenant.getPlan(),
                tenant.isActive(),
                tenant.getCreatedAt(),
                linkCount,
                period,
                usage != null ? usage.getApiCallCount() : 0L,
                usage != null ? usage.getRedirectCount() : 0L
        );
    }

    @Transactional
    public AdminTenantSummaryResponse updatePlan(Long tenantId, RateLimitPlan newPlan) {
        return toSummary(tenantService.updatePlan(tenantId, newPlan));
    }

    @Transactional
    public AdminTenantSummaryResponse updateStatus(Long tenantId, boolean active) {
        return toSummary(tenantService.updateActiveStatus(tenantId, active));
    }

    @Transactional(readOnly = true)
    public List<AdminUrlSummaryResponse> listTenantUrls(Long tenantId) {
        // Confirms the tenant exists (404s otherwise) before returning an
        // empty-vs-nonexistent list — an unknown tenant id and a tenant
        // with zero links should not look the same to the caller.
        tenantService.getTenantById(tenantId);

        return urlMappingRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(this::toUrlSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminUsageSummaryResponse getUsageSummary() {
        String period = usageMeteringService.currentPeriod();
        List<TenantUsageRecord> records = usageRecordRepository.findByPeriodYearMonth(period);

        Map<Long, Tenant> tenantsById = tenantService.listAll().stream()
                .collect(Collectors.toMap(Tenant::getId, Function.identity()));

        long totalApiCalls = 0;
        long totalRedirects = 0;
        List<AdminTenantUsageBreakdown> breakdown = new ArrayList<>();
        for (TenantUsageRecord record : records) {
            totalApiCalls += record.getApiCallCount();
            totalRedirects += record.getRedirectCount();
            Tenant tenant = tenantsById.get(record.getTenantId());
            String name = tenant != null ? tenant.getName() : "(deleted tenant " + record.getTenantId() + ")";
            breakdown.add(new AdminTenantUsageBreakdown(record.getTenantId(), name, record.getApiCallCount(), record.getRedirectCount()));
        }

        return new AdminUsageSummaryResponse(period, tenantsById.size(), totalApiCalls, totalRedirects, breakdown);
    }

    private AdminTenantSummaryResponse toSummary(Tenant tenant) {
        return new AdminTenantSummaryResponse(
                tenant.getId(), tenant.getName(), tenant.getPlan(), tenant.isActive(), tenant.getCreatedAt());
    }

    private AdminUrlSummaryResponse toUrlSummary(UrlMapping mapping) {
        return new AdminUrlSummaryResponse(
                mapping.getShortCode(),
                mapping.getOriginalUrl(),
                mapping.getClickCount(),
                mapping.isActive(),
                mapping.getCreatedAt(),
                mapping.getExpiresAt()
        );
    }
}
