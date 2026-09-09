package com.schwab.urlshortener.tenant;

import com.schwab.urlshortener.config.CacheConfig;
import com.schwab.urlshortener.exception.DuplicateTenantNameException;
import com.schwab.urlshortener.exception.TenantNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@Slf4j
public class TenantService {

    private final TenantRepository repository;

    public TenantService(TenantRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public TenantRegistrationResponse register(TenantRegistrationRequest request) {
        String normalizedName = normalize(request.name());

        // Fast-fail pre-check (fine UX, not the actual guarantee) — same
        // TOCTOU-aware pattern as UrlShortenerServiceImpl's short-code
        // creation: the real guarantee is the DB's unique constraint on
        // normalizedName, backstopped by the catch below for a race that
        // slips past this check under real concurrency.
        if (repository.existsByNormalizedName(normalizedName)) {
            throw new DuplicateTenantNameException(request.name());
        }

        String rawApiKey = ApiKeyGenerator.generate();
        Tenant tenant = Tenant.builder()
                .name(request.name())
                .normalizedName(normalizedName)
                .apiKeyHash(ApiKeyGenerator.hash(rawApiKey))
                // Every self-registered tenant starts on STANDARD, unconditionally — there
                // is no caller-supplied plan anymore (see TenantRegistrationRequest's
                // Javadoc for why accepting one was a real vulnerability, not a shortcut).
                // A plan change is exclusively an authenticated admin action from here on
                // (updatePlan below), never something declared at registration time.
                .plan(RateLimitPlan.STANDARD)
                .active(true)
                .build();

        Tenant saved;
        try {
            saved = repository.save(tenant);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateTenantNameException(request.name());
        }
        // Deliberately never log the raw key — only the fact that a tenant was created.
        log.info("Registered new tenant: id={} name={} plan={}", saved.getId(), saved.getName(), saved.getPlan());

        return new TenantRegistrationResponse(saved.getId(), saved.getName(), saved.getPlan(), rawApiKey, saved.getCreatedAt());
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
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

    /** Paginated variant for the admin listing endpoint — see AdminController.
     *  listAll() (unpaginated) stays for internal callers that genuinely
     *  need every tenant (e.g. AdminService.getUsageSummary's fallback
     *  path), not removed just because a paginated sibling now exists. */
    @Transactional(readOnly = true)
    public Page<Tenant> listAll(Pageable pageable) {
        return repository.findAll(pageable);
    }

    /**
     * Fetches exactly the tenants named by the given ids — used by
     * AdminService.getUsageSummary to resolve display names for only the
     * tenants that actually have usage this period, instead of loading
     * every tenant ever registered just to build a lookup map (see that
     * method's own comment for why this replaced listAll() there).
     */
    @Transactional(readOnly = true)
    public List<Tenant> findByIds(List<Long> ids) {
        return repository.findAllById(ids);
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.TENANT_PLANS, key = "#tenantId")
    public Tenant updatePlan(Long tenantId, RateLimitPlan newPlan) {
        Tenant tenant = getTenantById(tenantId);
        RateLimitPlan previous = tenant.getPlan();
        tenant.setPlan(newPlan);
        Tenant saved = repository.save(tenant);
        log.info("Admin changed tenant {} plan: {} -> {}", tenantId, previous, newPlan);
        return saved;
    }

    /**
     * Cached lookup for the redirect hot path (see
     * UrlShortenerServiceImpl.resolveAndRecordHit), which needs only the
     * link OWNER's plan to pick their rate-limit tier — not the full
     * Tenant record. Falls back to STANDARD for a tenant that's vanished
     * (deleted between the link being created and this redirect), same as
     * the un-cached lookup this replaced: fair-share protection degrading
     * to the more conservative tier is safe, unlike defaulting open.
     *
     * Cache is bounded to 10 minutes (see CacheConfig) and explicitly
     * evicted on {@link #updatePlan} — an admin plan change should not
     * take up to 10 minutes to actually apply to redirect rate limiting.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.TENANT_PLANS, key = "#tenantId")
    public RateLimitPlan getPlanForRateLimiting(Long tenantId) {
        return repository.findById(tenantId)
                .map(Tenant::getPlan)
                .orElse(RateLimitPlan.STANDARD);
    }

    @Transactional
    public Tenant updateActiveStatus(Long tenantId, boolean active) {
        Tenant tenant = getTenantById(tenantId);
        tenant.setActive(active);
        Tenant saved = repository.save(tenant);
        log.info("Admin set tenant {} active={}", tenantId, active);
        return saved;
    }

    /**
     * Admin-triggered recovery for a lost API key: generates a fresh raw
     * key, persists only its hash (same as {@link #register}), and
     * immediately invalidates the old one — there is no window where both
     * work. The tenant's identity, plan, links, and billing history are
     * untouched; only the credential changes.
     */
    @Transactional
    public TenantApiKeyRotationResult rotateApiKey(Long tenantId) {
        Tenant tenant = getTenantById(tenantId);
        String rawApiKey = ApiKeyGenerator.generate();
        tenant.setApiKeyHash(ApiKeyGenerator.hash(rawApiKey));
        Tenant saved = repository.save(tenant);
        // Deliberately never log the raw key — only the fact that a rotation happened.
        log.info("Admin rotated API key for tenant {}", tenantId);
        return new TenantApiKeyRotationResult(saved, rawApiKey);
    }
}
