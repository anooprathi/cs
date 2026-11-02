package com.schwab.urlshortener.workflow.adapter;

import com.schwab.urlshortener.workflow.engine.AgentRequest;

/**
 * How a stage agent's generative step is executed. Exactly two implementations:
 * <ul>
 *   <li>{@link DeterministicDemoAdapter}: runs the step's rule-based path. Reproducible, offline, free,
 *       and NOT AI analysis.</li>
 *   <li>{@link LlmExecutionAdapter}: calls Claude through the Anthropic Java SDK with structured outputs,
 *       inside the run's token and cost budget. Configured only through environment/properties
 *       (ANTHROPIC_API_KEY, APP_AGENTIC_LLM_ENABLED, APP_AGENTIC_LLM_MODEL). No secret is in code.</li>
 * </ul>
 * {@link AdapterRouter} picks one per call from the run's execution mode (or the fallback flag).
 * Every {@link Generation} records which adapter produced it.
 */
public interface ExecutionAdapter {

    String name();

    <T> Generation<T> generate(GenerationRequest<T> generation, AgentRequest request);
}
