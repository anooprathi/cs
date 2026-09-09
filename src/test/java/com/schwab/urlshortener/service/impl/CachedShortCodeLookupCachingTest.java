package com.schwab.urlshortener.service.impl;

import com.schwab.urlshortener.config.CacheConfig;
import com.schwab.urlshortener.entity.UrlMapping;
import com.schwab.urlshortener.repository.UrlMappingRepository;
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
 * Proves {@code @Cacheable}/{@code @CacheEvict} on {@link CachedShortCodeLookup}
 * actually take effect through a real caching proxy and {@link CacheConfig}'s
 * CacheManager wiring. A plain {@code new CachedShortCodeLookup(mockRepo)}
 * unit test cannot observe this at all — Spring's caching annotations only
 * do anything via an AOP proxy the application context creates, which is
 * exactly why this class exists as its own bean rather than a private
 * method on UrlShortenerServiceImpl (see its own Javadoc). If CacheConfig's
 * bean definition or cache names were ever wrong, every test written
 * against a mocked CachedShortCodeLookup would still pass while caching
 * silently did nothing in the real app — this test is what would catch that.
 */
class CachedShortCodeLookupCachingTest {

    private AnnotationConfigApplicationContext context;
    private UrlMappingRepository repository;
    private CachedShortCodeLookup lookup;

    @BeforeEach
    void setUp() {
        repository = mock(UrlMappingRepository.class);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(UrlMappingRepository.class, () -> repository);
        context.register(CacheConfig.class, CachedShortCodeLookup.class);
        context.refresh();
        lookup = context.getBean(CachedShortCodeLookup.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void repeatedLookup_hitsRepositoryOnlyOnce() {
        UrlMapping mapping = UrlMapping.builder()
                .tenantId(1L).shortCode("abc1234").originalUrl("https://example.com").active(true).build();
        when(repository.findByShortCodeAndActiveTrue("abc1234")).thenReturn(Optional.of(mapping));

        CachedShortCodeLookup.RedirectTarget first = lookup.findActive("abc1234");
        CachedShortCodeLookup.RedirectTarget second = lookup.findActive("abc1234");

        assertThat(first).isEqualTo(second);
        assertThat(first.originalUrl()).isEqualTo("https://example.com");
        verify(repository, times(1)).findByShortCodeAndActiveTrue("abc1234");
    }

    @Test
    void evict_forcesNextLookupBackToRepository() {
        UrlMapping mapping = UrlMapping.builder()
                .tenantId(1L).shortCode("abc1234").originalUrl("https://example.com").active(true).build();
        when(repository.findByShortCodeAndActiveTrue("abc1234")).thenReturn(Optional.of(mapping));

        lookup.findActive("abc1234");
        lookup.evict("abc1234");
        lookup.findActive("abc1234");

        verify(repository, times(2)).findByShortCodeAndActiveTrue("abc1234");
    }

    @Test
    void miss_isNotCached_eachCallHitsRepositoryAgain() {
        // Deliberate: caching a "not found" would let a scanner cheaply
        // pollute the cache, and a link created moments after a miss was
        // cached would stay invisible until the entry's TTL passed.
        when(repository.findByShortCodeAndActiveTrue("missing")).thenReturn(Optional.empty());

        lookup.findActive("missing");
        lookup.findActive("missing");

        verify(repository, times(2)).findByShortCodeAndActiveTrue("missing");
    }
}
