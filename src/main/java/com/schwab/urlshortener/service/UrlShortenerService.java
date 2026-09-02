package com.schwab.urlshortener.service;

import com.schwab.urlshortener.dto.ShortenUrlRequest;
import com.schwab.urlshortener.dto.ShortenUrlResponse;
import com.schwab.urlshortener.dto.UrlStatsResponse;

public interface UrlShortenerService {

    ShortenUrlResponse createShortUrl(ShortenUrlRequest request, Long tenantId);

    /**
     * Resolves a short code to its original URL and records a click.
     * Public/anonymous path — not tenant-scoped by caller identity, but
     * IS rate-limited against the link's *owning* tenant's fair-share
     * quota (see TenantRateLimiterService).
     * Throws UrlNotFoundException / UrlExpiredException / RateLimitExceededException on failure.
     */
    String resolveAndRecordHit(String shortCode);

    UrlStatsResponse getStats(String shortCode, Long tenantId);

    void deactivate(String shortCode, Long tenantId);
}
