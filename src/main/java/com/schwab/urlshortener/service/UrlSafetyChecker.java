package com.schwab.urlshortener.service;

/**
 * Decides whether a URL is safe to shorten. Exactly one implementation is
 * active at a time, selected by Spring config (app.url-safety-check.enabled)
 * — see NoOpUrlSafetyChecker and FeignUrlSafetyChecker. Replaces an
 * earlier single class that branched on a boolean flag internally: this
 * is real Strategy-pattern selection (decided once at startup by Spring,
 * not re-branched on every call), and adding a third provider later means
 * adding a new implementation, not editing this interface or its caller.
 */
public interface UrlSafetyChecker {
    boolean isSafe(String originalUrl);
}
