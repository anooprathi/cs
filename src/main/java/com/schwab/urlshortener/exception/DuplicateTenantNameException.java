package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a registration attempt's name normalizes to the same value
 * as an existing tenant's (case/whitespace-insensitive comparison — see
 * Tenant.normalizedName). Deliberately distinct from a raw duplicate: "Acme
 * Corp" and "ACME CORP " are the same conceptual tenant to anyone reading
 * them, and treating them as different rows would let the same
 * organization silently register multiple accounts.
 */
public class DuplicateTenantNameException extends ApiException {
    public DuplicateTenantNameException(String name) {
        super(HttpStatus.CONFLICT, "A tenant with this name (or one that normalizes to it) already exists: " + name);
    }
}
