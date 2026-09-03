package com.schwab.urlshortener.repository;

import com.schwab.urlshortener.entity.UrlMapping;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    Optional<UrlMapping> findByShortCodeAndActiveTrue(String shortCode);

    /** Tenant-scoped lookup for management endpoints (stats/deactivate) —
     *  a defense-in-depth measure so a cross-tenant fetch is impossible
     *  at the query level, not just via an application-level check. */
    Optional<UrlMapping> findByShortCodeAndActiveTrueAndTenantId(String shortCode, Long tenantId);

    boolean existsByShortCode(String shortCode);

    Optional<UrlMapping> findFirstByOriginalUrlAndActiveTrueAndExpiresAtIsNull(String originalUrl);

    /**
     * Atomic increment to avoid read-modify-write races on the hot redirect path
     * under concurrent traffic to the same short code.
     */
    @Modifying
    @Query("UPDATE UrlMapping u SET u.clickCount = u.clickCount + 1, u.lastAccessedAt = :now " +
            "WHERE u.shortCode = :shortCode")
    int incrementClickCount(@Param("shortCode") String shortCode, @Param("now") Instant now);

    List<UrlMapping> findByExpiresAtBeforeAndActiveTrue(Instant cutoff);

    /** Admin visibility: ALL of a tenant's links, active or deactivated —
     *  unlike the tenant-facing endpoints, admins can see the full history.
     *  Paginated (see AdminController) — an unpaginated "every link this
     *  tenant has ever created" was fine for a demo, not for a tenant with
     *  a real link volume. Sort order is supplied by the caller via
     *  Pageable rather than baked into the method name, so the service
     *  layer can apply createdAt-desc without needing two near-identical
     *  repository methods (one paginated, one not). */
    Page<UrlMapping> findByTenantId(Long tenantId, Pageable pageable);

    long countByTenantId(Long tenantId);
}
