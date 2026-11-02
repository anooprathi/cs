package com.schwab.urlshortener.workflow.graph;

/**
 * The SDLC stages a workflow task can belong to. A stage is a label on an
 * executor ("what kind of work is this"), not a position in the pipeline:
 * execution order comes only from the dependency graph, so two tasks of
 * different stages can run in parallel and a custom graph can omit stages.
 */
public enum WorkflowStage {
    REQUIREMENT_ANALYSIS,
    ARCHITECTURE_DESIGN,
    IMPLEMENTATION,
    TEST_GENERATION_AND_EXECUTION,
    SECURITY_POLICY_VALIDATION,
    DOCUMENTATION,
    RELEASE_READINESS
}
