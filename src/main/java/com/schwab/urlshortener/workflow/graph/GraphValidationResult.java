package com.schwab.urlshortener.workflow.graph;

import java.util.List;
import java.util.Map;

/**
 * Outcome of validating a {@link WorkflowDefinition}. {@code topologicalOrder}
 * and {@code parallelLayers} are only populated for a valid graph. Each layer is
 * a set of tasks whose dependencies all lie in earlier layers, so the tasks in
 * one layer can run concurrently.
 */
public record GraphValidationResult(
        boolean valid,
        List<GraphError> errors,
        List<String> topologicalOrder,
        List<List<String>> parallelLayers,
        Map<String, String> producers
) {

    public enum ErrorCode {
        EMPTY_GRAPH,
        BLANK_TASK_KEY,
        DUPLICATE_TASK,
        UNKNOWN_AGENT,
        SELF_DEPENDENCY,
        UNKNOWN_DEPENDENCY,
        DUPLICATE_DEPENDENCY,
        CYCLE,
        DUPLICATE_PRODUCER,
        MISSING_INPUT,
        INPUT_NOT_UPSTREAM
    }

    public record GraphError(ErrorCode code, String task, String message) {
    }
}
