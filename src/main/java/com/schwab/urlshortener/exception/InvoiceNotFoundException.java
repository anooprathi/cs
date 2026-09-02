package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

/** Thrown when an invoice does not exist, or does not belong to the requesting tenant. */
public class InvoiceNotFoundException extends ApiException {
    public InvoiceNotFoundException(Long invoiceId) {
        super(HttpStatus.NOT_FOUND, "No invoice found with id: " + invoiceId);
    }
}
