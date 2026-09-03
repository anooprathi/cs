package com.schwab.urlshortener.service.impl;

import com.schwab.urlshortener.entity.UrlMapping;
import com.schwab.urlshortener.repository.UrlMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Previously untested entirely — @Scheduled methods don't run naturally
 * under any integration test's timeframe (this one fires every 10
 * minutes), so nothing exercised deactivateExpiredMappings() at all
 * before this class existed. Tested here as a plain unit test, calling
 * the method directly rather than waiting on the scheduler — the
 * scheduling annotation itself (interval, fixedDelay vs fixedRate) is
 * framework configuration, not business logic worth a test; the logic
 * worth testing is "does it find the right rows and correctly deactivate
 * exactly those."
 */
@ExtendWith(MockitoExtension.class)
class ExpiredUrlCleanupServiceTest {

    @Mock
    private UrlMappingRepository repository;

    private ExpiredUrlCleanupService service;

    @BeforeEach
    void setUp() {
        service = new ExpiredUrlCleanupService(repository);
    }

    @Test
    void noExpiredMappings_doesNothing() {
        when(repository.findByExpiresAtBeforeAndActiveTrue(any(Instant.class))).thenReturn(List.of());

        service.deactivateExpiredMappings();

        verify(repository, never()).saveAll(any());
    }

    @Test
    void expiredMappings_areDeactivatedAndSaved() {
        UrlMapping expired1 = UrlMapping.builder()
                .shortCode("abc1234").originalUrl("https://example.com/1")
                .active(true).expiresAt(Instant.now().minusSeconds(3600)).build();
        UrlMapping expired2 = UrlMapping.builder()
                .shortCode("def5678").originalUrl("https://example.com/2")
                .active(true).expiresAt(Instant.now().minusSeconds(60)).build();
        when(repository.findByExpiresAtBeforeAndActiveTrue(any(Instant.class))).thenReturn(List.of(expired1, expired2));

        service.deactivateExpiredMappings();

        assertThat(expired1.isActive()).isFalse();
        assertThat(expired2.isActive()).isFalse();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UrlMapping>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(expired1, expired2);
    }

    @Test
    void onlyDeactivatesWhatTheQueryReturns_doesNotWidenTheSet() {
        // The query itself (findByExpiresAtBeforeAndActiveTrue) is what
        // scopes "expired" — this test's job is to confirm the service
        // doesn't separately re-check or widen that set, just acts on
        // exactly what the query handed it.
        UrlMapping onlyExpiredOne = UrlMapping.builder()
                .shortCode("exp0001").originalUrl("https://example.com/expired")
                .active(true).expiresAt(Instant.now().minusSeconds(10)).build();
        when(repository.findByExpiresAtBeforeAndActiveTrue(any(Instant.class))).thenReturn(List.of(onlyExpiredOne));

        service.deactivateExpiredMappings();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UrlMapping>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getShortCode()).isEqualTo("exp0001");
    }
}
