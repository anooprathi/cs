package com.schwab.urlshortener.workflow.agents;

/** Artifact names exchanged between workflow stages. Graph validation checks producers and consumers by these names. */
public final class Artifacts {

    public static final String REQUIREMENT_SPEC = "requirement-spec";
    public static final String ARCHITECTURE_DESIGN = "architecture-design";
    public static final String IMPLEMENTATION_PLAN = "implementation-plan";
    public static final String CHANGE_PATCH = "change-patch";
    public static final String TEST_PLAN = "test-plan";
    public static final String TEST_REPORT = "test-report";
    public static final String RISK_ASSESSMENT = "risk-assessment";
    public static final String POLICY_REPORT = "policy-report";
    public static final String DOCUMENTATION = "documentation";
    public static final String READINESS_REPORT = "readiness-report";

    private Artifacts() {
    }
}
