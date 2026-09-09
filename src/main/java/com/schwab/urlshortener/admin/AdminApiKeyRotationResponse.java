package com.schwab.urlshortener.admin;

import java.time.Instant;

/**
 * Returned exactly once, at rotation time — mirrors
 * TenantRegistrationResponse. The old key stops working the instant this
 * completes; only the new key's hash is persisted, so if this response is
 * lost the fix is to rotate again, not to ask for it back.
 */
public record AdminApiKeyRotationResponse(
        Long tenantId,
        String name,
        String apiKey,
        Instant rotatedAt
) {
}
