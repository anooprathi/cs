package com.schwab.urlshortener.tenant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            t.setId(1L);
            t.setCreatedAt(Instant.now());
            return t;
        });

        TenantRegistrationResponse response = tenantService.register(
                new TenantRegistrationRequest("acme", RateLimitPlan.PREMIUM));

        assertThat(response.apiKey()).startsWith("usk_");
        assertThat(response.plan()).isEqualTo(RateLimitPlan.PREMIUM);

        ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        verify(repository).save(captor.capture());
        Tenant persisted = captor.getValue();

        // The raw key is never what gets persisted — only its hash.
        assertThat(persisted.getApiKeyHash()).isNotEqualTo(response.apiKey());
        assertThat(persisted.getApiKeyHash()).isEqualTo(ApiKeyGenerator.hash(response.apiKey()));
    }

    @Test
    void register_withNoPlanSpecified_defaultsToStandard() {
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            t.setId(1L);
            t.setCreatedAt(Instant.now());
            return t;
        });

        TenantRegistrationResponse response = tenantService.register(
                new TenantRegistrationRequest("acme", null));

        assertThat(response.plan()).isEqualTo(RateLimitPlan.STANDARD);
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
