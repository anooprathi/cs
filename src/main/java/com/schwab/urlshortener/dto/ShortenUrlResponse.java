package com.schwab.urlshortener.dto;

import java.time.Instant;

public record ShortenUrlResponse(
        String shortCode,
        String shortUrl,
        String originalUrl,
        Instant createdAt,
        Instant expiresAt
) {
}
