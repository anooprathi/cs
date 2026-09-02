package com.schwab.urlshortener.billing;

import com.schwab.urlshortener.exception.DuplicateInvoiceException;
import com.schwab.urlshortener.exception.InvalidBillingPeriodException;
import com.schwab.urlshortener.exception.InvoiceNotFoundException;
import com.schwab.urlshortener.tenant.RateLimitPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Strictness.LENIENT: the usageMeteringService.currentPeriod() stub in
 * setUp() is shared for convenience across tests that exercise
 * generateInvoice's period-resolution/validation path, but a few tests
 * deliberately short-circuit before reaching that call (a malformed
 * period fails its regex check first) or don't call generateInvoice at
 * all (listInvoices/getInvoice). Strict stubbing would flag the shared
 * setup as "unused" in exactly those tests; lenient here is a deliberate
 * choice for a shared fixture, not stubbing sloppiness elsewhere.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvoiceServiceTest {

    @Mock
    private InvoiceRepository repository;

    @Mock
    private BillingService billingService;

    @Mock
    private UsageMeteringService usageMeteringService;

    private InvoiceService invoiceService;

    @BeforeEach
    void setUp() {
        invoiceService = new InvoiceService(repository, billingService, usageMeteringService);
        when(usageMeteringService.currentPeriod()).thenReturn("2026-08");
    }

    @Test
    void generateInvoice_noExistingInvoice_createsAndReturnsSnapshot() {
        when(repository.findByTenantIdAndBillingPeriod(1L, "2026-08")).thenReturn(Optional.empty());
        when(billingService.getStatementForPeriod(1L, RateLimitPlan.STANDARD, "2026-08"))
                .thenReturn(new BillingStatementResponse(1L, RateLimitPlan.STANDARD, "2026-08", 60, 50, 700, 500, 0, 220, 220));
        when(repository.save(any(Invoice.class))).thenAnswer(inv -> {
            Invoice i = inv.getArgument(0);
            i.setId(9L);
            i.setIssuedAt(Instant.now());
            return i;
        });

        InvoiceResponse response = invoiceService.generateInvoice(1L, RateLimitPlan.STANDARD, null);

        assertThat(response.invoiceNumber()).isEqualTo("INV-2026-08-000001");
        assertThat(response.totalChargeCents()).isEqualTo(220);
        assertThat(response.status()).isEqualTo(InvoiceStatus.ISSUED);

        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(1L);
        assertThat(captor.getValue().getBillingPeriod()).isEqualTo("2026-08");
    }

    @Test
    void generateInvoice_explicitPastPeriod_isHonored() {
        when(repository.findByTenantIdAndBillingPeriod(1L, "2026-06")).thenReturn(Optional.empty());
        when(billingService.getStatementForPeriod(1L, RateLimitPlan.STANDARD, "2026-06"))
                .thenReturn(new BillingStatementResponse(1L, RateLimitPlan.STANDARD, "2026-06", 0, 50, 0, 500, 0, 0, 0));
        when(repository.save(any(Invoice.class))).thenAnswer(inv -> {
            Invoice i = inv.getArgument(0);
            i.setId(1L);
            i.setIssuedAt(Instant.now());
            return i;
        });

        InvoiceResponse response = invoiceService.generateInvoice(1L, RateLimitPlan.STANDARD, "2026-06");

        assertThat(response.billingPeriod()).isEqualTo("2026-06");
    }

    @Test
    void generateInvoice_futurePeriod_isRejected() {
        assertThatThrownBy(() -> invoiceService.generateInvoice(1L, RateLimitPlan.STANDARD, "2027-01"))
                .isInstanceOf(InvalidBillingPeriodException.class);

        verifyNoInteractions(billingService);
        verify(repository, never()).save(any());
    }

    @Test
    void generateInvoice_malformedPeriod_isRejected() {
        assertThatThrownBy(() -> invoiceService.generateInvoice(1L, RateLimitPlan.STANDARD, "not-a-period"))
                .isInstanceOf(InvalidBillingPeriodException.class);
    }

    @Test
    void generateInvoice_alreadyExistsForPeriod_throwsDuplicateConflict() {
        Invoice existing = Invoice.builder()
                .id(5L).invoiceNumber("INV-2026-08-000001").tenantId(1L).billingPeriod("2026-08")
                .plan(RateLimitPlan.STANDARD).status(InvoiceStatus.ISSUED).build();
        when(repository.findByTenantIdAndBillingPeriod(1L, "2026-08")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> invoiceService.generateInvoice(1L, RateLimitPlan.STANDARD, null))
                .isInstanceOf(DuplicateInvoiceException.class)
                .hasMessageContaining("INV-2026-08-000001");

        verify(repository, never()).save(any());
        verifyNoInteractions(billingService);
    }

    @Test
    void listInvoices_returnsTenantsInvoicesOnly() {
        Invoice invoice = Invoice.builder()
                .id(1L).invoiceNumber("INV-2026-08-000001").tenantId(1L).billingPeriod("2026-08")
                .plan(RateLimitPlan.STANDARD).status(InvoiceStatus.ISSUED).issuedAt(Instant.now()).build();
        when(repository.findByTenantIdOrderByIssuedAtDesc(1L)).thenReturn(List.of(invoice));

        List<InvoiceResponse> result = invoiceService.listInvoices(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).invoiceNumber()).isEqualTo("INV-2026-08-000001");
    }

    @Test
    void getInvoice_belongsToDifferentTenant_throwsNotFound() {
        when(repository.findByIdAndTenantId(5L, 999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.getInvoice(5L, 999L))
                .isInstanceOf(InvoiceNotFoundException.class);
    }
}
