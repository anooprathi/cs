package com.schwab.urlshortener.agentic.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.schwab.urlshortener.agentic.SdlcArtifact;
import com.schwab.urlshortener.agentic.model.ArtifactSource;
import com.schwab.urlshortener.agentic.model.SdlcStage;

import java.time.Instant;

/** One stage's artifact: the typed content as JSON plus its rendered Markdown and provenance. */
public record SdlcArtifactResponse(
        Long runId,
        SdlcStage stage,
        ArtifactSource source,
        SdlcArtifact.Status status,
        String model,
        Long durationMs,
        Long inputTokens,
        Long outputTokens,
        String errorMessage,
        JsonNode content,
        String markdown,
        Instant createdAt
) {
}
