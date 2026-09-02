package com.schwab.urlshortener.dto;

import java.time.Instant;

public record UrlStatsResponse(
        String shortCode,
        String originalUrl,
        long clickCount,
        Instant createdAt,
        Instant lastAccessedAt,
        Instant expiresAt,
        boolean active
) {
}
