package com.schwab.urlshortener.agentic.llm;

/**
 * The orchestrator's only view of a language model: "given these
 * instructions, return an instance of this artifact type." Kept this
 * narrow so the orchestrator and agents never touch SDK types, and so
 * tests can substitute a scripted implementation without mocking the SDK.
 *
 * A bean of this type exists ONLY when app.agentic.llm.enabled=true (see
 * AgenticLlmConfig). Its absence is how the rest of the layer knows LLM
 * mode is unavailable — there is no "disabled" implementation that could
 * be mistaken for a working one.
 */
public interface LlmClient {

    /** Model id actually used for calls, recorded on every LLM-produced artifact. */
    String modelId();

    /**
     * @throws LlmException on any failure — transport, API error, refusal,
     *         truncation, or output that can't be parsed into {@code outputType}.
     *         Never returns a partial or default object.
     */
    <T> LlmResponse<T> generate(String systemPrompt, String userPrompt, Class<T> outputType);
}
