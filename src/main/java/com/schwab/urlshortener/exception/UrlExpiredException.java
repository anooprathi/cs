package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a short code exists but has passed its expiry. */
public class UrlExpiredException extends ApiException {
    public UrlExpiredException(String shortCode) {
        super(HttpStatus.GONE, "Short URL has expired: " + shortCode);
    }
}
