package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a requested custom alias is already in use. */
public class DuplicateAliasException extends ApiException {
    public DuplicateAliasException(String alias) {
        super(HttpStatus.CONFLICT, "Custom alias is already in use: " + alias);
    }
}
