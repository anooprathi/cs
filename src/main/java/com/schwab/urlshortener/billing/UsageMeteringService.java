package com.schwab.urlshortener.billing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.Instant;

/**
 * Records the two billable events in this system: creating a short link
 * (an "api call") and a redirect being served (a "redirect"). Deliberately
 * does NOT meter read-only calls like /stats, /me, or the billing endpoint
 * itself — checking your own usage/invoice shouldn't itself add to it.
 *
 * <b>Async, deliberately, with a stated trade-off.</b> Both methods run on
 * {@code meteringTaskExecutor} (see {@link com.schwab.urlshortener.config.AsyncConfig})
 * rather than the calling thread. The redirect path in particular is the
 * highest-QPS, most latency-sensitive endpoint in this system — a
 * tenant's viral link should not get slower to serve just because every
 * hit also does a metering write. Moving that write off the response path
 * means: (a) the caller (UrlShortenerServiceImpl) gets zero added latency
 * from metering, and (b) a crash between the primary write (the redirect
 * itself, or the link-creation row) and this async metering write can
 * lose that one usage count. That's an accepted trade-off, not an
 * oversight: usage metering feeds billing *summaries* and *on-demand*
 * invoice generation, not a real-time balance that must be exact to the
 * request — occasional undercounting by a handful of events per outage is
 * a materially different risk than, say, losing the click-count itself
 * (which stays synchronous, on the caller's own transaction, in
 * UrlShortenerServiceImpl) or the link/tenant records (always synchronous).
 * If billing ever needs to be exact-to-the-event, the right fix is a
 * durable outbox (write the usage event in the SAME transaction as the
 * primary write, then a separate process publishes it) — genuinely more
 * infrastructure than this system needs today, and called out here
 * rather than silently deferred.
 *
 * Concurrency: increments are attempted directly first (the common case,
 * once a period's row exists); on the rare "row doesn't exist yet this
 * month" case, this falls back to insert-then-retry via
 * {@link UsageRecordCreator} (a separate bean so its own transaction is
 * genuinely isolated — see that class's Javadoc), tolerating a
 * DataIntegrityViolationException if a concurrent request won the race to
 * create the same (tenantId, period) row first.
 */
@Service
@Slf4j
public class UsageMeteringService {

    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

    private final TenantUsageRecordRepository repository;
    private final UsageRecordCreator usageRecordCreator;

    public UsageMeteringService(TenantUsageRecordRepository repository, UsageRecordCreator usageRecordCreator) {
        this.repository = repository;
        this.usageRecordCreator = usageRecordCreator;
    }

    @Async("meteringTaskExecutor")
    @Transactional
    public void recordApiCall(Long tenantId) {
        recordUsage(tenantId, true);
    }

    @Async("meteringTaskExecutor")
    @Transactional
    public void recordRedirect(Long tenantId) {
        recordUsage(tenantId, false);
    }

    private void recordUsage(Long tenantId, boolean isApiCall) {
        String period = currentPeriod();
        int updated = isApiCall
                ? repository.incrementApiCallCount(tenantId, period)
                : repository.incrementRedirectCount(tenantId, period);

        if (updated > 0) {
            return;
        }

        // No row for this tenant+period yet — create it, tolerating a lost
        // race against a concurrent request doing the same thing.
        try {
            usageRecordCreator.createInitialRecord(tenantId, period, isApiCall);
        } catch (DataIntegrityViolationException e) {
            log.debug("Lost race creating usage record for tenant={} period={}; retrying increment", tenantId, period);
            if (isApiCall) {
                repository.incrementApiCallCount(tenantId, period);
            } else {
                repository.incrementRedirectCount(tenantId, period);
            }
        }
    }

    public String currentPeriod() {
        return PERIOD_FORMAT.format(Instant.now());
    }
}
