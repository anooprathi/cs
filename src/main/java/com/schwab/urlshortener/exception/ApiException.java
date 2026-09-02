package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

/**
 * Base for exceptions that map directly to a specific HTTP status with a
 * client-safe message and nothing else — GlobalExceptionHandler has a
 * single handler for this whole family, so a new "this business rule was
 * violated, return status X with this message" exception needs no new
 * handler method, just a subclass.
 *
 * Deliberately NOT used for ShortCodeGenerationException (its client
 * message must differ from its internal message — see that class) or
 * RateLimitExceededException (needs an extra Retry-After header) — both
 * carry behavior beyond "status + message" and keep their own handlers.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
