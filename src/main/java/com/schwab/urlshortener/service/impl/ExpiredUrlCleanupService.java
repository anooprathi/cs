package com.schwab.urlshortener.service.impl;

import com.schwab.urlshortener.entity.UrlMapping;
import com.schwab.urlshortener.repository.UrlMappingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Reliability feature: periodically deactivates expired mappings so the
 * "active" short-code index stays clean and redirect lookups on hot codes
 * don't keep hitting stale/expired rows. Runs every 10 minutes; fixedDelay
 * (not fixedRate) so a slow run never overlaps with the next one.
 *
 * Also evicts each deactivated code from {@link CachedShortCodeLookup} —
 * without this, a code cached (with a still-future expiresAt at cache
 * time) just before this sweep runs could keep resolving from cache for
 * up to that cache's TTL after this method has already deactivated it in
 * the database. In practice CachedShortCodeLookup's own isExpired() check
 * against the cached expiresAt already catches the common case; this
 * eviction is the backstop for active=false itself being the reason a
 * lookup should now fail (e.g. a customer-initiated deactivation racing
 * this sweep), not just expiry.
 */
@Component
@Slf4j
public class ExpiredUrlCleanupService {

    private final UrlMappingRepository repository;
    private final CachedShortCodeLookup cachedShortCodeLookup;

    public ExpiredUrlCleanupService(UrlMappingRepository repository, CachedShortCodeLookup cachedShortCodeLookup) {
        this.repository = repository;
        this.cachedShortCodeLookup = cachedShortCodeLookup;
    }

    @Scheduled(fixedDelay = 10 * 60 * 1000)
    @Transactional
    public void deactivateExpiredMappings() {
        List<UrlMapping> expired = repository.findByExpiresAtBeforeAndActiveTrue(Instant.now());
        if (expired.isEmpty()) {
            return;
        }
        expired.forEach(m -> m.setActive(false));
        repository.saveAll(expired);
        expired.forEach(m -> cachedShortCodeLookup.evict(m.getShortCode()));
        log.info("Deactivated {} expired short URL mapping(s)", expired.size());
    }
}
