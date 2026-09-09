package com.schwab.urlshortener.service.impl;

import com.schwab.urlshortener.config.CacheConfig;
import com.schwab.urlshortener.repository.UrlMappingRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Caches the redirect hot path's lookup (shortCode -> destination),
 * deliberately split out of {@link UrlShortenerServiceImpl} into its own
 * bean: Spring's {@code @Cacheable} is proxy-based, so calling it from
 * another method on the *same* class (self-invocation) bypasses the proxy
 * entirely and silently never caches — the exact same reason
 * {@code @Transactional} needs a separate bean to work from a self-call.
 *
 * Caches a small immutable projection, {@link RedirectTarget}, not the
 * {@code UrlMapping} entity itself: caching a mutable JPA entity means
 * every caller shares (and could corrupt) the same in-memory instance
 * across requests, and this path only ever needs three scalar fields off
 * it. It deliberately does NOT include clickCount/lastAccessedAt — those
 * are updated via a separate bulk {@code @Modifying} query on every hit
 * (see {@code UrlShortenerServiceImpl.resolveAndRecordHit}) specifically
 * so a hot link doesn't serialize through JPA dirty-checking; a cached
 * entity would go stale on that column immediately anyway, so it's not
 * pretended to be cacheable here.
 *
 * A miss (not found) is deliberately NOT cached ({@code unless} below) —
 * caching negative lookups would let an attacker/scanner probing random
 * codes cheaply fill the cache with misses, and a false "not found" would
 * never self-correct for a link created moments after the miss was cached.
 *
 * Invalidation: {@link #evict(String)} is called explicitly from
 * {@code UrlShortenerServiceImpl.deactivate} and from the scheduled expiry
 * sweep ({@code ExpiredUrlCleanupService}) — the two known write paths that
 * must take effect immediately. The 2-minute TTL on this cache (see
 * {@link CacheConfig}) is the backstop for any other path, not the primary
 * invalidation mechanism.
 */
@Component
public class CachedShortCodeLookup {

    private final UrlMappingRepository repository;

    public CachedShortCodeLookup(UrlMappingRepository repository) {
        this.repository = repository;
    }

    @Cacheable(cacheNames = CacheConfig.SHORT_CODE_REDIRECTS, key = "#shortCode", unless = "#result == null")
    public RedirectTarget findActive(String shortCode) {
        return repository.findByShortCodeAndActiveTrue(shortCode)
                .map(m -> new RedirectTarget(m.getTenantId(), m.getOriginalUrl(), m.getExpiresAt()))
                .orElse(null);
    }

    @CacheEvict(cacheNames = CacheConfig.SHORT_CODE_REDIRECTS, key = "#shortCode")
    public void evict(String shortCode) {
        // Body intentionally empty — @CacheEvict does the actual work.
    }

    public record RedirectTarget(Long tenantId, String originalUrl, Instant expiresAt) {
        public boolean isExpired() {
            return expiresAt != null && Instant.now().isAfter(expiresAt);
        }
    }
}
