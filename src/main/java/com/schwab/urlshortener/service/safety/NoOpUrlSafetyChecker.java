package com.schwab.urlshortener.service.safety;

import com.schwab.urlshortener.service.UrlSafetyChecker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default: no external safety check — everything is treated as safe.
 * Active whenever app.url-safety-check.enabled is false OR unset
 * (matchIfMissing = true), so the service runs standalone with no
 * external dependency out of the box.
 */
@Component
@ConditionalOnProperty(name = "app.url-safety-check.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpUrlSafetyChecker implements UrlSafetyChecker {

    @Override
    public boolean isSafe(String originalUrl) {
        return true;
    }
}
