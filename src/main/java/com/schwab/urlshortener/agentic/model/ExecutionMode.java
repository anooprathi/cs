package com.schwab.urlshortener.agentic.model;

/**
 * How a run's generative stages (everything except REVIEW) were produced.
 * Chosen per run and stamped on the run, on every artifact (see
 * {@link ArtifactSource}), in every API response, and as a banner at the
 * top of every rendered Markdown document — so a reader can never mistake
 * one for the other.
 */
public enum ExecutionMode {

    /**
     * Rule-based, template-driven stand-ins for each agent: keyword
     * classification, a vague-term lexicon, keyword matching against the
     * component catalog, layer-ordered task generation, rule-based risks.
     * No network, no cost, same input always gives the same output. This
     * demonstrates the orchestration mechanics and guardrails — it is not
     * AI analysis, and nothing in this codebase presents it as such.
     */
    DETERMINISTIC_DEMO,

    /**
     * Each generative stage is one call to a Claude model through the
     * official Anthropic Java SDK, constrained by structured outputs to the
     * stage's artifact schema. Only available when app.agentic.llm.enabled=true;
     * requesting it otherwise is refused with 503, never silently served by
     * DETERMINISTIC_DEMO instead.
     */
    LLM
}
