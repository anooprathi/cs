package com.schwab.urlshortener.billing;

import com.schwab.urlshortener.tenant.Tenant;
import com.schwab.urlshortener.tenant.TenantPrincipal;
import com.schwab.urlshortener.tenant.TenantService;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * Tenant-scoped invoice generation and retrieval. Every operation here
 * operates on the authenticated tenant only — there is no cross-tenant
 * listing/lookup, consistent with how the rest of the management API
 * enforces isolation (see UrlShortenerController).
 *
 * Depends on TenantService, not TenantRepository directly — the tenant
 * aggregate's persistence is TenantService's to encapsulate, not
 * something every controller that happens to need a tenant's name
 * should reach past it for.
 */
@RestController
@RequestMapping("/api/v1/tenants/me/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final InvoicePdfGenerator pdfGenerator;
    private final TenantService tenantService;

    public InvoiceController(InvoiceService invoiceService, InvoicePdfGenerator pdfGenerator, TenantService tenantService) {
        this.invoiceService = invoiceService;
        this.pdfGenerator = pdfGenerator;
        this.tenantService = tenantService;
    }

    /** Generates an invoice for the given (or, if omitted, current) billing period. One per period — see InvoiceService. */
    @PostMapping
    public ResponseEntity<InvoiceResponse> generate(@Valid @RequestBody(required = false) InvoiceGenerateRequest request,
                                                      @AuthenticationPrincipal TenantPrincipal tenant) {
        String requestedPeriod = request != null ? request.billingPeriod() : null;
        InvoiceResponse response = invoiceService.generateInvoice(tenant.tenantId(), tenant.plan(), requestedPeriod);
        return ResponseEntity
                .created(URI.create("/api/v1/tenants/me/invoices/" + response.invoiceId()))
                .body(response);
    }

    @GetMapping
    public ResponseEntity<List<InvoiceResponse>> list(@AuthenticationPrincipal TenantPrincipal tenant) {
        return ResponseEntity.ok(invoiceService.listInvoices(tenant.tenantId()));
    }

    @GetMapping("/{invoiceId}")
    public ResponseEntity<InvoiceResponse> get(@PathVariable Long invoiceId,
                                                @AuthenticationPrincipal TenantPrincipal tenant) {
        return ResponseEntity.ok(invoiceService.getInvoice(invoiceId, tenant.tenantId()));
    }

    /** Downloads the invoice as a PDF. */
    @GetMapping(value = "/{invoiceId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getPdf(@PathVariable Long invoiceId,
                                          @AuthenticationPrincipal TenantPrincipal tenant) {
        Invoice invoice = invoiceService.getInvoiceEntity(invoiceId, tenant.tenantId());
        Tenant tenantEntity = tenantService.getTenantOrThrow(tenant.tenantId());

        byte[] pdfBytes = pdfGenerator.generate(invoice, tenantEntity.getName());

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(invoice.getInvoiceNumber() + ".pdf")
                .build();

        return ResponseEntity.status(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }
}
