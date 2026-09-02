package com.schwab.urlshortener.billing;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deliberately its own Spring bean, not a private method on
 * {@link UsageMeteringService}: {@code @Transactional(REQUIRES_NEW)} only
 * takes effect on calls that go through the Spring AOP proxy, and a
 * same-class ("self-invocation") call bypasses that proxy entirely. By
 * putting the insert attempt here and having UsageMeteringService call it
 * as a genuine bean-to-bean collaborator, a constraint-violation on the
 * insert rolls back only THIS inner transaction — the caller's own
 * transaction (used for the retry increment afterward) is left clean
 * rather than being marked rollback-only by Hibernate's flush failure.
 */
@Component
class UsageRecordCreator {

    private final TenantUsageRecordRepository repository;

    UsageRecordCreator(TenantUsageRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createInitialRecord(Long tenantId, String period, boolean isApiCall) {
        repository.save(TenantUsageRecord.builder()
                .tenantId(tenantId)
                .periodYearMonth(period)
                .apiCallCount(isApiCall ? 1 : 0)
                .redirectCount(isApiCall ? 0 : 1)
                .build());
    }
}
