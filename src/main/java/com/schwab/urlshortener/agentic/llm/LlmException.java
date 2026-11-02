package com.schwab.urlshortener.agentic.llm;

/**
 * Any failure to obtain a usable artifact from the model. The message is
 * written to be safe to store on the failed artifact and show to an
 * operator: it describes what went wrong (refusal, truncation, API error
 * type) without echoing request content or credentials.
 */
public class LlmException extends RuntimeException {

    private final boolean retryable;

    public LlmException(String message) {
        this(message, null, false);
    }

    public LlmException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public LlmException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * True for failures where the same request may succeed later: rate limiting
     * (429), server errors and overload (5xx), and transport errors. False for
     * refusals, truncation, unparseable output, and other 4xx errors, which won't
     * change on retry.
     */
    public boolean retryable() {
        return retryable;
    }
}
