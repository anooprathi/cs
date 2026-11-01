package com.schwab.urlshortener.agentic.api;

import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.agentic.model.SdlcStage;

import java.util.List;

/**
 * What this deployment can actually do right now. {@code availableModes}
 * reflects the live bean wiring, not configuration intent: LLM appears only
 * if an LLM client bean was actually created.
 */
public record AgenticCapabilitiesResponse(
        ExecutionMode defaultMode,
        List<ExecutionMode> availableModes,
        boolean llmEnabled,
        String llmModel,
        int catalogComponentCount,
        List<SdlcStage> stages,
        String notice
) {
}
