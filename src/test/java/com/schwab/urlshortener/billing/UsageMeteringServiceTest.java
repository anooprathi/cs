package com.schwab.urlshortener.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UsageMeteringServiceTest {

    @Mock
    private TenantUsageRecordRepository repository;

    @Mock
    private UsageRecordCreator usageRecordCreator;

    private UsageMeteringService service;

    @BeforeEach
    void setUp() {
        service = new UsageMeteringService(repository, usageRecordCreator);
    }

    @Test
    void recordApiCall_existingPeriodRow_incrementsInPlace() {
        when(repository.incrementApiCallCount(eq(1L), anyString())).thenReturn(1);

        service.recordApiCall(1L);

        verify(repository).incrementApiCallCount(eq(1L), anyString());
        verifyNoInteractions(usageRecordCreator);
    }

    @Test
    void recordApiCall_noRowYet_createsInitialRecord() {
        when(repository.incrementApiCallCount(eq(1L), anyString())).thenReturn(0);

        service.recordApiCall(1L);

        verify(usageRecordCreator).createInitialRecord(eq(1L), anyString(), eq(true));
    }

    @Test
    void recordApiCall_lostRaceCreatingRow_fallsBackToIncrement() {
        when(repository.incrementApiCallCount(eq(1L), anyString())).thenReturn(0, 1); // first call: no row, second: succeeds
        doThrow(new DataIntegrityViolationException("duplicate"))
                .when(usageRecordCreator).createInitialRecord(eq(1L), anyString(), eq(true));

        service.recordApiCall(1L);

        verify(repository, times(2)).incrementApiCallCount(eq(1L), anyString());
    }

    @Test
    void recordRedirect_existingPeriodRow_incrementsInPlace() {
        when(repository.incrementRedirectCount(eq(2L), anyString())).thenReturn(1);

        service.recordRedirect(2L);

        verify(repository).incrementRedirectCount(eq(2L), anyString());
        verifyNoInteractions(usageRecordCreator);
    }

    @Test
    void currentPeriod_isYearMonthFormat() {
        String period = service.currentPeriod();
        assertThatPeriodLooksValid(period);
    }

    private void assertThatPeriodLooksValid(String period) {
        org.assertj.core.api.Assertions.assertThat(period).matches("\\d{4}-\\d{2}");
    }
}
