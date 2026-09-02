package com.schwab.urlshortener.tenant;

import com.schwab.urlshortener.exception.TenantNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
public class TenantService {

    private final TenantRepository repository;

    public TenantService(TenantRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public TenantRegistrationResponse register(TenantRegistrationRequest request) {
        String rawApiKey = ApiKeyGenerator.generate();
        Tenant tenant = Tenant.builder()
                .name(request.name())
                .apiKeyHash(ApiKeyGenerator.hash(rawApiKey))
                .plan(request.plan() != null ? request.plan() : RateLimitPlan.STANDARD)
                .active(true)
                .build();

        Tenant saved = repository.save(tenant);
        // Deliberately never log the raw key — only the fact that a tenant was created.
        log.info("Registered new tenant: id={} name={} plan={}", saved.getId(), saved.getName(), saved.getPlan());

        return new TenantRegistrationResponse(saved.getId(), saved.getName(), saved.getPlan(), rawApiKey, saved.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public TenantPrincipal authenticate(String rawApiKey) {
        String hash = ApiKeyGenerator.hash(rawApiKey);
        return repository.findByApiKeyHashAndActiveTrue(hash)
                .map(t -> new TenantPrincipal(t.getId(), t.getName(), t.getPlan()))
                .orElse(null);
    }

    /**
     * Fetches the full tenant record for an already-authenticated caller.
     * "Not found" here is an invariant violation (the caller was just
     * authenticated as this tenant), not user input to validate — see
     * the call site in InvoiceController for why that maps to a 500, not
     * a 404. Exists so other components (e.g. InvoiceController, which
     * needs the tenant's display name for a PDF) go through TenantService
     * rather than reaching into TenantRepository directly — the tenant
     * aggregate's persistence stays encapsulated behind this service.
     */
    @Transactional(readOnly = true)
    public Tenant getTenantOrThrow(Long tenantId) {
        return repository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Authenticated tenant " + tenantId + " has no backing record"));
    }

    /**
     * Fetches a tenant by an arbitrary id supplied as caller input (e.g. an
     * admin looking up a specific tenant) — as opposed to getTenantOrThrow,
     * where "not found" is an invariant violation, here it's ordinary
     * client error territory, so it maps to a proper 404
     * (TenantNotFoundException) rather than a 500.
     */
    @Transactional(readOnly = true)
    public Tenant getTenantById(Long tenantId) {
        return repository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
    }

    @Transactional(readOnly = true)
    public List<Tenant> listAll() {
        return repository.findAll();
    }

    @Transactional
    public Tenant updatePlan(Long tenantId, RateLimitPlan newPlan) {
        Tenant tenant = getTenantById(tenantId);
        RateLimitPlan previous = tenant.getPlan();
        tenant.setPlan(newPlan);
        Tenant saved = repository.save(tenant);
        log.info("Admin changed tenant {} plan: {} -> {}", tenantId, previous, newPlan);
        return saved;
    }

    @Transactional
    public Tenant updateActiveStatus(Long tenantId, boolean active) {
        Tenant tenant = getTenantById(tenantId);
        tenant.setActive(active);
        Tenant saved = repository.save(tenant);
        log.info("Admin set tenant {} active={}", tenantId, active);
        return saved;
    }
}
