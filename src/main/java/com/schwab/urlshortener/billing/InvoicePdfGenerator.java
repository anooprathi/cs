package com.schwab.urlshortener.billing;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;

/**
 * Renders a one-page PDF for an {@link Invoice} using OpenPDF (a
 * maintained fork of iText 4 — package name {@code com.lowagie.text} is
 * kept for that lineage's compatibility).
 *
 * Deliberately takes plain scalar fields off the entity rather than the
 * entity + a live BillingService lookup — the PDF must render exactly
 * what was frozen into the Invoice row, not whatever the tenant's usage
 * has become since (see Invoice's Javadoc on why invoices are snapshots).
 */
@Component
public class InvoicePdfGenerator {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy").withZone(ZoneOffset.UTC);

    private static final Font TITLE_FONT = new Font(Font.HELVETICA, 20, Font.BOLD);
    private static final Font HEADING_FONT = new Font(Font.HELVETICA, 11, Font.BOLD);
    private static final Font NORMAL_FONT = new Font(Font.HELVETICA, 10, Font.NORMAL);
    private static final Font SMALL_FONT = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.GRAY);
    private static final Font TOTAL_FONT = new Font(Font.HELVETICA, 13, Font.BOLD);

    public byte[] generate(Invoice invoice, String tenantName) {
        Document document = new Document(PageSize.LETTER, 50, 50, 50, 50);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(document, out);
            document.open();

            addHeader(document);
            addInvoiceMeta(document, invoice, tenantName);
            addLineItems(document, invoice);
            addTotal(document, invoice);
            addFootnote(document);

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to generate invoice PDF for " + invoice.getInvoiceNumber(), e);
        }
    }

    private void addHeader(Document document) throws DocumentException {
        Paragraph title = new Paragraph("INVOICE", TITLE_FONT);
        title.setSpacingAfter(4);
        document.add(title);

        Paragraph subtitle = new Paragraph("URL Shortener \u2014 usage-based billing statement", SMALL_FONT);
        subtitle.setSpacingAfter(20);
        document.add(subtitle);
    }

    private void addInvoiceMeta(Document document, Invoice invoice, String tenantName) throws DocumentException {
        PdfPTable meta = new PdfPTable(2);
        meta.setWidthPercentage(100);
        meta.setSpacingAfter(20);

        addKeyValue(meta, "Invoice Number", invoice.getInvoiceNumber());
        addKeyValue(meta, "Billing Period", invoice.getBillingPeriod());
        addKeyValue(meta, "Billed To", tenantName);
        addKeyValue(meta, "Plan", invoice.getPlan().name());
        addKeyValue(meta, "Issued", DATE_FORMAT.format(invoice.getIssuedAt()));
        addKeyValue(meta, "Status", invoice.getStatus().name());

        document.add(meta);
    }

    private void addLineItems(Document document, Invoice invoice) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[]{4, 2, 2, 2});
        table.setWidthPercentage(100);
        table.setSpacingBefore(10);

        addHeaderCell(table, "Line Item");
        addHeaderCell(table, "Used");
        addHeaderCell(table, "Included");
        addHeaderCell(table, "Amount");

        addRow(table, "Base subscription fee", "\u2014", "\u2014", formatCents(invoice.getBaseFeeCents()));
        addRow(table, "API calls (link creation)",
                String.valueOf(invoice.getApiCallsUsed()), String.valueOf(invoice.getApiCallsIncluded()), "included above");
        addRow(table, "Redirects served",
                String.valueOf(invoice.getRedirectsUsed()), String.valueOf(invoice.getRedirectsIncluded()), "included above");
        addRow(table, "Overage charges", "\u2014", "\u2014", formatCents(invoice.getOverageChargeCents()));

        document.add(table);
    }

    private void addTotal(Document document, Invoice invoice) throws DocumentException {
        Paragraph total = new Paragraph("Total: " + formatCents(invoice.getTotalChargeCents()), TOTAL_FONT);
        total.setSpacingBefore(16);
        total.setAlignment(Element.ALIGN_RIGHT);
        document.add(total);
    }

    private void addFootnote(Document document) throws DocumentException {
        Paragraph footnote = new Paragraph(
                "This is a computed usage statement for informational purposes only. No payment method is on "
                        + "file and no charge is processed automatically by this system.",
                SMALL_FONT);
        footnote.setSpacingBefore(30);
        document.add(footnote);
    }

    private void addKeyValue(PdfPTable table, String key, String value) {
        PdfPCell keyCell = new PdfPCell(new Phrase(key, HEADING_FONT));
        keyCell.setBorder(Rectangle.NO_BORDER);
        keyCell.setPaddingBottom(4);
        table.addCell(keyCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, NORMAL_FONT));
        valueCell.setBorder(Rectangle.NO_BORDER);
        valueCell.setPaddingBottom(4);
        table.addCell(valueCell);
    }

    private void addHeaderCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, HEADING_FONT));
        cell.setBackgroundColor(new Color(230, 230, 230));
        cell.setPadding(6);
        table.addCell(cell);
    }

    private void addRow(PdfPTable table, String label, String used, String included, String amount) {
        table.addCell(plainCell(label));
        table.addCell(plainCell(used));
        table.addCell(plainCell(included));
        table.addCell(plainCell(amount));
    }

    private PdfPCell plainCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, NORMAL_FONT));
        cell.setPadding(6);
        return cell;
    }

    private String formatCents(long cents) {
        return String.format("$%,.2f", cents / 100.0);
    }
}
