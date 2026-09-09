package com.schwab.urlshortener.service;

import com.schwab.urlshortener.dto.PageResponse;
import com.schwab.urlshortener.dto.ShortenUrlRequest;
import com.schwab.urlshortener.dto.ShortenUrlResponse;
import com.schwab.urlshortener.dto.UpdateUrlRequest;
import com.schwab.urlshortener.dto.UrlStatsResponse;
import org.springframework.data.domain.Pageable;

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

    /** Every link this tenant has ever created, active or not — the
     *  tenant-facing counterpart to AdminService's cross-tenant listing. */
    PageResponse<UrlStatsResponse> listMyUrls(Long tenantId, Pageable pageable);

    /** Partial update of destination URL and/or expiry on a link this
     *  tenant owns, active or not. See {@link UpdateUrlRequest} for the
     *  "at least one field" and "null means unchanged" rules. */
    UrlStatsResponse updateUrl(String shortCode, Long tenantId, UpdateUrlRequest request);

    /** Undoes a deactivation. Idempotent — reactivating an already-active
     *  link just succeeds. Reactivating a link whose expiresAt is already
     *  in the past brings it back to active=true, but it will still 410 on
     *  the next redirect attempt until its expiry is also pushed out via
     *  updateUrl — reactivate and un-expiring are deliberately separate
     *  operations, not one implicit action. */
    UrlStatsResponse reactivate(String shortCode, Long tenantId);
}
