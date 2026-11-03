package com.schwab.urlshortener.workflow.artifact;

import java.util.List;

/**
 * Result of the test-execution half of the TEST stage. {@code productTestsExecuted}
 * says plainly whether any real test ran. The plan-verification runner sets it to
 * false. The command runner sets it to true only when the fresh Surefire reports
 * show at least one executed (not skipped) test; a zero exit code alone proves nothing.
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
        String notice,
        /** What JUnit actually ran, read from the fresh Surefire XML reports. Null when no test command ran. */
        JUnitTotals junit
) {

    /** Totals and per-class results from the Surefire reports written by this run's test command. */
    public record JUnitTotals(int tests, int failures, int errors, int skipped, List<ClassResult> classes) {
        public int executed() {
            return tests - skipped;
        }
    }

    public record ClassResult(String testClass, int tests, int failures, int errors, int skipped) {
    }

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
