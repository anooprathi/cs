package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

/**
 * The requested workflow operation isn't valid in the run's or task's current
 * state: starting a run twice, resuming while running, approving a task that
 * isn't waiting for approval, and so on.
 */
public class WorkflowConflictException extends ApiException {
    public WorkflowConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
