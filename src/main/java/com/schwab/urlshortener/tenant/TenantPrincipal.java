package com.schwab.urlshortener.tenant;

/**
 * The Spring Security {@code Authentication} principal for an
 * authenticated request. Carries just enough to authorize and rate-limit
 * without a repository round-trip on every downstream call — resolved
 * once per request in {@code ApiKeyAuthenticationFilter}.
 */
public record TenantPrincipal(Long tenantId, String name, RateLimitPlan plan) {
}
