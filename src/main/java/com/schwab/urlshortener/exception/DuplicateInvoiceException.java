package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

/** Thrown when an invoice already exists for the requested tenant/period. */
public class DuplicateInvoiceException extends ApiException {
    public DuplicateInvoiceException(String billingPeriod, String existingInvoiceNumber) {
        super(HttpStatus.CONFLICT, "An invoice already exists for period " + billingPeriod + ": " + existingInvoiceNumber);
    }
}
