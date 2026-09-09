package com.schwab.urlshortener.service.impl;

import com.schwab.urlshortener.billing.UsageMeteringService;
import com.schwab.urlshortener.dto.PageResponse;
import com.schwab.urlshortener.dto.ShortenUrlRequest;
import com.schwab.urlshortener.dto.ShortenUrlResponse;
import com.schwab.urlshortener.dto.UpdateUrlRequest;
import com.schwab.urlshortener.dto.UrlStatsResponse;
import com.schwab.urlshortener.entity.UrlMapping;
import com.schwab.urlshortener.exception.DuplicateAliasException;
import com.schwab.urlshortener.exception.InvalidUrlException;
import com.schwab.urlshortener.exception.RateLimitExceededException;
import com.schwab.urlshortener.exception.ShortCodeGenerationException;
import com.schwab.urlshortener.exception.UrlExpiredException;
import com.schwab.urlshortener.exception.UrlNotFoundException;
import com.schwab.urlshortener.ratelimit.RateLimitResult;
import com.schwab.urlshortener.ratelimit.TenantRateLimiterService;
import com.schwab.urlshortener.repository.UrlMappingRepository;
import com.schwab.urlshortener.service.ShortCodeGenerator;
import com.schwab.urlshortener.service.UrlSafetyChecker;
import com.schwab.urlshortener.service.UrlShortenerService;
import com.schwab.urlshortener.tenant.RateLimitPlan;
import com.schwab.urlshortener.tenant.TenantService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Business rules for creating, resolving, and managing short links.
 * Deliberately delegates the mechanics it doesn't need to own:
 *  - candidate short-code generation -> {@link ShortCodeGenerator}
 *  - "is this URL safe to shorten" -> {@link UrlSafetyChecker}
 *  - entity/DTO shaping -> {@link UrlMappingMapper}
 * so this class's own job stays "collision-retry policy, tenant scoping,
 * rate limiting, and metering orchestration" — not also random-number
 * generation or response-shape formatting.
 */
@Service
@Slf4j
public class UrlShortenerServiceImpl implements UrlShortenerService {

    private static final int MAX_GENERATION_ATTEMPTS = 5;

    private final UrlMappingRepository repository;
    private final TenantService tenantService;
    private final CachedShortCodeLookup cachedShortCodeLookup;
    private final UrlSafetyChecker urlSafetyChecker;
    private final TenantRateLimiterService rateLimiterService;
    private final UsageMeteringService usageMeteringService;
    private final ShortCodeGenerator shortCodeGenerator;
    private final UrlMappingMapper mapper;
    private final Counter urlsCreatedCounter;
    private final Counter redirectsServedCounter;
    private final Timer redirectResolveTimer;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    public UrlShortenerServiceImpl(UrlMappingRepository repository,
                                    TenantService tenantService,
                                    CachedShortCodeLookup cachedShortCodeLookup,
                                    UrlSafetyChecker urlSafetyChecker,
                                    TenantRateLimiterService rateLimiterService,
                                    UsageMeteringService usageMeteringService,
                                    ShortCodeGenerator shortCodeGenerator,
                                    UrlMappingMapper mapper,
                                    MeterRegistry meterRegistry) {
        this.repository = repository;
        this.tenantService = tenantService;
        this.cachedShortCodeLookup = cachedShortCodeLookup;
        this.urlSafetyChecker = urlSafetyChecker;
        this.rateLimiterService = rateLimiterService;
        this.usageMeteringService = usageMeteringService;
        this.shortCodeGenerator = shortCodeGenerator;
        this.mapper = mapper;
        this.urlsCreatedCounter = Counter.builder("urls.created")
                .description("Short URLs successfully created")
                .register(meterRegistry);
        this.redirectsServedCounter = Counter.builder("redirects.served")
                .description("Redirects successfully served")
                .register(meterRegistry);
        this.redirectResolveTimer = Timer.builder("redirect.resolve.duration")
                .description("Latency of resolving a short code to its redirect target — the hottest path in this service")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @Override
    @Transactional
    public ShortenUrlResponse createShortUrl(ShortenUrlRequest request, Long tenantId) {
        if (!urlSafetyChecker.isSafe(request.originalUrl())) {
            throw new InvalidUrlException("originalUrl was flagged as unsafe and cannot be shortened");
        }

        boolean isCustom = request.customAlias() != null && !request.customAlias().isBlank();
        UrlMapping saved = isCustom
                ? createWithCustomAlias(request, tenantId)
                : createWithGeneratedCode(request, tenantId);

        urlsCreatedCounter.increment();
        usageMeteringService.recordApiCall(tenantId);
        log.info("Created short URL: code={} tenantId={} customAlias={} expiresAt={}",
                saved.getShortCode(), tenantId, isCustom, request.expiresAt());

        return mapper.toShortenResponse(saved, resolveBaseUrl(tenantId));
    }

    /**
     * The tenant's branded domain (admin-assigned, see Tenant.customDomain)
     * if one is set, otherwise the platform's default host. This is
     * cosmetic only at the application level — actually reaching
     * {@code https://{customDomain}/{shortCode}} still requires that
     * domain's DNS to be pointed at this deployment and a TLS certificate
     * issued for it, neither of which this method (or this codebase) can
     * provide; see Tenant's Javadoc for the full boundary.
     *
     * One live DB read per creation, not cached: creation is not the hot
     * path (unlike the redirect resolution CachedShortCodeLookup exists
     * for), and a custom-domain change should be reflected on the very
     * next link a tenant creates, not after a cache TTL elapses.
     */
    private String resolveBaseUrl(Long tenantId) {
        String customDomain = tenantService.getTenantOrThrow(tenantId).getCustomDomain();
        return (customDomain != null && !customDomain.isBlank()) ? "https://" + customDomain : baseUrl;
    }

    /**
     * ACID note: the existsByShortCode pre-check below is a fast-fail UX
     * improvement, not the actual uniqueness guarantee — that's the
     * database's unique constraint on shortCode (see UrlMapping). Two
     * requests for the same alias can still race past the pre-check under
     * real concurrency; the try/catch here is what makes that race resolve
     * correctly (a clean 409) instead of an opaque 500 from an
     * unhandled DataIntegrityViolationException.
     */
    private UrlMapping createWithCustomAlias(ShortenUrlRequest request, Long tenantId) {
        String code = request.customAlias();
        if (repository.existsByShortCode(code)) {
            throw new DuplicateAliasException(code);
        }
        try {
            return repository.save(buildMapping(request, tenantId, code, true));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateAliasException(code);
        }
    }

    private UrlMapping createWithGeneratedCode(ShortenUrlRequest request, Long tenantId) {
        String code = generateUniqueCode();
        try {
            return repository.save(buildMapping(request, tenantId, code, false));
        } catch (DataIntegrityViolationException e) {
            // generateUniqueCode()'s own pre-check loop makes this vanishingly
            // rare — it means a concurrent request landed on the exact same
            // randomly-generated 7-character code in the same instant. Not
            // retried in-place: after a failed flush, Hibernate's persistence
            // context for this transaction should be treated as no longer
            // safe to reuse (the same reasoning behind UsageRecordCreator
            // being its own bean for its own isolated retry transaction —
            // see that class's Javadoc). Failing this attempt cleanly and
            // letting the client's normal retry land on a fresh candidate is
            // simpler and safer than a retry mechanism that would need its
            // own isolated transaction to be correct.
            log.error("Short code collision survived the pre-check for candidate {} — concurrent request won the race", code, e);
            throw new ShortCodeGenerationException("A short code collision occurred; please retry.");
        }
    }

    private UrlMapping buildMapping(ShortenUrlRequest request, Long tenantId, String code, boolean isCustom) {
        return UrlMapping.builder()
                .tenantId(tenantId)
                .shortCode(code)
                .originalUrl(request.originalUrl())
                .expiresAt(request.expiresAt())
                .customAlias(isCustom)
                .clickCount(0L)
                .active(true)
                .build();
    }

    @Override
    @Transactional
    public String resolveAndRecordHit(String shortCode) {
        Timer.Sample sample = Timer.start();
        try {
            return doResolveAndRecordHit(shortCode);
        } finally {
            sample.stop(redirectResolveTimer);
        }
    }

    private String doResolveAndRecordHit(String shortCode) {
        CachedShortCodeLookup.RedirectTarget target = cachedShortCodeLookup.findActive(shortCode);
        if (target == null) {
            throw new UrlNotFoundException(shortCode);
        }

        if (target.isExpired()) {
            log.info("Access attempt on expired short code: {}", shortCode);
            throw new UrlExpiredException(shortCode);
        }

        // Fair-share protection on the hot path: this redirect's cost is charged
        // against the LINK OWNER's bucket (not the anonymous caller's — there is
        // no caller identity here), so one tenant's traffic spike on a viral link
        // cannot starve redirect capacity for other tenants' links.
        RateLimitPlan ownerPlan = tenantService.getPlanForRateLimiting(target.tenantId());
        RateLimitResult result =
                rateLimiterService.tryConsumeRedirectPermit(target.tenantId(), ownerPlan);
        if (!result.allowed()) {
            throw new RateLimitExceededException(
                    "This link's owner has exceeded its redirect rate limit. Please try again shortly.",
                    result.retryAfterSeconds());
        }

        repository.incrementClickCount(shortCode, Instant.now());
        redirectsServedCounter.increment();
        usageMeteringService.recordRedirect(target.tenantId());
        return target.originalUrl();
    }

    @Override
    @Transactional(readOnly = true)
    public UrlStatsResponse getStats(String shortCode, Long tenantId) {
        UrlMapping mapping = repository.findByShortCodeAndActiveTrueAndTenantId(shortCode, tenantId)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        return mapper.toStatsResponse(mapping);
    }

    @Override
    @Transactional
    public void deactivate(String shortCode, Long tenantId) {
        UrlMapping mapping = repository.findByShortCodeAndActiveTrueAndTenantId(shortCode, tenantId)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        mapping.setActive(false);
        repository.save(mapping);
        cachedShortCodeLookup.evict(shortCode);
        log.info("Deactivated short code: {} (tenantId={})", shortCode, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UrlStatsResponse> listMyUrls(Long tenantId, Pageable pageable) {
        // Same createdAt-desc convention as AdminService.listTenantUrls — the
        // caller supplies page/size, sort order is this service's call, not
        // something every caller needs to know to ask for.
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<UrlStatsResponse> page = repository.findByTenantId(tenantId, sorted).map(mapper::toStatsResponse);
        return PageResponse.from(page);
    }

    @Override
    @Transactional
    public UrlStatsResponse updateUrl(String shortCode, Long tenantId, UpdateUrlRequest request) {
        if (request.originalUrl() == null && request.expiresAt() == null) {
            throw new InvalidUrlException("At least one of originalUrl or expiresAt must be provided");
        }

        UrlMapping mapping = repository.findByShortCodeAndTenantId(shortCode, tenantId)
                .orElseThrow(() -> UrlNotFoundException.forTenant(shortCode));

        if (request.originalUrl() != null) {
            if (!urlSafetyChecker.isSafe(request.originalUrl())) {
                throw new InvalidUrlException("originalUrl was flagged as unsafe and cannot be used");
            }
            mapping.setOriginalUrl(request.originalUrl());
        }
        if (request.expiresAt() != null) {
            mapping.setExpiresAt(request.expiresAt());
        }

        UrlMapping saved = repository.save(mapping);
        // Must evict regardless of whether this link is currently active: a
        // stale cached destination/expiry must not outlive the update just
        // because the redirect path only re-populates the cache on a miss.
        cachedShortCodeLookup.evict(shortCode);
        log.info("Updated short URL: code={} tenantId={} originalUrlChanged={} expiresAtChanged={}",
                shortCode, tenantId, request.originalUrl() != null, request.expiresAt() != null);
        return mapper.toStatsResponse(saved);
    }

    @Override
    @Transactional
    public UrlStatsResponse reactivate(String shortCode, Long tenantId) {
        UrlMapping mapping = repository.findByShortCodeAndTenantId(shortCode, tenantId)
                .orElseThrow(() -> UrlNotFoundException.forTenant(shortCode));
        mapping.setActive(true);
        UrlMapping saved = repository.save(mapping);
        // No cache eviction needed here, unlike deactivate/updateUrl: a miss
        // (inactive/nonexistent) is never cached in the first place (see
        // CachedShortCodeLookup's `unless` clause) — there is nothing stale
        // to evict for a code that was inactive a moment ago.
        log.info("Reactivated short code: {} (tenantId={})", shortCode, tenantId);
        return mapper.toStatsResponse(saved);
    }

    /**
     * Collision-retry POLICY (how many times to ask, what to do when
     * exhausted) — stays here, distinct from candidate generation
     * MECHANISM, which is ShortCodeGenerator's job.
     */
    private String generateUniqueCode() {
        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = shortCodeGenerator.generateCandidate();
            if (!repository.existsByShortCode(candidate)) {
                return candidate;
            }
            log.warn("Short code collision on attempt {} for candidate {}", attempt, candidate);
        }
        throw new ShortCodeGenerationException(
                "Failed to generate a unique short code after " + MAX_GENERATION_ATTEMPTS + " attempts");
    }
}
