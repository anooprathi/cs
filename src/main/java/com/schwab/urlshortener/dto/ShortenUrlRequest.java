package com.schwab.urlshortener.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.schwab.urlshortener.util.ShortCodeFormat;

import java.time.Instant;

/**
 * Inbound request to create a short URL.
 * customAlias and expiresAt are optional.
 */
public record ShortenUrlRequest(

        @NotBlank(message = "originalUrl must not be blank")
        @Pattern(
                regexp = "^(https?)://[\\w.-]+(:\\d+)?(/[\\w\\-./?%&=+#~]*)?$",
                message = "originalUrl must be a valid http(s) URL"
        )
        @Size(max = 2048, message = "originalUrl must not exceed 2048 characters")
        String originalUrl,

        @Pattern(
                regexp = ShortCodeFormat.ANCHORED,
                message = "customAlias must be 4-20 characters of letters, digits, '-' or '_'"
        )
        String customAlias,

        @Future(message = "expiresAt must be in the future")
        Instant expiresAt
) {
}
