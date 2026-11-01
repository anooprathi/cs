package com.schwab.urlshortener.agentic.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * TESTS stage output: test cases traced back to acceptance criteria. The
 * quality gate requires every acceptance criterion to have at least one.
 */
public record TestPlan(
        @JsonPropertyDescription("Test cases, ids TC-1, TC-2, ...")
        List<TestCase> testCases
) {

    /** Mirrors the test layers this repository actually uses (see README §1 "Test"). */
    public enum TestLevel {
        UNIT,
        WEB_SLICE,
        INTEGRATION,
        MANUAL
    }

    public record TestCase(
            @JsonPropertyDescription("TC-n")
            String id,
            String title,
            TestLevel level,
            @JsonPropertyDescription("Existing or new JUnit test class the case belongs in")
            String targetTestClass,
            @JsonPropertyDescription("Given/when/then description of the scenario")
            String scenario,
            @JsonPropertyDescription("AC-n ids this test verifies")
            List<String> acceptanceCriteria
    ) {
    }
}
