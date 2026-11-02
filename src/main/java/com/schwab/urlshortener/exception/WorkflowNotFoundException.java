package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a workflow run, or a task or artifact within one, does not exist. */
public class WorkflowNotFoundException extends ApiException {

    public WorkflowNotFoundException(Long runId) {
        super(HttpStatus.NOT_FOUND, "No workflow run found with id: " + runId);
    }

    public WorkflowNotFoundException(Long runId, String kind, String name) {
        super(HttpStatus.NOT_FOUND, "No " + kind + " '" + name + "' in workflow run " + runId);
    }
}
