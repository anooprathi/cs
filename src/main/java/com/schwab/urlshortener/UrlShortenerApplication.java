package com.schwab.urlshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the URL Shortener service.
 *
 * Scope (per assignment): core shorten/redirect APIs, click analytics,
 * and reliability features (expiry handling, cleanup of expired links),
 * plus multi-tenant isolation, API-key security, and per-tenant
 * rate limiting (see the tenant/security/ratelimit packages).
 *
 * Spring Cloud usage: {@code @EnableFeignClients} activates the declarative
 * HTTP client used by {@code UrlSafetyClient} to call an external URL-safety
 * check service — a realistic extension point for a URL shortener (blocking
 * phishing/malware links). It is feature-flagged off by default (see
 * app.url-safety-check.enabled) so the prototype runs standalone with no
 * external network dependency; see ARCHITECTURE.md for the brownfield
 * scenario that exercises it.
 */
@SpringBootApplication
@EnableScheduling
@EnableFeignClients
@ConfigurationPropertiesScan
public class UrlShortenerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(UrlShortenerApplication.class);
        // Registered directly here, not as a @Component — see
        // RequiredProfileGuard's Javadoc for why a normal bean would run
        // too late to matter.
        app.addListeners(new com.schwab.urlshortener.config.RequiredProfileGuard());
        app.run(args);
    }
}
