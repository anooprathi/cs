package com.schwab.urlshortener.agentic.llm;

/**
 * Any failure to obtain a usable artifact from the model. The message is
 * written to be safe to store on the failed artifact and show to an
 * operator: it describes what went wrong (refusal, truncation, API error
 * type) without echoing request content or credentials.
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
