package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when an SDLC run asks for LLM mode but no LLM client is configured
 * (app.agentic.llm.enabled=false, the default). 503 rather than 400: the
 * request is well-formed, this deployment just doesn't offer the capability
 * right now. Deliberately never handled by falling back to
 * DETERMINISTIC_DEMO — a caller who asked for model output must not
 * receive rule-based output it could mistake for it.
 */
public class LlmUnavailableException extends ApiException {
    public LlmUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE,
                "LLM execution mode is not enabled on this deployment (app.agentic.llm.enabled=false). "
                        + "Use mode DETERMINISTIC_DEMO, or have an operator enable LLM mode with an ANTHROPIC_API_KEY.");
    }
}
