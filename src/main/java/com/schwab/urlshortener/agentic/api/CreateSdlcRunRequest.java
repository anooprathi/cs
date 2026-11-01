package com.schwab.urlshortener.agentic.api;

import com.schwab.urlshortener.agentic.model.ExecutionMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Starts a run. {@code mode} is optional (server default applies, normally
 * DETERMINISTIC_DEMO). The 10,000-character cap bounds both storage and,
 * in LLM mode, the per-run token cost an operator can trigger.
 */
public record CreateSdlcRunRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 10_000) String requirement,
        ExecutionMode mode
) {
}
