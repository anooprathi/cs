package com.schwab.urlshortener.tenant;

import java.time.Instant;

/**
 * Returned exactly once, at registration time. apiKey is the only time
 * the raw secret is ever transmitted — store it now, it cannot be
 * retrieved again (only the hash is persisted).
 */
public record TenantRegistrationResponse(
        Long tenantId,
        String name,
        RateLimitPlan plan,
        String apiKey,
        Instant createdAt
) {
}
