package com.schwab.urlshortener.agentic.agent;

import com.schwab.urlshortener.agentic.model.SdlcStage;

import java.util.List;

/**
 * One generative stage of the pipeline. Each agent carries BOTH of its
 * execution paths side by side, so the two modes can't drift into
 * producing different artifact shapes:
 *  - {@link #runDeterministic}: the rule-based DETERMINISTIC_DEMO stand-in.
 *  - {@link #systemPrompt}/{@link #userPrompt}: what LLM mode sends; the
 *    orchestrator passes {@link #outputType} as the structured-output schema.
 *
 * Agents never call the LLM themselves and never persist anything — the
 * orchestrator owns mode selection, timing, failure handling, and storage,
 * which is what makes the "which mode produced this" stamp trustworthy.
 */
public interface SdlcAgent<T> {

    SdlcStage stage();

    /** Stages whose artifacts this agent reads; used to order execution. */
    List<SdlcStage> dependsOn();

    Class<T> outputType();

    T runDeterministic(AgentContext context);

    String systemPrompt();

    String userPrompt(AgentContext context);
}
