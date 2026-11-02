package com.schwab.urlshortener.workflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.workflow.engine.ArtifactOrigin;
import com.schwab.urlshortener.workflow.engine.RunStatus;
import com.schwab.urlshortener.workflow.engine.TaskState;
import com.schwab.urlshortener.workflow.graph.GraphValidationResult;
import com.schwab.urlshortener.workflow.graph.WorkflowStage;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Response shapes of the workflow API. */
public final class WorkflowViews {

    private WorkflowViews() {
    }

    public static final String DEMO_NOTICE = "DETERMINISTIC_DEMO: no language model is called. Agent steps run rule-based demo logic; "
            + "gates, policies, and the workspace pipeline are real.";
    public static final String LLM_NOTICE = "LLM: agent steps call the model named in 'model' through the Anthropic API; gates, "
            + "policies, and the workspace pipeline are deterministic code. Model output requires human review.";

    public record Run(
            Long id,
            String title,
            ExecutionMode executionMode,
            String executionNotice,
            String model,
            RunStatus status,
            int requirementRevision,
            String requirementHash,
            String definitionName,
            List<List<String>> parallelLayers,
            Map<String, Integer> injectedFaults,
            String baseCommit,
            String workspace,
            Promotion promotion,
            String failureReason,
            int driveCount,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            List<Task> tasks,
            List<Approval> approvals
    ) {
    }

    public record Promotion(String branch, String commit, String promotedBy, Instant promotedAt, Instant compensatedAt) {
    }

    public record Task(
            String key,
            WorkflowStage stage,
            String agent,
            TaskState state,
            List<String> dependsOn,
            int attempts,
            int retries,
            boolean reused,
            boolean fallbackUsed,
            boolean requiresApproval,
            String reviewReason,
            Map<String, String> outputs,
            String error,
            JsonNode entryGate,
            JsonNode exitGate,
            Instant startedAt,
            Instant finishedAt
    ) {
    }

    public record RunSummary(Long id, String title, ExecutionMode executionMode, RunStatus status, Instant createdAt, Instant completedAt) {
    }

    public record Approval(Long id, String task, String artifact, String decision, String reviewer, String comment,
                           Instant decidedAt, String status, String staleReason, Instant staledAt) {
    }

    public record ArtifactSummary(Long id, String name, int version, String label, String task, int attempt, ArtifactOrigin origin,
                                  String adapter, String model, boolean fallback, boolean accepted, boolean invalidated,
                                  boolean current, String contentHash, String inputFingerprint, Instant createdAt) {
    }

    public record Artifact(ArtifactSummary summary, JsonNode content, String markdown, JsonNode agentResult, JsonNode gates,
                           Long inputTokens, Long outputTokens, Double estimatedCostUsd) {
    }

    /**
     * Decision lineage of one artifact version: which exact input versions it was
     * produced from, by which agent and adapter, what the agent decided and why,
     * which gates it passed, which approvals are bound to it, and what consumed it.
     */
    public record Lineage(ArtifactSummary artifact, String agent, String inputFingerprint, List<InputRef> inputs,
                          List<String> consumers, JsonNode agentResult, JsonNode gates, List<Approval> approvals) {
    }

    public record InputRef(String label, String contentHash, Long artifactId) {
    }

    public record Event(long sequence, Instant at, String type, String task, Integer attempt, String agent, String from, String to,
                        String actor, String mode, String model, JsonNode refs, String message, JsonNode details, String hash) {
    }

    public record Capabilities(
            ExecutionMode defaultMode,
            List<ExecutionMode> availableModes,
            String llmModel,
            String llmConfiguration,
            boolean workspaceEnabled,
            String workspaceStatus,
            String testRunner,
            String fallback,
            Map<String, Object> retry,
            Map<String, Object> limits,
            Map<String, Object> policy,
            List<Agent> agents
    ) {
    }

    public record Agent(String key, String version, WorkflowStage stage, String description, List<String> consumes,
                        List<String> produces, List<String> tools, List<String> entryCriteria, List<String> exitCriteria,
                        boolean readsRequirement, boolean deterministicFallback) {
    }

    public record DefinitionValidation(boolean valid, List<GraphValidationResult.GraphError> errors, List<String> topologicalOrder,
                                       List<List<String>> parallelLayers) {
    }
}
