package com.schwab.urlshortener.tenant;

import com.schwab.urlshortener.config.CacheConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves {@code @Cacheable}/{@code @CacheEvict} on
 * {@link TenantService#getPlanForRateLimiting} and
 * {@link TenantService#updatePlan} actually take effect through a real
 * caching proxy and {@link CacheConfig}'s wiring — see the equivalent
 * caveat on CachedShortCodeLookupCachingTest for why a plain
 * {@code new TenantService(mockRepo)} unit test can't observe this.
 */
class TenantPlanCachingTest {

    private AnnotationConfigApplicationContext context;
    private TenantRepository repository;
    private TenantService tenantService;

    @BeforeEach
    void setUp() {
        repository = mock(TenantRepository.class);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(TenantRepository.class, () -> repository);
        context.register(CacheConfig.class, TenantService.class);
        context.refresh();
        tenantService = context.getBean(TenantService.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void repeatedLookup_hitsRepositoryOnlyOnce() {
        Tenant tenant = Tenant.builder().id(7L).plan(RateLimitPlan.PREMIUM).build();
        when(repository.findById(7L)).thenReturn(Optional.of(tenant));

        RateLimitPlan first = tenantService.getPlanForRateLimiting(7L);
        RateLimitPlan second = tenantService.getPlanForRateLimiting(7L);

        assertThat(first).isEqualTo(RateLimitPlan.PREMIUM);
        assertThat(second).isEqualTo(RateLimitPlan.PREMIUM);
        verify(repository, times(1)).findById(7L);
    }

    @Test
    void updatePlan_evictsCache_nextLookupSeesTheNewPlan() {
        Tenant tenant = Tenant.builder().id(7L).plan(RateLimitPlan.STANDARD).build();
        when(repository.findById(7L)).thenReturn(Optional.of(tenant));
        when(repository.save(tenant)).thenReturn(tenant);

        assertThat(tenantService.getPlanForRateLimiting(7L)).isEqualTo(RateLimitPlan.STANDARD);

        tenant.setPlan(RateLimitPlan.PREMIUM);
        tenantService.updatePlan(7L, RateLimitPlan.PREMIUM);

        assertThat(tenantService.getPlanForRateLimiting(7L)).isEqualTo(RateLimitPlan.PREMIUM);
        // First getPlanForRateLimiting call, plus updatePlan's own getTenantById,
        // plus the post-eviction getPlanForRateLimiting call actually hitting the DB again.
        verify(repository, times(3)).findById(7L);
    }
}
