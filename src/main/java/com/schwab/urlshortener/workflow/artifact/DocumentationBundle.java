package com.schwab.urlshortener.workflow.artifact;

import java.util.List;

/**
 * DOCUMENTATION stage output: release notes plus a requirement → criterion →
 * task → component traceability matrix, assembled deterministically from
 * upstream artifacts. {@code executionDisclosure} states what produced the
 * upstream analysis. The exit gate requires it.
 */
public record DocumentationBundle(
        String title,
        String executionDisclosure,
        String summary,
        List<TraceRow> traceability,
        List<ComponentNote> components,
        List<String> assumptions,
        List<String> outOfScope
) {

    public record TraceRow(String criterionId, String requirementId, String requirement, List<String> tasks, List<String> components) {
    }

    public record ComponentNote(String component, String change, String reason) {
    }
}
