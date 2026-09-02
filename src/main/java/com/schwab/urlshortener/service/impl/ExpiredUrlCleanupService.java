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
 */
@Component
@Slf4j
public class ExpiredUrlCleanupService {

    private final UrlMappingRepository repository;

    public ExpiredUrlCleanupService(UrlMappingRepository repository) {
        this.repository = repository;
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
        log.info("Deactivated {} expired short URL mapping(s)", expired.size());
    }
}
