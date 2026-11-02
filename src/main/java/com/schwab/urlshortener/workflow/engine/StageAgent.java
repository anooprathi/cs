package com.schwab.urlshortener.workflow.engine;

import com.schwab.urlshortener.workflow.graph.WorkflowStage;

import java.util.List;

/**
 * A stage-specific agent: the work behind one workflow task. One implementation
 * per SDLC stage, registered as a Spring bean and referenced by {@link #key()}
 * from graph nodes.
 *
 * <b>Input</b> ({@link AgentRequest}): task intent and constraints, the acceptance
 * criteria it will be judged by (its exit gate), relevant repository context,
 * the approved upstream artifacts it declared, its allowed tools, and its limits.
 * <b>Output</b> ({@link AgentResult}): summary, produced artifacts, validation
 * evidence, assumptions, risks, decisions with rationale, and a completion or
 * failure status.
 *
 * The contract the engine relies on:
 * <ul>
 *   <li>{@link #consumes()}/{@link #produces()} are complete and stable: graph validation checks them,
 *       the request only serves declared inputs, and the engine fails the task if the produced set
 *       differs from the declared one.</li>
 *   <li>{@link #version()} changes whenever the agent's logic changes. It's part of the input
 *       fingerprint, so a logic change invalidates reuse of prior outputs.</li>
 *   <li>{@link #execute} persists nothing and uses only the tools in {@link #toolPolicy()}. The request
 *       enforces that at runtime. The engine owns state, artifacts, approvals, lineage, and audit.</li>
 * </ul>
 */
public interface StageAgent {

    String key();

    String version();

    WorkflowStage stage();

    String description();

    /** What this task is for, as given to the agent (and to the model in LLM mode). */
    String intent();

    /** Hard rules the agent must respect, as given to the agent. */
    List<String> constraints();

    List<String> consumes();

    List<String> produces();

    /**
     * Whether the agent reads the raw requirement text. Only such agents carry
     * the requirement hash in their input fingerprint. Every other agent sees the
     * requirement only through the versioned requirement-spec, so a requirement
     * edit that leaves the spec unchanged doesn't force them to re-run.
     */
    default boolean readsRequirement() {
        return false;
    }

    List<GateCriterion> entryCriteria();

    /** The agent's acceptance criteria: evaluated as its exit gate, and shown to it in the request. */
    List<GateCriterion> exitCriteria();

    ToolPolicy toolPolicy();

    /**
     * Whether a failed LLM-backed execution may fall back to this agent's
     * deterministic path (the defined fallback). Fallback output is always labeled
     * and requires human approval.
     */
    default boolean supportsDeterministicFallback() {
        return false;
    }

    AgentResult execute(AgentRequest request);

    /**
     * Undoes this agent's local side effects after a failed attempt, or when the
     * run is cancelled. Returns a description of what was rolled back, or empty if
     * the agent has no side effects (the default). Only agents with supported local
     * side effects (the isolated workspace) implement it. Nothing here claims to
     * reverse external effects.
     */
    default java.util.Optional<String> compensate(AgentRequest request) {
        return java.util.Optional.empty();
    }
}
