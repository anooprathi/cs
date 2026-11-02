package com.schwab.urlshortener.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The summary's JUnit-totals extraction (the sections themselves are asserted in WorkflowApiIntegrationTest#fullRun_throughTheApi). */
class WorkflowReportsTest {

    @Test
    void junitTotals_takesTheLastSurefireTotalsLine_notAPerClassLine() {
        String output = """
                [INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.1 s -- in a.FooTest
                [INFO] Results:
                [INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
                [INFO] Tests run: 9, Failures: 1, Errors: 0, Skipped: 2, Time elapsed: 1 s -- in a.BarTest
                [ERROR] Tests run: 13, Failures: 1, Errors: 0, Skipped: 2
                [INFO] BUILD FAILURE
                """;

        assertThat(WorkflowReports.junitTotals(output)).hasValue("Tests run: 13, Failures: 1, Errors: 0, Skipped: 2");
        assertThat(WorkflowReports.junitTotals("no maven here")).isEmpty();
        assertThat(WorkflowReports.junitTotals(null)).isEmpty();
    }
}
