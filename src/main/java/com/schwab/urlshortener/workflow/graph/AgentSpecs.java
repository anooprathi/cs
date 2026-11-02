package com.schwab.urlshortener.workflow.graph;

import java.util.List;
import java.util.Optional;

/**
 * What graph validation needs to know about a stage agent: its stage and its
 * artifact contract. Implemented by AgentRegistry. Kept separate so the
 * validator can be tested against hand-built specs.
 */
public interface AgentSpecs {

    record AgentSpec(String key, WorkflowStage stage, List<String> consumes, List<String> produces) {
    }

    Optional<AgentSpec> spec(String agentKey);
}
