package com.schwab.urlshortener.admin;

import com.schwab.urlshortener.config.OpenApiConfig;
import com.schwab.urlshortener.dto.PageResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Cross-tenant visibility and management, authenticated separately from
 * the tenant-facing API via X-Admin-Key (see AdminAuthenticationFilter) —
 * never a tenant's own X-API-Key, however privileged. This is a distinct
 * identity, not an elevated tenant.
 *
 * The two listing endpoints (tenants, a tenant's urls) are paginated —
 * standard Spring paging query params (page, size, sort), defaulting to
 * 50 per page. Unpaginated "return everything" was fine for a demo
 * tenant count; it would not have held up as either list grew.
 *
 * @SecurityRequirement here overrides (rather than adds to) the global
 * ApiKeyAuth requirement from OpenApiConfig, so Swagger UI's "Try it out"
 * sends X-Admin-Key for these endpoints instead of the tenant X-API-Key.
 */
@RestController
@RequestMapping("/api/v1/admin")
@SecurityRequirement(name = OpenApiConfig.ADMIN_KEY_SCHEME)
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /** All tenants and their plan/status — the admin "dashboard" list. */
    @GetMapping("/tenants")
    public ResponseEntity<PageResponse<AdminTenantSummaryResponse>> listTenants(
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(adminService.listTenants(pageable));
    }

    /** One tenant, plus a snapshot of its current-period usage. */
    @GetMapping("/tenants/{tenantId}")
    public ResponseEntity<AdminTenantDetailResponse> getTenant(@PathVariable Long tenantId) {
        return ResponseEntity.ok(adminService.getTenantDetail(tenantId));
    }

    /** Upgrade/downgrade a tenant's plan. */
    @PatchMapping("/tenants/{tenantId}/plan")
    public ResponseEntity<AdminTenantSummaryResponse> updatePlan(@PathVariable Long tenantId,
                                                                   @Valid @RequestBody AdminUpdatePlanRequest request) {
        return ResponseEntity.ok(adminService.updatePlan(tenantId, request.plan()));
    }

    /** Suspend or reinstate a tenant (active=false blocks their API key at
     *  the next authentication attempt — see TenantService.authenticate's
     *  findByApiKeyHashAndActiveTrue). */
    @PatchMapping("/tenants/{tenantId}/status")
    public ResponseEntity<AdminTenantSummaryResponse> updateStatus(@PathVariable Long tenantId,
                                                                     @Valid @RequestBody AdminUpdateStatusRequest request) {
        return ResponseEntity.ok(adminService.updateStatus(tenantId, request.active()));
    }

    /** Sets or clears (customDomain: null) a tenant's branded short-link
     *  domain, e.g. go.company.com. Application-level only: this records
     *  the association, it does not provision DNS or TLS — see Tenant's
     *  customDomain Javadoc and README "Custom domains" for that boundary. */
    @PatchMapping("/tenants/{tenantId}/domain")
    public ResponseEntity<AdminTenantSummaryResponse> updateCustomDomain(@PathVariable Long tenantId,
                                                                           @Valid @RequestBody AdminUpdateDomainRequest request) {
        return ResponseEntity.ok(adminService.updateCustomDomain(tenantId, request.customDomain()));
    }

    /** Rotates a tenant's API key when the original is lost — the old key
     *  stops working immediately (no overlap window). The new raw key is
     *  returned exactly once, mirroring registration (TenantService.register);
     *  it is never retrievable again after this response. */
    @PostMapping("/tenants/{tenantId}/rotate-key")
    public ResponseEntity<AdminApiKeyRotationResponse> rotateApiKey(@PathVariable Long tenantId) {
        return ResponseEntity.ok(adminService.rotateApiKey(tenantId));
    }

    /** Every link a given tenant has ever created, active or not. */
    @GetMapping("/tenants/{tenantId}/urls")
    public ResponseEntity<PageResponse<AdminUrlSummaryResponse>> listTenantUrls(
            @PathVariable Long tenantId,
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(adminService.listTenantUrls(tenantId, pageable));
    }

    /** Platform-wide usage for the current billing period, broken down by tenant. */
    @GetMapping("/usage")
    public ResponseEntity<AdminUsageSummaryResponse> usageSummary() {
        return ResponseEntity.ok(adminService.getUsageSummary());
    }
}
