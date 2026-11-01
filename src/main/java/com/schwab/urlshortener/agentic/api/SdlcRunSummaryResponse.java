package com.schwab.urlshortener.agentic.api;

import com.schwab.urlshortener.agentic.SdlcRun;
import com.schwab.urlshortener.agentic.model.ExecutionMode;

import java.time.Instant;

/** List-view shape for a run: no per-stage detail, so listing never loads artifacts. */
public record SdlcRunSummaryResponse(
        Long id,
        String title,
        ExecutionMode executionMode,
        String model,
        SdlcRun.Status status,
        Boolean qualityGatePassed,
        Integer blockerCount,
        Instant createdAt
) {
    public static SdlcRunSummaryResponse from(SdlcRun run) {
        return new SdlcRunSummaryResponse(run.getId(), run.getTitle(), run.getExecutionMode(), run.getModel(),
                run.getStatus(), run.getQualityGatePassed(), run.getBlockerCount(), run.getCreatedAt());
    }
}
