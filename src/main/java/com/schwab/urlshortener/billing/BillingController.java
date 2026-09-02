package com.schwab.urlshortener.billing;

import com.schwab.urlshortener.tenant.TenantPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service usage/billing visibility for the authenticated tenant.
 * Deliberately not metered itself (see UsageMeteringService) — checking
 * your bill shouldn't add to it.
 */
@RestController
@RequestMapping("/api/v1/tenants/me/billing")
public class BillingController {

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping
    public ResponseEntity<BillingStatementResponse> currentStatement(@AuthenticationPrincipal TenantPrincipal tenant) {
        return ResponseEntity.ok(billingService.getCurrentStatement(tenant.tenantId(), tenant.plan()));
    }
}
