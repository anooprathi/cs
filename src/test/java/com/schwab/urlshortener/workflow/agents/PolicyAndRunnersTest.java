package com.schwab.urlshortener.workflow.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.agentic.AgenticFixtures;
import com.schwab.urlshortener.agentic.agent.AgentContext;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis;
import com.schwab.urlshortener.agentic.model.RiskAssessment;
import com.schwab.urlshortener.agentic.model.TestPlan;
import com.schwab.urlshortener.workflow.WorkflowProperties;
import com.schwab.urlshortener.workflow.artifact.PolicyReport;
import com.schwab.urlshortener.workflow.artifact.TestReport;
import com.schwab.urlshortener.workflow.audit.SecretPatterns;
import com.schwab.urlshortener.workflow.workspace.ChangePatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic policy rules, secret redaction, and both test runners (the command runner really executes a process). */
class PolicyAndRunnersTest {

    private final AgentContext ctx = AgenticFixtures.runDeterministicPipeline(AgenticFixtures.TITLE, AgenticFixtures.REQUIREMENT);
    private final PolicyEngine engine = new PolicyEngine(AgenticFixtures.catalog(), new ObjectMapper(),
            new WorkflowProperties(0, null, null, null, null, null, null, null, null, null, null));
    private final ChangePatch cleanPatch = new ChangePatch("s", "abc", true, true, List.of(), "diff --git a/x b/x", "1 file", List.of(),
            List.of(), List.of(), null);

    private PolicyReport evaluate(String requirement, ImpactAnalysis design, RiskAssessment risks, ChangePatch patch) {
        return engine.evaluate(requirement, ctx.requirementSpec(), design, ctx.implementationPlan(), risks, patch);
    }

    @Test
    void cleanInputs_passWithOnlyWarnings() {
        PolicyReport r = evaluate(AgenticFixtures.REQUIREMENT, ctx.impactAnalysis(), ctx.riskAssessment(), cleanPatch);

        assertThat(r.passed()).isTrue();
        assertThat(r.blockers()).isZero();
        assertThat(r.violations()).allMatch(v -> v.severity() == PolicyReport.Severity.WARNING);
        assertThat(r.violations()).anyMatch(v -> v.ruleId().equals("POL-TENANT"));
    }

    @Test
    void secretInRequirementOrPatch_isABlocker_andIsMasked() {
        PolicyReport r = evaluate(AgenticFixtures.REQUIREMENT + "\nRepro: X-API-Key: usk_9MFV5byEX-ypg32y7C91EVeLEA", ctx.impactAnalysis(),
                ctx.riskAssessment(), new ChangePatch("s", "b", true, true, List.of(), "+password = hunter22", "", List.of(), List.of(), List.of(), null));

        assertThat(r.passed()).isFalse();
        assertThat(r.violations()).filteredOn(v -> v.ruleId().equals("POL-SECRET")).extracting(PolicyReport.Violation::location)
                .contains("requirement", "change-patch");
        assertThat(r.violations().toString()).doesNotContain("ypg32y7C91EVeLEA");
    }

    @Test
    void outOfScopePayment_isABlocker() {
        PolicyReport r = evaluate("Tenants must be charged; charge the card on file monthly.", ctx.impactAnalysis(), ctx.riskAssessment(), cleanPatch);

        assertThat(r.violations()).anyMatch(v -> v.ruleId().equals("POL-SCOPE") && v.severity() == PolicyReport.Severity.BLOCKER);
    }

    @Test
    void securityLayerChange_withoutAHighMitigatedRisk_isABlocker() {
        ImpactAnalysis design = new ImpactAnalysis(List.of(new ImpactAnalysis.ImpactedComponent("SecurityConfig",
                ImpactAnalysis.ChangeType.MODIFY, "r", List.of())), List.of(), List.of());

        PolicyReport none = evaluate("x", design, new RiskAssessment(List.of()), cleanPatch);
        PolicyReport acknowledged = evaluate("x", design, new RiskAssessment(List.of(new RiskAssessment.Risk("R-1", "auth",
                RiskAssessment.Severity.HIGH, "add matcher tests", List.of("SecurityConfig")))), cleanPatch);

        assertThat(none.violations()).anyMatch(v -> v.ruleId().equals("POL-AUTHZ"));
        assertThat(acknowledged.violations()).noneMatch(v -> v.ruleId().equals("POL-AUTHZ"));
    }

    @Test
    void personalDataLogging_andProtectedPaths_areWarnings() {
        ChangePatch protectedPatch = new ChangePatch("s", "b", true, true, List.of(), "d", "", List.of(), List.of("pom.xml"), List.of(), null);
        PolicyReport r = evaluate("Analytics must store the IP address of every visitor.", ctx.impactAnalysis(), ctx.riskAssessment(), protectedPatch);

        assertThat(r.violations()).anyMatch(v -> v.ruleId().equals("POL-PII") && v.severity() == PolicyReport.Severity.WARNING);
        assertThat(r.violations()).anyMatch(v -> v.ruleId().equals("POL-PROTECTED"));
    }

    @Test
    void redaction_replacesEveryKnownSecretShape() {
        String text = "a usk_abcdefghijklmnopqrstuvwx b sk-ant-api03-abcdefghijklmnop c AKIAABCDEFGHIJKLMNOP d password: s3cret!! "
                + "e Bearer abcdefghijklmnopqrstuvwxyz012345";

        String redacted = SecretPatterns.redact(text);

        assertThat(redacted).doesNotContain("usk_abc", "sk-ant-api03", "AKIAABCD", "s3cret", "abcdefghijklmnopqrstuvwxyz012345");
        assertThat(redacted).contains("[REDACTED:tenant API key]", "[REDACTED:Anthropic API key]", "[REDACTED:AWS access key]");
        assertThat(SecretPatterns.redact(null)).isNull();
    }

    @Test
    void planVerification_checksTargetsInTheWorkingTree_andRunsNoProductTests() {
        TestReport r = new PlanVerificationTestRunner().run(ctx.testPlan(), ctx.requirementSpec(), Path.of("."));

        assertThat(r.productTestsExecuted()).isFalse();
        assertThat(r.failed()).isZero();
        assertThat(r.passed()).isGreaterThan(0);
        assertThat(r.notExecuted()).isEqualTo(1); // the "mvn clean verify" regression case
        assertThat(r.notice()).contains("No product test was compiled or run");
    }

    @Test
    void planVerification_failsCasesWhoseTargetDoesNotExist(@TempDir Path empty) {
        TestReport r = new PlanVerificationTestRunner().run(ctx.testPlan(), ctx.requirementSpec(), empty);

        assertThat(r.failed()).isGreaterThan(0);
        assertThat(r.results()).anyMatch(x -> x.detail().contains("target test file not found"));
    }

    @Test
    void commandRunner_reallyExecutes_andMapsExitCodes(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/test/java"));
        Files.writeString(dir.resolve("src/test/java/FooTest.java"), "class FooTest {}");
        TestPlan plan = new TestPlan(List.of(
                new TestPlan.TestCase("TC-1", "t", TestPlan.TestLevel.UNIT, "src/test/java/FooTest.java", "s", List.of("AC-1")),
                new TestPlan.TestCase("TC-2", "t", TestPlan.TestLevel.UNIT, "src/test/java/Missing.java", "s", List.of("AC-1"))));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        TestReport pass = new CommandTestRunner(List.of(java, "-version"), Duration.ofSeconds(60)).run(plan, ctx.requirementSpec(), dir);
        TestReport targeted = new CommandTestRunner(List.of(java, "-Dtests={tests}", "-version"), Duration.ofSeconds(60))
                .run(plan, ctx.requirementSpec(), dir);
        TestReport fail = new CommandTestRunner(List.of(java, "-no-such-flag"), Duration.ofSeconds(60)).run(plan, ctx.requirementSpec(), dir);

        assertThat(pass.productTestsExecuted()).isTrue();
        assertThat(pass.exitCode()).isZero();
        assertThat(pass.failed()).isZero();
        assertThat(targeted.command()).contains("-Dtests=FooTest");
        assertThat(targeted.results()).filteredOn(r -> r.testCaseId().equals("TC-2")).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(TestReport.Status.NOT_EXECUTED));
        assertThat(fail.exitCode()).isNotZero();
        assertThat(fail.failed()).isEqualTo(2);
    }

    @Test
    void commandRunner_timesOut(@TempDir Path dir) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path sleeper = dir.resolve("Sleep.java");
        try {
            Files.writeString(sleeper, "public class Sleep { public static void main(String[] a) throws Exception { Thread.sleep(30000); } }");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        TestPlan plan = new TestPlan(List.of(new TestPlan.TestCase("TC-1", "t", TestPlan.TestLevel.UNIT, "mvn verify", "s", List.of())));

        TestReport r = new CommandTestRunner(List.of(java, sleeper.toString()), Duration.ofMillis(1500)).run(plan, ctx.requirementSpec(), dir);

        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.results().get(0).detail()).contains("timed out");
    }
}
