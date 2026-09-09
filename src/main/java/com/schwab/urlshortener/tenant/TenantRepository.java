package com.schwab.urlshortener.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findByApiKeyHashAndActiveTrue(String apiKeyHash);

    /**
     * Replaces the old existsByName — which was case-sensitive, unnormalized,
     * AND (a genuine gap found in a production review) never actually
     * called anywhere, so tenant names had no enforced uniqueness at all
     * despite this method existing. See Tenant.normalizedName for what
     * "normalized" means here.
     */
    boolean existsByNormalizedName(String normalizedName);

    /** Used by updateCustomDomain to check for a collision with a
     *  DIFFERENT tenant — excluding self is the caller's job, since
     *  "already claimed by me" is a legitimate no-op, not a conflict. */
    Optional<Tenant> findByCustomDomain(String customDomain);
}
