package com.schwab.urlshortener.admin;

import jakarta.validation.constraints.Pattern;

/**
 * customDomain is nullable by design: a null value clears the tenant's
 * custom domain, reverting them to the platform's default short-link host.
 * When present, it must be a bare hostname — no scheme, path, or port —
 * since it's assembled into a URL (`https://{customDomain}/{shortCode}`)
 * elsewhere, not treated as a caller-supplied URL fragment.
 */
public record AdminUpdateDomainRequest(
        @Pattern(
                regexp = "^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$",
                message = "customDomain must be a bare hostname with no scheme, path, or port (e.g. go.company.com)"
        )
        String customDomain
) {
}
