package com.schwab.urlshortener.exception;

/**
 * Thrown when the service cannot generate a unique short code after
 * exhausting its retry budget. Maps to HTTP 500 — signals an operational
 * problem (keyspace exhaustion / persistent collisions), not bad input.
 */
public class ShortCodeGenerationException extends RuntimeException {
    public ShortCodeGenerationException(String message) {
        super(message);
    }
}
