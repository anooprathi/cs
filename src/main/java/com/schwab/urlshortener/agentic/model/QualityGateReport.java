package com.schwab.urlshortener.agentic.model;

import java.util.List;

/**
 * REVIEW stage output — produced by deterministic code
 * ({@link com.schwab.urlshortener.agentic.review.QualityGateReviewer}) in
 * every execution mode, never by a model. {@code passed} is false iff
 * there is at least one BLOCKER; a failed gate blocks human approval.
 */
public record QualityGateReport(
        boolean passed,
        int blockerCount,
        int warningCount,
        List<Finding> findings,
        Metrics metrics
) {

    public enum FindingSeverity {
        BLOCKER,
        WARNING,
        INFO
    }

    public record Finding(FindingSeverity severity, String rule, String message) {
    }

    public record Metrics(
            int acceptanceCriteria,
            int criteriaCoveredByTasks,
            int criteriaCoveredByTests,
            int impactedComponents,
            int tasks,
            int testCases,
            int risks,
            int openAmbiguities
    ) {
    }
}
