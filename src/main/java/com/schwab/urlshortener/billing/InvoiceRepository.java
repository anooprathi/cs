package com.schwab.urlshortener.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Optional<Invoice> findByTenantIdAndBillingPeriod(Long tenantId, String billingPeriod);

    List<Invoice> findByTenantIdOrderByIssuedAtDesc(Long tenantId);

    /** Tenant-scoped fetch for the detail/PDF endpoints — same defense-in-depth
     *  pattern as UrlMappingRepository's tenant-scoped queries. */
    Optional<Invoice> findByIdAndTenantId(Long id, Long tenantId);
}
