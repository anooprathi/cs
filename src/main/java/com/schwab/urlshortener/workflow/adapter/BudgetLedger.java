package com.schwab.urlshortener.workflow.adapter;

import com.schwab.urlshortener.workflow.WorkflowProperties;
import com.schwab.urlshortener.workflow.engine.AgentFailures;
import com.schwab.urlshortener.workflow.engine.AgentResult;
import com.schwab.urlshortener.workflow.persistence.WorkflowArtifact;
import com.schwab.urlshortener.workflow.persistence.WorkflowArtifactRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-run token and estimated-cost budget for LLM calls. Seeded from durable
 * usage (tokens recorded on the run's stored artifacts) the first time a run is
 * seen in this process, so a resumed run doesn't get a fresh budget.
 *
 * Check-then-call: a call is refused once the budget is spent, but one call
 * already in flight can overshoot it by at most one response's worth of tokens
 * (bounded by max-output-tokens-per-call). Cost is an estimate from configured
 * prices, not a billed amount.
 */
@Component
public class BudgetLedger {

    private record Spend(long tokens, double costUsd) {
    }

    private final Map<Long, Spend> spend = new ConcurrentHashMap<>();
    private final WorkflowArtifactRepository artifacts;
    private final WorkflowProperties.Limits limits;

    public BudgetLedger(WorkflowArtifactRepository artifacts, WorkflowProperties properties) {
        this.artifacts = artifacts;
        this.limits = properties.limits();
    }

    public double estimateCostUsd(long inputTokens, long outputTokens) {
        return inputTokens / 1_000_000.0 * limits.inputUsdPerMtok() + outputTokens / 1_000_000.0 * limits.outputUsdPerMtok();
    }

    public void checkAvailable(Long runId) {
        Spend s = current(runId);
        if (s.tokens() >= limits.maxTokensPerRun()) {
            throw new AgentFailures.BudgetExceeded("Run token budget exhausted: " + s.tokens() + " of " + limits.maxTokensPerRun() + " tokens used");
        }
        if (s.costUsd() >= limits.maxCostUsdPerRun()) {
            throw new AgentFailures.BudgetExceeded(String.format("Run cost budget exhausted: est. $%.4f of $%.2f", s.costUsd(), limits.maxCostUsdPerRun()));
        }
    }

    public void charge(Long runId, AgentResult.Usage usage) {
        spend.compute(runId, (id, s) -> {
            Spend base = s == null ? seed(id) : s;
            return new Spend(base.tokens() + usage.inputTokens() + usage.outputTokens(), base.costUsd() + usage.estimatedCostUsd());
        });
    }

    public long remainingTokens(Long runId) {
        return Math.max(0, limits.maxTokensPerRun() - current(runId).tokens());
    }

    public double remainingCostUsd(Long runId) {
        return Math.max(0, limits.maxCostUsdPerRun() - current(runId).costUsd());
    }

    private Spend current(Long runId) {
        return spend.computeIfAbsent(runId, this::seed);
    }

    private Spend seed(Long runId) {
        long tokens = 0;
        long in = 0;
        long out = 0;
        for (WorkflowArtifact a : artifacts.findByRunIdOrderByIdAsc(runId)) {
            in += a.getInputTokens() == null ? 0 : a.getInputTokens();
            out += a.getOutputTokens() == null ? 0 : a.getOutputTokens();
        }
        tokens = in + out;
        return new Spend(tokens, estimateCostUsd(in, out));
    }
}
