package com.schwab.urlshortener.workflow.adapter;

import java.util.function.Supplier;

/**
 * One generative step of a stage agent, expressed for BOTH adapters at once:
 * the prompts and output schema for the LLM adapter, and the rule-based
 * supplier for the demo adapter. Keeping them side by side means the two
 * modes can't drift into producing different artifact types.
 *
 * Prompts are passed only to the model. They are never logged or written to
 * the audit trail (see AuditLog redaction).
 */
public record GenerationRequest<T>(String purpose, Class<T> outputType, String systemPrompt, String userPrompt,
                                   Supplier<T> deterministic) {
}
