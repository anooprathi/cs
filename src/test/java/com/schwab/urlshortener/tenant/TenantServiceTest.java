package com.schwab.urlshortener.tenant;

import com.schwab.urlshortener.exception.DuplicateTenantNameException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock
    private TenantRepository repository;

    private TenantService tenantService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        tenantService = new TenantService(repository);
    }

    @Test
    void register_persistsHashedKey_andReturnsRawKeyExactlyOnce() {
        when(repository.existsByNormalizedName("acme")).thenReturn(false);
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            t.setId(1L);
            t.setCreatedAt(Instant.now());
            return t;
        });

        TenantRegistrationResponse response = tenantService.register(new TenantRegistrationRequest("acme"));

        assertThat(response.apiKey()).startsWith("usk_");

        ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        verify(repository).save(captor.capture());
        Tenant persisted = captor.getValue();

        // The raw key is never what gets persisted — only its hash.
        assertThat(persisted.getApiKeyHash()).isNotEqualTo(response.apiKey());
        assertThat(persisted.getApiKeyHash()).isEqualTo(ApiKeyGenerator.hash(response.apiKey()));
    }

    @Test
    void register_alwaysAssignsStandard_thereIsNoWayToRequestOtherwise() {
        // TenantRegistrationRequest has no plan field at all anymore — this
        // is the actual fix for a real vulnerability a production review
        // found: public self-registration used to accept a caller-chosen
        // plan, meaning anyone could register as PREMIUM for free with zero
        // authorization. Every registration now goes through the exact same
        // path, unconditionally, to STANDARD.
        when(repository.existsByNormalizedName("acme")).thenReturn(false);
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            t.setId(1L);
            t.setCreatedAt(Instant.now());
            return t;
        });

        TenantRegistrationResponse response = tenantService.register(new TenantRegistrationRequest("acme"));

        assertThat(response.plan()).isEqualTo(RateLimitPlan.STANDARD);
    }

    @Test
    void register_normalizesNameForUniquenessCheck_caseAndWhitespaceInsensitive() {
        // "Acme" and "  ACME  " should collide, not silently create two
        // separate tenants for what a human would recognize as the same
        // organization name.
        when(repository.existsByNormalizedName("acme")).thenReturn(true);

        assertThatThrownBy(() -> tenantService.register(new TenantRegistrationRequest("  ACME  ")))
                .isInstanceOf(DuplicateTenantNameException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void register_racesPastPreCheck_stillThrowsConflict_notA500() {
        // Same TOCTOU-safe pattern as UrlShortenerServiceImpl's short-code
        // creation: the pre-check can be raced by a concurrent request, and
        // the DB's real unique constraint on normalizedName is the actual
        // backstop — its violation must map to the same clean 409, not an
        // unhandled 500.
        when(repository.existsByNormalizedName("acme")).thenReturn(false);
        when(repository.save(any(Tenant.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("unique constraint violation"));

        assertThatThrownBy(() -> tenantService.register(new TenantRegistrationRequest("acme")))
                .isInstanceOf(DuplicateTenantNameException.class);
    }

    @Test
    void authenticate_validKey_resolvesToPrincipal() {
        String rawKey = "usk_test-key";
        Tenant tenant = Tenant.builder()
                .id(42L).name("acme").plan(RateLimitPlan.STANDARD)
                .apiKeyHash(ApiKeyGenerator.hash(rawKey)).active(true).build();
        when(repository.findByApiKeyHashAndActiveTrue(ApiKeyGenerator.hash(rawKey)))
                .thenReturn(Optional.of(tenant));

        TenantPrincipal principal = tenantService.authenticate(rawKey);

        assertThat(principal).isNotNull();
        assertThat(principal.tenantId()).isEqualTo(42L);
        assertThat(principal.plan()).isEqualTo(RateLimitPlan.STANDARD);
    }

    @Test
    void authenticate_unknownKey_returnsNull() {
        when(repository.findByApiKeyHashAndActiveTrue(any())).thenReturn(Optional.empty());

        TenantPrincipal principal = tenantService.authenticate("usk_does-not-exist");

        assertThat(principal).isNull();
    }

    // ---------- getPlanForRateLimiting ----------
    // Note: this test exercises the plain delegate logic only. The @Cacheable
    // behavior itself (does a second call skip the repository) is Spring AOP
    // proxy behavior that a bare `new TenantService(repository)` unit test
    // can't observe — see CachedShortCodeLookupCachingTest for a test that
    // actually goes through a real caching proxy and CacheConfig's wiring.

    @Test
    void getPlanForRateLimiting_knownTenant_returnsItsPlan() {
        Tenant tenant = Tenant.builder().id(7L).plan(RateLimitPlan.PREMIUM).build();
        when(repository.findById(7L)).thenReturn(Optional.of(tenant));

        assertThat(tenantService.getPlanForRateLimiting(7L)).isEqualTo(RateLimitPlan.PREMIUM);
    }

    @Test
    void getPlanForRateLimiting_unknownTenant_fallsBackToStandard() {
        // Same fallback the un-cached lookup this replaced had: a link whose
        // owning tenant has vanished between link creation and this redirect
        // degrades to the more conservative tier rather than failing closed.
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThat(tenantService.getPlanForRateLimiting(404L)).isEqualTo(RateLimitPlan.STANDARD);
    }

    // ---------- rotateApiKey ----------

    @Test
    void rotateApiKey_persistsNewHash_andReturnsRawKeyExactlyOnce() {
        Tenant tenant = Tenant.builder()
                .id(1L).name("acme").plan(RateLimitPlan.STANDARD)
                .apiKeyHash(ApiKeyGenerator.hash("usk_old-key")).active(true).build();
        when(repository.findById(1L)).thenReturn(Optional.of(tenant));
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantApiKeyRotationResult result = tenantService.rotateApiKey(1L);

        assertThat(result.rawApiKey()).startsWith("usk_");
        assertThat(result.rawApiKey()).isNotEqualTo("usk_old-key");

        ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        verify(repository).save(captor.capture());
        Tenant persisted = captor.getValue();

        // The raw key is never what gets persisted — only its hash — and the
        // old hash is fully replaced, not appended alongside a still-valid one.
        assertThat(persisted.getApiKeyHash()).isEqualTo(ApiKeyGenerator.hash(result.rawApiKey()));
        assertThat(persisted.getApiKeyHash()).isNotEqualTo(ApiKeyGenerator.hash("usk_old-key"));
    }

    @Test
    void rotateApiKey_leavesIdentityPlanAndStatusUntouched() {
        Tenant tenant = Tenant.builder()
                .id(1L).name("acme").plan(RateLimitPlan.PREMIUM)
                .apiKeyHash(ApiKeyGenerator.hash("usk_old-key")).active(true).build();
        when(repository.findById(1L)).thenReturn(Optional.of(tenant));
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantApiKeyRotationResult result = tenantService.rotateApiKey(1L);

        assertThat(result.tenant().getId()).isEqualTo(1L);
        assertThat(result.tenant().getName()).isEqualTo("acme");
        assertThat(result.tenant().getPlan()).isEqualTo(RateLimitPlan.PREMIUM);
        assertThat(result.tenant().isActive()).isTrue();
    }

    @Test
    void rotateApiKey_unknownTenant_throwsNotFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.rotateApiKey(999L))
                .isInstanceOf(com.schwab.urlshortener.exception.TenantNotFoundException.class);

        verify(repository, never()).save(any());
    }
}
