package com.schwab.urlshortener.agentic.llm;

/** A parsed, schema-conforming model output plus the usage the API reported for producing it. */
public record LlmResponse<T>(T output, long inputTokens, long outputTokens) {
}
