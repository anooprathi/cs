package com.schwab.urlshortener.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Partial update for an existing short URL — both fields optional (null
 * means "leave unchanged"), but at least one must be provided; that
 * "at least one of these optional fields" rule isn't expressible with a
 * single-field Bean Validation annotation, so it's enforced in
 * UrlShortenerServiceImpl instead. A null expiresAt means "unchanged," not
 * "clear it" — there is currently no way to remove an expiry once set via
 * this endpoint.
 */
public record UpdateUrlRequest(

        @Pattern(
                regexp = "^(https?)://[\\w.-]+(:\\d+)?(/[\\w\\-./?%&=+#~]*)?$",
                message = "originalUrl must be a valid http(s) URL"
        )
        @Size(max = 2048, message = "originalUrl must not exceed 2048 characters")
        String originalUrl,

        @Future(message = "expiresAt must be in the future")
        Instant expiresAt
) {
}
