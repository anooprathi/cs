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
}
