package com.schwab.urlshortener.workflow.artifact;

import java.util.List;

/** Output of the deterministic policy engine in the SECURITY stage. Any BLOCKER violation fails the stage. */
public record PolicyReport(boolean passed, int blockers, int warnings, List<String> rulesEvaluated, List<Violation> violations) {

    public enum Severity {
        BLOCKER,
        WARNING
    }

    public record Violation(String ruleId, Severity severity, String message, String location) {
    }
}
