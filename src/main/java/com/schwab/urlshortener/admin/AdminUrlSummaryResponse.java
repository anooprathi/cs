package com.schwab.urlshortener.admin;

import java.time.Instant;

public record AdminUrlSummaryResponse(
        String shortCode,
        String originalUrl,
        long clickCount,
        boolean active,
        Instant createdAt,
        Instant expiresAt
) {
}
