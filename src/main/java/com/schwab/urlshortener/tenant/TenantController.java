package com.schwab.urlshortener.tenant;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant onboarding. Registration is intentionally public/unauthenticated
 * (self-service signup, like most SaaS API products) — everything else in
 * the system requires the API key this endpoint hands back.
 */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping
    public ResponseEntity<TenantRegistrationResponse> register(@Valid @RequestBody TenantRegistrationRequest request) {
        TenantRegistrationResponse response = tenantService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** "Who am I" — handy for confirming an API key works and checking your plan/limits. */
    @GetMapping("/me")
    public ResponseEntity<TenantProfileResponse> me(@AuthenticationPrincipal TenantPrincipal principal) {
        return ResponseEntity.ok(new TenantProfileResponse(principal.tenantId(), principal.name(), principal.plan()));
    }
}
