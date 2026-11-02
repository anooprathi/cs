package com.schwab.urlshortener.workflow.artifact;

import java.util.List;

/**
 * Result of the test-execution half of the TEST stage. {@code productTestsExecuted}
 * says plainly whether any real test suite ran. The default plan-verification
 * runner sets it to false, and only the opt-in command runner can set it to true.
 */
public record TestReport(
        String runner,
        boolean productTestsExecuted,
        int executed,
        int passed,
        int failed,
        int notExecuted,
        List<Result> results,
        String command,
        Integer exitCode,
        String outputTail,
        String notice
) {

    public enum Status {
        PASSED,
        FAILED,
        NOT_EXECUTED
    }

    public record Result(String testCaseId, String target, Status status, String detail) {
    }

    public boolean allExecutedPassed() {
        return executed > 0 && failed == 0;
    }
}
