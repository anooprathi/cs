package com.schwab.urlshortener.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TenantUsageRecordRepository extends JpaRepository<TenantUsageRecord, Long> {

    Optional<TenantUsageRecord> findByTenantIdAndPeriodYearMonth(Long tenantId, String periodYearMonth);

    /** Atomic increment; returns rows affected (0 means no record exists yet for this period). */
    @Modifying
    @Query("UPDATE TenantUsageRecord u SET u.apiCallCount = u.apiCallCount + 1 " +
            "WHERE u.tenantId = :tenantId AND u.periodYearMonth = :period")
    int incrementApiCallCount(@Param("tenantId") Long tenantId, @Param("period") String period);

    @Modifying
    @Query("UPDATE TenantUsageRecord u SET u.redirectCount = u.redirectCount + 1 " +
            "WHERE u.tenantId = :tenantId AND u.periodYearMonth = :period")
    int incrementRedirectCount(@Param("tenantId") Long tenantId, @Param("period") String period);

    /** Admin cross-tenant usage summary for a given period — every tenant
     *  that has any recorded usage that month (tenants with zero usage
     *  simply have no row and are reported as zero at the service layer). */
    List<TenantUsageRecord> findByPeriodYearMonth(String periodYearMonth);
}
