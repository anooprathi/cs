package com.schwab.urlshortener.workflow.engine;

/**
 * What actually produced an artifact. Recorded per artifact (a task can
 * produce two artifacts of different origin, e.g. a model-generated test plan
 * and a deterministic test report) and surfaced in lineage and every API view.
 */
public enum ArtifactOrigin {
    /** A Claude model call (run mode LLM). */
    LLM,
    /** Rule-based stand-in for an LLM-backed step (run mode DETERMINISTIC_DEMO). Not AI analysis. */
    DETERMINISTIC_DEMO,
    /** Deterministic code by design, identical in every mode (policy checks, readiness review, documentation assembly). */
    DETERMINISTIC,
    /** Result of running an external command (the opt-in command test runner). */
    EXTERNAL_COMMAND
}
