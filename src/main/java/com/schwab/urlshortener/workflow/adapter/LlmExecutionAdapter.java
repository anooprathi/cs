package com.schwab.urlshortener.workflow.adapter;

import com.schwab.urlshortener.agentic.llm.LlmClient;
import com.schwab.urlshortener.agentic.llm.LlmException;
import com.schwab.urlshortener.agentic.llm.LlmResponse;
import com.schwab.urlshortener.workflow.WorkflowProperties;
import com.schwab.urlshortener.workflow.engine.AgentFailures;
import com.schwab.urlshortener.workflow.engine.AgentRequest;
import com.schwab.urlshortener.workflow.engine.AgentResult;
import com.schwab.urlshortener.workflow.engine.ArtifactOrigin;
import com.schwab.urlshortener.workflow.engine.ToolPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Real LLM execution: one structured-output call to Claude per generative step,
 * through the shared {@link LlmClient} (the official Anthropic Java SDK, see
 * agentic/llm/AnthropicLlmClient).
 *
 * Configured entirely from the environment, with nothing hardcoded:
 * {@code ANTHROPIC_API_KEY} (read by the SDK), {@code APP_AGENTIC_LLM_ENABLED=true},
 * and optionally {@code APP_AGENTIC_LLM_MODEL} / {@code APP_AGENTIC_LLM_MAX_TOKENS}.
 * Without {@code APP_AGENTIC_LLM_ENABLED=true} no client exists. LLM-mode runs
 * are then refused at creation (503) rather than started.
 *
 * Enforced around every call: the agent must hold {@link ToolPolicy.Tool#LLM_GENERATE};
 * the run's token and cost budget must not be spent ({@link BudgetLedger}); output
 * tokens are capped per call. The SDK's failures are mapped onto the engine's
 * recovery taxonomy: retryable API/transport errors become
 * {@link AgentFailures.Transient}, and refusals and unusable output become
 * {@link AgentFailures.AgentFailure}.
 *
 * Model output is untrusted data. It's parsed into a typed record and checked
 * by deterministic gates, and nothing it contains is ever executed.
 */
@Component
public class LlmExecutionAdapter implements ExecutionAdapter {

    public static final String NAME = "anthropic-llm";

    private final ObjectProvider<LlmClient> llmClient;
    private final BudgetLedger budget;
    private final long maxOutputTokensPerCall;

    public LlmExecutionAdapter(ObjectProvider<LlmClient> llmClient, BudgetLedger budget, WorkflowProperties properties) {
        this.llmClient = llmClient;
        this.budget = budget;
        this.maxOutputTokensPerCall = properties.limits().maxOutputTokensPerCall();
    }

    @Override
    public String name() {
        return NAME;
    }

    public boolean available() {
        return llmClient.getIfAvailable() != null;
    }

    public String model() {
        LlmClient client = llmClient.getIfAvailable();
        return client == null ? null : client.modelId();
    }

    @Override
    public <T> Generation<T> generate(GenerationRequest<T> generation, AgentRequest request) {
        request.requireTool(ToolPolicy.Tool.LLM_GENERATE);
        LlmClient client = llmClient.getIfAvailable();
        if (client == null) {
            throw new AgentFailures.AgentFailure("LLM mode requested but no LLM client is configured (APP_AGENTIC_LLM_ENABLED is not true)");
        }
        // The LLM boundary: nothing credential-shaped leaves this process, whatever upstream checks missed.
        java.util.List<String> secrets = com.schwab.urlshortener.util.SecretPatterns.kindsFound(generation.systemPrompt(), generation.userPrompt());
        if (!secrets.isEmpty()) {
            throw new AgentFailures.PolicyViolation("The outgoing '" + generation.purpose() + "' prompt contains what looks like a "
                    + String.join(", ", secrets) + "; the model call was blocked before anything was sent.");
        }
        budget.checkAvailable(request.runId());
        long cap = Math.min(maxOutputTokensPerCall, Math.max(1, request.limits().maxOutputTokensPerCall()));
        LlmResponse<T> response;
        try {
            response = client.generate(generation.systemPrompt(), generation.userPrompt(), generation.outputType(), cap);
        } catch (LlmException e) {
            if (e.retryable()) {
                throw new AgentFailures.Transient(generation.purpose() + ": " + e.getMessage(), e);
            }
            throw new AgentFailures.AgentFailure(generation.purpose() + ": " + e.getMessage(), e);
        }
        AgentResult.Usage usage = new AgentResult.Usage(response.inputTokens(), response.outputTokens(),
                budget.estimateCostUsd(response.inputTokens(), response.outputTokens()), 1);
        budget.charge(request.runId(), usage);
        if (response.output() == null) {
            throw new AgentFailures.AgentFailure(generation.purpose() + ": model returned no artifact");
        }
        return new Generation<>(response.output(), ArtifactOrigin.LLM, NAME, client.modelId(), usage);
    }
}
