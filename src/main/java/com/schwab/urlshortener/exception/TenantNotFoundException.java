package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when an admin operation references a tenant id that doesn't
 * exist. Distinct from TenantService.getTenantOrThrow's IllegalStateException
 * (500) used for the "authenticated tenant must exist" invariant — here
 * the tenant id is arbitrary caller input, so a nonexistent one is
 * ordinary client error territory (404), not an internal invariant
 * violation.
 */
public class TenantNotFoundException extends ApiException {
    public TenantNotFoundException(Long tenantId) {
        super(HttpStatus.NOT_FOUND, "No tenant found with id: " + tenantId);
    }
}
