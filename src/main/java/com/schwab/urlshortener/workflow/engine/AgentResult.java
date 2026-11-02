package com.schwab.urlshortener.workflow.engine;

import java.util.List;

/**
 * Structured output of a stage agent.
 *
 * @param status             COMPLETED, or FAILED when the agent could not finish (the engine then takes the defined
 *                           fallback path or fails the task. It never treats a FAILED result's artifacts as usable)
 * @param summary            one-paragraph account of what was done
 * @param artifacts          produced artifacts, each with its own origin
 * @param validationEvidence checks the agent itself ran on its output (the exit gate is evaluated independently)
 * @param assumptions        assumptions the output depends on
 * @param risks              risks the agent identified in its own output
 * @param decisions          decisions made and the rationale for each
 * @param reviewReason       non-null to require human approval of this output (e.g. ambiguities, fallback, protected paths)
 * @param failureReason      why the agent failed, when status is FAILED
 * @param usage              model usage for LLM-backed steps; zero otherwise
 */
public record AgentResult(
        Status status,
        String summary,
        List<Produced> artifacts,
        List<Evidence> validationEvidence,
        List<String> assumptions,
        List<String> risks,
        List<Decision> decisions,
        String reviewReason,
        String failureReason,
        Usage usage
) {

    public enum Status {
        COMPLETED,
        FAILED
    }

    public record Produced(String name, Object content, ArtifactOrigin origin, String model) {
    }

    public record Evidence(String check, boolean passed, String detail) {
    }

    public record Decision(String decision, String rationale) {
    }

    public record Usage(long inputTokens, long outputTokens, double estimatedCostUsd, int modelCalls) {
        public static final Usage NONE = new Usage(0, 0, 0.0, 0);

        public Usage plus(Usage other) {
            return new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                    estimatedCostUsd + other.estimatedCostUsd, modelCalls + other.modelCalls);
        }
    }

    public AgentResult {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        validationEvidence = validationEvidence == null ? List.of() : List.copyOf(validationEvidence);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        risks = risks == null ? List.of() : List.copyOf(risks);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        usage = usage == null ? Usage.NONE : usage;
    }

    public static AgentResult failed(String reason) {
        return new AgentResult(Status.FAILED, reason, List.of(), List.of(), List.of(), List.of(), List.of(), null, reason, Usage.NONE);
    }

    /** The result envelope without artifact content, which is what gets stored alongside each artifact as its rationale record. */
    public Envelope envelope() {
        return new Envelope(status, summary, artifacts.stream().map(Produced::name).toList(), validationEvidence, assumptions,
                risks, decisions, reviewReason, failureReason, usage);
    }

    public record Envelope(Status status, String summary, List<String> artifactNames, List<Evidence> validationEvidence,
                           List<String> assumptions, List<String> risks, List<Decision> decisions, String reviewReason,
                           String failureReason, Usage usage) {
    }
}
