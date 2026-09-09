package com.schwab.urlshortener.tenant;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A tenant is the unit of isolation and fair-share rate limiting: every
 * short URL belongs to exactly one tenant, and every management-API
 * request is authenticated as a tenant via its API key.
 *
 * Security note on apiKeyHash: API keys are high-entropy, randomly
 * generated, machine-held secrets (not user-chosen passwords), so a
 * fast deterministic hash (SHA-256) used as a unique, directly-indexed
 * lookup column is the right trade-off here — it gives O(1) lookup by
 * hash with no plaintext key ever persisted, without BCrypt's
 * deliberately-slow, per-guess cost that low-entropy user passwords
 * need to resist offline brute-forcing. The raw key is shown to the
 * caller exactly once, at creation time, and never stored or logged.
 */
@Entity
@Table(
        name = "tenant",
        indexes = {
                @Index(name = "idx_tenant_api_key_hash", columnList = "apiKeyHash", unique = true),
                @Index(name = "idx_tenant_normalized_name", columnList = "normalizedName", unique = true),
                @Index(name = "idx_tenant_custom_domain", columnList = "customDomain", unique = true)
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /**
     * Lowercased, trimmed form of {@code name}, computed once at creation
     * (see TenantService.register) and given the actual DB-level unique
     * constraint above — {@code name} itself deliberately has none.
     * "Acme Corp" and "ACME CORP" should not be able to register as two
     * separate tenants just because a raw string-equality check would see
     * them as different; the display name stays exactly as the caller
     * typed it, this column exists purely so uniqueness means what a human
     * reading two names would expect it to mean.
     */
    @Column(nullable = false, length = 100)
    private String normalizedName;

    @Column(nullable = false, unique = true, length = 64)
    private String apiKeyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RateLimitPlan plan = RateLimitPlan.STANDARD;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * Branded/custom short-link domain (e.g. {@code go.company.com}), set
     * exclusively by an admin (see AdminService.updateCustomDomain) — a
     * tenant cannot self-assign one, same reasoning as plan changes:
     * claiming a domain has real-world implications (whoever controls it
     * can point it here) that shouldn't be a self-service action.
     * Null means "use the platform's default host" (app.base-url).
     *
     * Deliberately application-level only: setting this value records
     * that the domain is associated with this tenant, but does NOT
     * provision DNS, TLS certificates, or anything that makes
     * https://{customDomain} actually resolve to and be trusted by this
     * application — that's real infrastructure (a CNAME record pointed
     * here, a certificate issued for that exact hostname, e.g. via a
     * reverse proxy with automatic ACME/Let's Encrypt handling) outside
     * what this codebase can provide or verify on its own. See README's
     * "Custom domains" note for the explicit boundary.
     */
    @Column(length = 255)
    private String customDomain;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
