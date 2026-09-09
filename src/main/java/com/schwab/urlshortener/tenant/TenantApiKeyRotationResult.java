package com.schwab.urlshortener.tenant;

/**
 * Carries the raw key back out of {@link TenantService#rotateApiKey} for
 * exactly as long as it takes the caller to build a response — same
 * one-time-only handling as {@link TenantRegistrationResponse#apiKey()}.
 * Never persisted, logged, or held past that point.
 */
public record TenantApiKeyRotationResult(Tenant tenant, String rawApiKey) {
}
