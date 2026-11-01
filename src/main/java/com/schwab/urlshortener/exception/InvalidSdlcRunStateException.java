package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a review decision isn't allowed for a run's current state:
 * the run isn't awaiting review (already decided, failed, still running),
 * or approval was requested while the quality gate has blockers.
 */
public class InvalidSdlcRunStateException extends ApiException {
    public InvalidSdlcRunStateException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
