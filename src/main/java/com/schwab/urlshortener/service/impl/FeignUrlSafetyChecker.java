package com.schwab.urlshortener.service.impl;

import com.schwab.urlshortener.client.UrlSafetyClient;
import com.schwab.urlshortener.service.UrlSafetyChecker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Calls the external URL-safety service via Spring Cloud OpenFeign.
 * Active only when app.url-safety-check.enabled=true (mutually exclusive
 * with NoOpUrlSafetyChecker's condition, so exactly one UrlSafetyChecker
 * bean exists in the context — no @Primary needed).
 *
 * Fails open on any error/timeout: a third-party outage degrades to "no
 * check performed," never to "block all link creation." See
 * ARCHITECTURE.md for that trade-off's rationale.
 */
@Component
@ConditionalOnProperty(name = "app.url-safety-check.enabled", havingValue = "true")
@Slf4j
public class FeignUrlSafetyChecker implements UrlSafetyChecker {

    private final UrlSafetyClient client;

    public FeignUrlSafetyChecker(UrlSafetyClient client) {
        this.client = client;
    }

    @Override
    public boolean isSafe(String originalUrl) {
        try {
            UrlSafetyClient.UrlSafetyResult result = client.check(originalUrl);
            if (!result.safe()) {
                log.warn("URL flagged unsafe by safety service: category={} url={}", result.category(), originalUrl);
            }
            return result.safe();
        } catch (Exception ex) {
            log.error("URL safety check failed; failing open (treating as safe). url={}", originalUrl, ex);
            return true;
        }
    }
}
