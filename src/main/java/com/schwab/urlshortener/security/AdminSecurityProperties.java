package com.schwab.urlshortener.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.admin.api-key-hash} — the SHA-256 hash (see
 * ApiKeyGenerator.hash, reused here rather than duplicated) of the shared
 * admin credential. Deliberately has no default value in the common
 * application.properties: if this is blank, AdminAuthenticationFilter
 * fails closed (admin access disabled entirely), never fails open. See
 * application-dev.properties for the dev-only default and its raw key.
 */
@ConfigurationProperties(prefix = "app.admin")
public record AdminSecurityProperties(String apiKeyHash) {
}
