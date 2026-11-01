package com.schwab.urlshortener.agentic.model;

/**
 * The fixed stages of the agentic SDLC pipeline, in the order their
 * artifacts are presented. Execution order is NOT derived from this enum's
 * ordinal — each agent declares its own dependencies and
 * {@link com.schwab.urlshortener.agentic.SdlcOrchestrator} topologically
 * sorts them at startup — so reordering constants here can only change
 * presentation, never silently change what runs before what.
 *
 * REVIEW is the one stage with no agent: it is the deterministic quality
 * gate, run by the orchestrator itself in every execution mode.
 */
public enum SdlcStage {
    REQUIREMENTS,
    IMPACT,
    PLAN,
    TESTS,
    RISKS,
    REVIEW
}
