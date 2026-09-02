package com.schwab.urlshortener.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Persistent record mapping a short code to an original (long) URL,
 * plus lightweight analytics counters.
 *
 * Indexing:
 *  - shortCode has a unique index: it is the primary lookup key on the
 *    hot path (redirect).
 *  - originalUrl is indexed to support idempotent re-shortening lookups
 *    and duplicate-detection without a full table scan.
 */
@Entity
@Table(
        name = "url_mapping",
        indexes = {
                @Index(name = "idx_short_code", columnList = "shortCode", unique = true),
                @Index(name = "idx_original_url", columnList = "originalUrl"),
                @Index(name = "idx_tenant_id", columnList = "tenantId")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UrlMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant. Redirects are public and tenant-agnostic; every
     *  management-API operation (create/stats/deactivate) is scoped to
     *  this value — see UrlShortenerServiceImpl. */
    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false, unique = true, length = 20)
    private String shortCode;

    @Column(nullable = false, length = 2048)
    private String originalUrl;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant expiresAt;

    private Instant lastAccessedAt;

    @Column(nullable = false)
    @Builder.Default
    private long clickCount = 0L;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /** True custom alias vs system-generated code; kept for analytics/reporting. */
    @Column(nullable = false)
    @Builder.Default
    private boolean customAlias = false;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
