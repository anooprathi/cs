package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

/** Thrown when an admin tries to assign a custom domain another tenant already has. */
public class DuplicateCustomDomainException extends ApiException {
    public DuplicateCustomDomainException(String customDomain) {
        super(HttpStatus.CONFLICT, "Custom domain already in use by another tenant: " + customDomain);
    }
}
