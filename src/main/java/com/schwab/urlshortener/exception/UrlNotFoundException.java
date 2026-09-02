package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a short code has no active mapping. */
public class UrlNotFoundException extends ApiException {
    public UrlNotFoundException(String shortCode) {
        super(HttpStatus.NOT_FOUND, "No active URL mapping found for short code: " + shortCode);
    }
}
