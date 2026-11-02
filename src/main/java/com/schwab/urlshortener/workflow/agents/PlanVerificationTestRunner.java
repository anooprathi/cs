package com.schwab.urlshortener.workflow.agents;

import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.TestPlan;
import com.schwab.urlshortener.workflow.artifact.TestReport;
import com.schwab.urlshortener.workflow.engine.ArtifactOrigin;
import com.schwab.urlshortener.workflow.engine.ToolPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Default test runner. It verifies the generated test plan against the working
 * tree (the run's patched workspace) and executes no product tests. For each
 * test case it checks that the target test file exists, that every referenced
 * acceptance criterion exists, and that the scenario is non-empty. Command
 * targets (e.g. "mvn clean verify") are reported NOT_EXECUTED.
 *
 * The report states {@code productTestsExecuted=false} and says so in its
 * notice. Release readiness surfaces this as an advisory check, so a passing
 * TEST stage under this runner can't be mistaken for "the product tests passed".
 */
public class PlanVerificationTestRunner implements TestRunner {

    public static final String NOTICE = "Plan verification only: checked each generated test case against the working tree "
            + "(target test file exists, acceptance criteria resolve, scenario present). No product test was compiled or run. "
            + "Set app.workflow.test-runner.mode=command to execute a real test command.";

    @Override
    public String name() {
        return "plan-verification";
    }

    @Override
    public ArtifactOrigin origin() {
        return ArtifactOrigin.DETERMINISTIC;
    }

    @Override
    public ToolPolicy.Tool requiredTool() {
        return ToolPolicy.Tool.READ_REPOSITORY_FILES;
    }

    @Override
    public TestReport run(TestPlan plan, RequirementSpec spec, Path workingTree, List<String> patchTests) {
        Set<String> criteria = Checks.criterionIds(spec);
        List<TestReport.Result> results = new ArrayList<>();
        for (TestPlan.TestCase tc : Checks.safe(plan.testCases())) {
            String target = tc.targetTestClass() == null ? "" : tc.targetTestClass();
            if (!target.endsWith(".java")) {
                results.add(new TestReport.Result(tc.id(), target, TestReport.Status.NOT_EXECUTED,
                        "Command target; plan verification does not run commands."));
                continue;
            }
            List<String> problems = new ArrayList<>();
            if (!Files.isRegularFile(workingTree.resolve(target))) {
                problems.add("target test file not found");
            }
            List<String> unknown = Checks.safe(tc.acceptanceCriteria()).stream().filter(id -> !criteria.contains(id)).toList();
            if (!unknown.isEmpty()) {
                problems.add("unknown criteria " + unknown);
            }
            if (tc.scenario() == null || tc.scenario().isBlank()) {
                problems.add("empty scenario");
            }
            results.add(problems.isEmpty()
                    ? new TestReport.Result(tc.id(), target, TestReport.Status.PASSED, "target exists; criteria resolve")
                    : new TestReport.Result(tc.id(), target, TestReport.Status.FAILED, String.join("; ", problems)));
        }
        int passed = count(results, TestReport.Status.PASSED);
        int failed = count(results, TestReport.Status.FAILED);
        return new TestReport(name(), false, passed + failed, passed, failed, count(results, TestReport.Status.NOT_EXECUTED),
                List.copyOf(results), null, null, null, NOTICE);
    }

    static int count(List<TestReport.Result> results, TestReport.Status status) {
        return (int) results.stream().filter(r -> r.status() == status).count();
    }
}
