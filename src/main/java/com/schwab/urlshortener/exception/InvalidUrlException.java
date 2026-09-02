package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

/** Thrown for semantically invalid input that passes bean validation but fails business rules. */
public class InvalidUrlException extends ApiException {
    public InvalidUrlException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
