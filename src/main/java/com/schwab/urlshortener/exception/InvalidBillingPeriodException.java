package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

/** Thrown for a malformed or future billing period requested for invoicing. */
public class InvalidBillingPeriodException extends ApiException {
    public InvalidBillingPeriodException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
