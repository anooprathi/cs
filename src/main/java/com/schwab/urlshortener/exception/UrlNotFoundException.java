package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a short code has no active mapping. */
public class UrlNotFoundException extends ApiException {
    public UrlNotFoundException(String shortCode) {
        super(HttpStatus.NOT_FOUND, "No active URL mapping found for short code: " + shortCode);
    }

    private UrlNotFoundException(String shortCode, boolean forTenant) {
        super(HttpStatus.NOT_FOUND, "No URL mapping found for short code '" + shortCode + "' owned by this tenant");
    }

    /**
     * For tenant-owned lookups that intentionally search regardless of
     * active status (update/reactivate) — the default constructor's
     * message ("no ACTIVE mapping") would be misleading here, since an
     * inactive-but-owned link is exactly what reactivate expects to find;
     * this is for the case where nothing owned by this tenant exists at all.
     */
    public static UrlNotFoundException forTenant(String shortCode) {
        return new UrlNotFoundException(shortCode, true);
    }
}
