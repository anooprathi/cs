package com.schwab.urlshortener.exception;

/**
 * Thrown when a tenant has exhausted its fair-share rate-limit bucket.
 * Maps to HTTP 429 with a Retry-After header — see GlobalExceptionHandler.
 */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
