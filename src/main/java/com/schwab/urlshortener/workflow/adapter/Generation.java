package com.schwab.urlshortener.workflow.adapter;

import com.schwab.urlshortener.workflow.engine.AgentResult;
import com.schwab.urlshortener.workflow.engine.ArtifactOrigin;

/** Output of one generative step, with what produced it and what it cost. */
public record Generation<T>(T output, ArtifactOrigin origin, String adapter, String model, AgentResult.Usage usage) {
}
