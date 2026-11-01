package com.schwab.urlshortener.agentic.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * PLAN stage output: tasks with explicit dependencies. The quality gate
 * verifies the dependency graph is a DAG (no cycles, no dangling ids) and
 * that every acceptance criterion is covered by at least one task.
 */
public record ImplementationPlan(
        @JsonPropertyDescription("Ordered tasks, ids T-1, T-2, ...")
        List<PlanTask> tasks
) {

    public record PlanTask(
            @JsonPropertyDescription("T-n")
            String id,
            String title,
            String description,
            @JsonPropertyDescription("Ids of tasks that must finish first; must not form a cycle")
            List<String> dependsOn,
            @JsonPropertyDescription("Catalog component names or proposed new component names this task changes")
            List<String> components,
            @JsonPropertyDescription("AC-n ids this task contributes to")
            List<String> acceptanceCriteria
    ) {
    }
}
