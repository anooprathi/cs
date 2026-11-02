package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

/** The workflow driver pool is saturated, so the start or resume was not accepted. Retry later. */
public class WorkflowCapacityException extends ApiException {
    public WorkflowCapacityException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "Workflow execution capacity is exhausted; retry shortly.");
    }
}
