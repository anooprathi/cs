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
import com.schwab.urlshortener.util.SecretPatterns;
import com.schwab.urlshortener.workflow.artifact.InterfaceContract;
import com.schwab.urlshortener.workflow.workspace.ChangePatch;
import com.schwab.urlshortener.workflow.workspace.ProcessRunner;
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
    private final ChangePatch cleanPatch = new ChangePatch("s", ChangePatch.Kind.DOCUMENT, "change-record", "abc", true, true, List.of(), List.of(),
            "diff --git a/x b/x", "1 file", List.of(), List.of(), List.of(), null);

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
        PolicyReport r = evaluate(AgenticFixtures.REQUIREMENT + "\nRepro: X-API-Key: usk_SYNTHETICtestKEY000000000000", ctx.impactAnalysis(),
                ctx.riskAssessment(), new ChangePatch("s", ChangePatch.Kind.CODE, "model", "b", true, true, List.of(), List.of(), "+password = hunter22", "", List.of(),
                        List.of(), List.of(), null));

        assertThat(r.passed()).isFalse();
        assertThat(r.violations()).filteredOn(v -> v.ruleId().equals("POL-SECRET")).extracting(PolicyReport.Violation::location)
                .contains("requirement", "change-patch");
        assertThat(r.violations().toString()).doesNotContain("testKEY000000000000");
    }

    @Test
    void outOfScopePayment_isABlocker() {
        PolicyReport r = evaluate("Tenants must be charged; charge the card on file monthly.", ctx.impactAnalysis(), ctx.riskAssessment(), cleanPatch);

        assertThat(r.violations()).anyMatch(v -> v.ruleId().equals("POL-SCOPE") && v.severity() == PolicyReport.Severity.BLOCKER);
    }

    @Test
    void securityLayerChange_withoutAHighMitigatedRisk_isABlocker() {
        ImpactAnalysis design = new ImpactAnalysis(List.of(new ImpactAnalysis.ImpactedComponent("SecurityConfig",
                ImpactAnalysis.ChangeType.MODIFY, "r", List.of())), List.of(), List.of(), List.of());

        PolicyReport none = evaluate("x", design, new RiskAssessment(List.of()), cleanPatch);
        PolicyReport acknowledged = evaluate("x", design, new RiskAssessment(List.of(new RiskAssessment.Risk("R-1", "auth",
                RiskAssessment.Severity.HIGH, "add matcher tests", List.of("SecurityConfig")))), cleanPatch);

        assertThat(none.violations()).anyMatch(v -> v.ruleId().equals("POL-AUTHZ"));
        assertThat(acknowledged.violations()).noneMatch(v -> v.ruleId().equals("POL-AUTHZ"));
    }

    @Test
    void personalDataLogging_andProtectedPaths_areWarnings() {
        ChangePatch protectedPatch = new ChangePatch("s", ChangePatch.Kind.CODE, "model", "b", true, true, List.of(), List.of(), "d", "", List.of(),
                List.of("pom.xml"), List.of(), null);
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
        TestReport r = new PlanVerificationTestRunner().run(ctx.testPlan(), ctx.requirementSpec(), Path.of("."), List.of());

        assertThat(r.productTestsExecuted()).isFalse();
        assertThat(r.failed()).isZero();
        assertThat(r.passed()).isGreaterThan(0);
        assertThat(r.notExecuted()).isEqualTo(1); // the "mvn clean verify" regression case
        assertThat(r.notice()).contains("No product test was compiled or run");
    }

    @Test
    void planVerification_failsCasesWhoseTargetDoesNotExist(@TempDir Path empty) {
        TestReport r = new PlanVerificationTestRunner().run(ctx.testPlan(), ctx.requirementSpec(), empty, List.of());

        assertThat(r.failed()).isGreaterThan(0);
        assertThat(r.results()).anyMatch(x -> x.detail().contains("target test file not found"));
    }

    // ---------------------------------------------------------------- command test runner: evidence, not exit codes

    /**
     * A stand-in "test command": a single-file Java program that writes Surefire-format reports, exactly as told,
     * for the classes named in its first argument (the substituted {tests}), then exits with the given code.
     * Spec arguments: Class=tests/failures/errors/skipped. A class without a spec gets no report.
     */
    private static final String REPORT_WRITER = """
            import java.nio.file.*;
            import java.util.*;
            public class ReportWriter {
                public static void main(String[] a) throws Exception {
                    Set<String> selected = new HashSet<>(Arrays.asList(a[0].split(",")));
                    Path dir = Path.of("target/surefire-reports");
                    Files.createDirectories(dir);
                    for (int i = 2; i < a.length; i++) {
                        String[] kv = a[i].split("=");
                        String[] n = kv[1].split("/");
                        if (selected.contains(kv[0])) {
                            Files.writeString(dir.resolve("TEST-p." + kv[0] + ".xml"), "<?xml version=\\"1.0\\"?><testsuite name=\\"p." + kv[0]
                                    + "\\" tests=\\"" + n[0] + "\\" failures=\\"" + n[1] + "\\" errors=\\"" + n[2] + "\\" skipped=\\"" + n[3] + "\\"/>");
                        }
                    }
                    System.exit(Integer.parseInt(a[1]));
                }
            }
            """;

    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    private static Path workingTree(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/test/java/p"));
        Files.writeString(dir.resolve("src/test/java/p/FooTest.java"), "class FooTest {}");
        Files.writeString(dir.resolve("src/test/java/p/NewTest.java"), "class NewTest {}");
        Files.writeString(dir.resolve("ReportWriter.java"), REPORT_WRITER);
        return dir;
    }

    private static final TestPlan FOO_PLAN = new TestPlan(List.of(
            new TestPlan.TestCase("TC-1", "t", TestPlan.TestLevel.UNIT, "src/test/java/p/FooTest.java", "s", List.of("AC-1")),
            new TestPlan.TestCase("TC-2", "t", TestPlan.TestLevel.UNIT, "src/test/java/p/Missing.java", "s", List.of("AC-1"))));

    private TestReport runWriter(Path dir, List<String> patchTests, String exitCode, String... specs) {
        List<String> argv = new java.util.ArrayList<>(List.of(JAVA, dir.resolve("ReportWriter.java").toString(), "{tests}", exitCode));
        argv.addAll(List.of(specs));
        return new CommandTestRunner(argv, Duration.ofSeconds(120)).run(FOO_PLAN, ctx.requirementSpec(), dir, patchTests);
    }

    private static TestReport.Result row(TestReport r, String id) {
        return r.results().stream().filter(x -> x.testCaseId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void commandRunner_passesOnlyOnFreshReportsShowingExecutedTests(@TempDir Path dir) throws Exception {
        TestReport r = runWriter(workingTree(dir), List.of("src/test/java/p/NewTest.java", "src/test/java/p/Gone.java"), "0",
                "FooTest=3/0/0/0", "NewTest=2/0/0/1");

        assertThat(r.command()).contains("NewTest,FooTest");
        assertThat(r.results()).extracting(TestReport.Result::testCaseId).containsExactly("patch:NewTest", "TC-1", "TC-2");
        assertThat(row(r, "patch:NewTest").status()).isEqualTo(TestReport.Status.PASSED);
        assertThat(row(r, "TC-1").status()).isEqualTo(TestReport.Status.PASSED);
        assertThat(row(r, "TC-1").detail()).contains("3 tests, 0 failures");
        assertThat(row(r, "TC-2").status()).isEqualTo(TestReport.Status.NOT_EXECUTED);
        assertThat(r.productTestsExecuted()).isTrue();
        assertThat(r.junit().tests()).isEqualTo(5);
        assertThat(r.junit().executed()).isEqualTo(4);
        assertThat(r.allExecutedPassed()).isTrue();
    }

    /** The reviewer's probe: an empty test class, Maven exits 0 with "Tests run: 0". That is not a pass. */
    @Test
    void commandRunner_emptyTestClass_withExitCodeZero_isAFailure_andNoProductTestExecuted(@TempDir Path dir) throws Exception {
        TestReport r = runWriter(workingTree(dir), List.of(), "0", "FooTest=0/0/0/0");

        assertThat(r.exitCode()).isZero();
        assertThat(row(r, "TC-1").status()).isEqualTo(TestReport.Status.FAILED);
        assertThat(row(r, "TC-1").detail()).contains("0 tests ran");
        assertThat(r.productTestsExecuted()).isFalse();
        assertThat(r.passed()).isZero();
        assertThat(r.allExecutedPassed()).isFalse();
    }

    @Test
    void commandRunner_missingReportForATargetedClass_isAFailure(@TempDir Path dir) throws Exception {
        TestReport r = runWriter(workingTree(dir), List.of("src/test/java/p/NewTest.java"), "0", "FooTest=3/0/0/0");

        assertThat(row(r, "patch:NewTest").status()).isEqualTo(TestReport.Status.FAILED);
        assertThat(row(r, "patch:NewTest").detail()).contains("no Surefire report");
        assertThat(row(r, "TC-1").status()).isEqualTo(TestReport.Status.PASSED);
        assertThat(r.allExecutedPassed()).isFalse();
    }

    @Test
    void commandRunner_allTestsSkipped_isAFailure(@TempDir Path dir) throws Exception {
        TestReport r = runWriter(workingTree(dir), List.of(), "0", "FooTest=2/0/0/2");

        assertThat(row(r, "TC-1").status()).isEqualTo(TestReport.Status.FAILED);
        assertThat(row(r, "TC-1").detail()).contains("every test was skipped");
        assertThat(r.productTestsExecuted()).isFalse();
    }

    @Test
    void commandRunner_failuresInTheReport_failEvenWithExitCodeZero(@TempDir Path dir) throws Exception {
        TestReport r = runWriter(workingTree(dir), List.of(), "0", "FooTest=3/1/0/0");

        assertThat(row(r, "TC-1").status()).isEqualTo(TestReport.Status.FAILED);
        assertThat(row(r, "TC-1").detail()).contains("1 failures");
    }

    @Test
    void commandRunner_nonZeroExit_failsEvenWhenTheReportLooksGreen(@TempDir Path dir) throws Exception {
        TestReport r = runWriter(workingTree(dir), List.of(), "1", "FooTest=3/0/0/0");

        assertThat(row(r, "TC-1").status()).isEqualTo(TestReport.Status.FAILED);
        assertThat(row(r, "TC-1").detail()).contains("command exit code 1");
    }

    @Test
    void commandRunner_staleReportsFromAnEarlierRun_areNotEvidence(@TempDir Path dir) throws Exception {
        Path tree = workingTree(dir);
        Files.createDirectories(tree.resolve("target/surefire-reports"));
        Files.writeString(tree.resolve("target/surefire-reports/TEST-p.FooTest.xml"),
                "<?xml version=\"1.0\"?><testsuite name=\"p.FooTest\" tests=\"9\" failures=\"0\" errors=\"0\" skipped=\"0\"/>");

        TestReport r = runWriter(tree, List.of(), "0");   // this run writes no report at all

        assertThat(row(r, "TC-1").status()).isEqualTo(TestReport.Status.FAILED);
        assertThat(row(r, "TC-1").detail()).contains("no Surefire report");
        assertThat(r.productTestsExecuted()).isFalse();
    }

    @Test
    void commandRunner_reportWithADoctype_isRejectedNotExpanded(@TempDir Path dir) throws Exception {
        Path reports = Files.createDirectories(dir.resolve("target/surefire-reports"));
        Files.writeString(reports.resolve("TEST-p.EvilTest.xml"), "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<testsuite name=\"p.EvilTest\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">&e;</testsuite>");

        CommandTestRunner.Reports read = CommandTestRunner.readReports(reports);
        assertThat(read.classes()).as("a malformed or hostile report counts as missing").isEmpty();
        assertThat(read.unreadable()).singleElement().satisfies(f -> assertThat(f).startsWith("TEST-p.EvilTest.xml"));
    }

    @Test
    void commandRunner_wholeSuiteCommand_needsAtLeastOneExecutedTest(@TempDir Path dir) throws Exception {
        Path tree = workingTree(dir);
        List<String> argv = List.of(JAVA, tree.resolve("ReportWriter.java").toString(), "FooTest", "0", "FooTest=0/0/0/0");

        TestReport r = new CommandTestRunner(argv, Duration.ofSeconds(120)).run(FOO_PLAN, ctx.requirementSpec(), tree, List.of());

        assertThat(r.results()).allSatisfy(x -> {
            assertThat(x.status()).isEqualTo(TestReport.Status.FAILED);
            assertThat(x.detail()).contains("no test executed");
        });
    }

    @Test
    void contractGate_requiresAnExactContractForCodePatches() {
        ChangePatch code = new ChangePatch("s", ChangePatch.Kind.CODE, "recipe:x", "b", true, true,
                List.of(new ChangePatch.FileSummary("src/main/java/a/entity/E.java", ChangePatch.Action.MODIFIED, 1, "r"),
                        new ChangePatch.FileSummary("src/main/java/a/dto/D.java", ChangePatch.Action.MODIFIED, 1, "r")),
                List.of(), "d", "", List.of(), List.of(), List.of(), null);
        InterfaceContract.ApiChange api = new InterfaceContract.ApiChange("POST", "/x", "REQUEST_FIELD_ADDED", "f");

        assertThat(ImplementationAgent.contractGate(code, new InterfaceContract(InterfaceContract.Source.RECIPE, "s", List.of(api),
                List.of(new InterfaceContract.SchemaChange("t", "c", "COLUMN_ADDED", "ALTER TABLE t ADD COLUMN c INT")))).passed()).isTrue();
        assertThat(ImplementationAgent.contractGate(code, new InterfaceContract(InterfaceContract.Source.MODEL, "s", List.of(), List.of()))
                .detail()).contains("no schema change", "no API change");
        assertThat(ImplementationAgent.contractGate(code, new InterfaceContract(InterfaceContract.Source.DERIVED_PROPOSAL, "s", List.of(api),
                List.of(new InterfaceContract.SchemaChange("t", "c", "COLUMN_ADDED", null)))).detail())
                .contains("only a derived contract", "has no DDL");
        assertThat(ImplementationAgent.contractGate(cleanPatch, new InterfaceContract(InterfaceContract.Source.DERIVED_PROPOSAL, "s",
                List.of(), List.of())).passed()).isTrue();
    }

    @Test
    void processRunner_resolvesWindowsLaunchers(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("mvn.cmd"), "");
        Files.writeString(dir.resolve("git.exe"), "");
        String path = dir + java.io.File.pathSeparator + "\0bad";

        assertThat(ProcessRunner.resolveExecutable("mvn", path, true)).isEqualTo(dir.resolve("mvn.cmd").toString());
        assertThat(ProcessRunner.resolveExecutable("git", path, true)).isEqualTo("git");
        assertThat(ProcessRunner.resolveExecutable("mvn", path, false)).isEqualTo("mvn");
        assertThat(ProcessRunner.resolveExecutable("/usr/bin/mvn", path, true)).isEqualTo("/usr/bin/mvn");
        assertThat(ProcessRunner.resolveExecutable("nothing", path, true)).isEqualTo("nothing");
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

        TestReport r = new CommandTestRunner(List.of(java, sleeper.toString()), Duration.ofMillis(1500)).run(plan, ctx.requirementSpec(), dir, List.of());

        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.results().get(0).detail()).contains("timed out");
    }
}
