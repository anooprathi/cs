package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * A submitted workflow graph failed validation (cycle, unknown dependency,
 * input not produced upstream, ...). The message lists every error. Use
 * POST /api/v1/workflows/definitions/validate for the structured form.
 */
public class InvalidWorkflowDefinitionException extends ApiException {
    public InvalidWorkflowDefinitionException(List<String> errors) {
        super(HttpStatus.BAD_REQUEST, "Invalid workflow definition: " + String.join(" ", errors));
    }
}
