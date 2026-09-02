package com.schwab.urlshortener.security;

/**
 * The authenticated principal for admin requests. Unlike TenantPrincipal,
 * there's no per-admin database row — admin access is a single shared
 * credential (see AdminSecurityProperties), so a fixed singleton is all
 * that's needed to represent "this request presented a valid admin key."
 */
public record AdminPrincipal(String label) {
    public static final AdminPrincipal INSTANCE = new AdminPrincipal("admin");
}
