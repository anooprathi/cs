package com.schwab.urlshortener.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Two bounded, in-process Caffeine caches for the app's hottest read paths:
 *  - {@link #SHORT_CODE_REDIRECTS}: shortCode -> redirect target, read on
 *    every single {@code GET /{shortCode}} — see CachedShortCodeLookup.
 *  - {@link #TENANT_PLANS}: tenantId -> plan, read on every redirect to
 *    decide the link OWNER's rate-limit tier — see TenantService.
 *
 * Registered as two separately-configured Caffeine instances (rather than
 * one CaffeineCacheManager-wide spec) because they have different
 * correctness/staleness tolerances: a redirect entry must expire quickly
 * enough to bound how long a deactivated/expired link could still resolve
 * from cache if an eviction call is ever missed, while a plan change is a
 * rare, deliberate admin action that can tolerate a longer window.
 *
 * Same posture as the rate-limiter and safety-check backends elsewhere in
 * this app: this is a local, per-instance cache, correct for a single
 * instance. A multi-instance deployment would see each instance cache
 * independently — fine for these two read-mostly, short-TTL caches (unlike
 * the rate limiter, staleness here doesn't compound across instances into
 * a fairness violation), but worth knowing if it's ever revisited.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String SHORT_CODE_REDIRECTS = "shortCodeRedirects";
    public static final String TENANT_PLANS = "tenantPlans";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.registerCustomCache(SHORT_CODE_REDIRECTS,
                Caffeine.newBuilder()
                        .maximumSize(50_000)
                        .expireAfterWrite(Duration.ofMinutes(2))
                        .recordStats()
                        .build());
        manager.registerCustomCache(TENANT_PLANS,
                Caffeine.newBuilder()
                        .maximumSize(10_000)
                        .expireAfterWrite(Duration.ofMinutes(10))
                        .recordStats()
                        .build());
        return manager;
    }
}
