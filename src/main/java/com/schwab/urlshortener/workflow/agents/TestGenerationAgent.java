package com.schwab.urlshortener.workflow.agents;

import com.schwab.urlshortener.agentic.agent.TestDesignerAgent;
import com.schwab.urlshortener.agentic.model.ImplementationPlan;
import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.TestPlan;
import com.schwab.urlshortener.workflow.WorkflowProperties;
import com.schwab.urlshortener.workflow.adapter.Generation;
import com.schwab.urlshortener.workflow.artifact.TestReport;
import com.schwab.urlshortener.workflow.engine.AgentRequest;
import com.schwab.urlshortener.workflow.engine.AgentResult;
import com.schwab.urlshortener.workflow.engine.GateCriterion;
import com.schwab.urlshortener.workflow.engine.GateCriterion.Result;
import com.schwab.urlshortener.workflow.engine.StageAgent;
import com.schwab.urlshortener.workflow.engine.ToolPolicy;
import com.schwab.urlshortener.workflow.graph.WorkflowStage;
import com.schwab.urlshortener.workflow.workspace.ChangePatch;
import com.schwab.urlshortener.workflow.workspace.WorkspaceManager;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TEST_GENERATION_AND_EXECUTION stage agent: generates a test plan (demo or LLM),
 * then executes it with the configured {@link TestRunner} against the run's
 * worktree, with the change-patch re-applied from its exact diff, so it tests
 * the reviewed patch and nothing else. Produces test-plan and test-report,
 * possibly of different origin.
 */
@Component
public class TestGenerationAgent implements StageAgent {

    private final TestDesignerAgent designer;
    private final AgentStageRunner runner;
    private final TestRunner testRunner;
    private final WorkspaceManager workspaces;
    private final Path repositoryRoot;

    public TestGenerationAgent(TestDesignerAgent designer, AgentStageRunner runner, TestRunner testRunner,
                               WorkspaceManager workspaces, WorkflowProperties properties) {
        this.designer = designer;
        this.runner = runner;
        this.testRunner = testRunner;
        this.workspaces = workspaces;
        this.repositoryRoot = Path.of(properties.repositoryRoot()).toAbsolutePath().normalize();
    }

    @Override
    public String key() {
        return "test-generation-execution";
    }

    /** Includes the runner name: switching runners changes what "executed" means, so prior reports must not be reused. */
    @Override
    public String version() {
        return "2+" + testRunner.name();
    }

    @Override
    public WorkflowStage stage() {
        return WorkflowStage.TEST_GENERATION_AND_EXECUTION;
    }

    @Override
    public String description() {
        return "Generates test cases per acceptance criterion and executes them against the patched workspace with the '"
                + testRunner.name() + "' runner.";
    }

    @Override
    public String intent() {
        return "Prove every acceptance criterion with at least one test case and execute the cases against the patch.";
    }

    @Override
    public List<String> constraints() {
        return List.of("Target existing test classes from the component catalog where possible.",
                "Only the configured test command may run; test cases cannot specify commands.");
    }

    @Override
    public List<String> consumes() {
        return List.of(Artifacts.REQUIREMENT_SPEC, Artifacts.ARCHITECTURE_DESIGN, Artifacts.IMPLEMENTATION_PLAN, Artifacts.CHANGE_PATCH);
    }

    @Override
    public List<String> produces() {
        return List.of(Artifacts.TEST_PLAN, Artifacts.TEST_REPORT);
    }

    @Override
    public boolean supportsDeterministicFallback() {
        return true;
    }

    @Override
    public ToolPolicy toolPolicy() {
        return ToolPolicy.of(ToolPolicy.Tool.READ_ARTIFACTS, ToolPolicy.Tool.READ_COMPONENT_CATALOG, ToolPolicy.Tool.LLM_GENERATE,
                ToolPolicy.Tool.READ_REPOSITORY_FILES, ToolPolicy.Tool.RUN_TEST_COMMAND, ToolPolicy.Tool.WORKSPACE_WRITE);
    }

    @Override
    public List<GateCriterion> entryCriteria() {
        return List.of(new GateCriterion("TST-ENTRY-1", "Upstream implementation-plan has at least one task",
                in -> {
                    int n = Checks.safe(in.input(Artifacts.IMPLEMENTATION_PLAN, ImplementationPlan.class).tasks()).size();
                    return Result.of(n > 0, n + " tasks", "implementation-plan has no tasks");
                }));
    }

    @Override
    public List<GateCriterion> exitCriteria() {
        return List.of(
                new GateCriterion("TST-EXIT-1", "Every acceptance criterion has at least one test case",
                        in -> {
                            Set<String> tested = new HashSet<>();
                            Checks.safe(in.output(Artifacts.TEST_PLAN, TestPlan.class).testCases())
                                    .forEach(tc -> tested.addAll(Checks.safe(tc.acceptanceCriteria())));
                            List<String> untested = Checks.criterionIds(in.input(Artifacts.REQUIREMENT_SPEC, RequirementSpec.class))
                                    .stream().filter(id -> !tested.contains(id)).toList();
                            return Result.of(untested.isEmpty(), "all tested", "untested " + untested);
                        }),
                new GateCriterion("TST-EXIT-2", "At least one test case was executed",
                        in -> {
                            TestReport r = in.output(Artifacts.TEST_REPORT, TestReport.class);
                            return Result.of(r.executed() > 0, r.executed() + " executed (runner " + r.runner() + ", product tests executed: "
                                    + r.productTestsExecuted() + ")", "nothing was executed");
                        }),
                new GateCriterion("TST-EXIT-3", "Every executed test case passed",
                        in -> {
                            TestReport r = in.output(Artifacts.TEST_REPORT, TestReport.class);
                            List<String> failed = r.results().stream().filter(x -> x.status() == TestReport.Status.FAILED)
                                    .map(x -> x.testCaseId() + " (" + x.detail() + ")").toList();
                            return Result.of(failed.isEmpty(), r.passed() + " passed", "failed: " + failed);
                        }));
    }

    @Override
    public AgentResult execute(AgentRequest request) {
        Generation<TestPlan> g = runner.run(designer, request);
        TestPlan plan = g.output();
        ChangePatch patch = request.input(Artifacts.CHANGE_PATCH, ChangePatch.class);
        Path workingTree = repositoryRoot;
        String tree = "repository root (no workspace)";
        if (request.workspace().isPresent() && patch.applied()) {
            request.requireTool(ToolPolicy.Tool.WORKSPACE_WRITE);
            WorkspaceManager.Workspace ws = new WorkspaceManager.Workspace(request.runId(), request.workspace().get(), request.repository().baseCommit());
            workspaces.reapply(ws, patch.unifiedDiff());
            workingTree = ws.directory();
            tree = "isolated workspace with " + Artifacts.CHANGE_PATCH + " " + request.inputRefs().get(Artifacts.CHANGE_PATCH).label() + " applied";
        }
        request.requireTool(testRunner.requiredTool());
        TestReport report = testRunner.run(plan, request.input(Artifacts.REQUIREMENT_SPEC, RequirementSpec.class), workingTree);
        return new AgentResult(AgentResult.Status.COMPLETED,
                "Generated " + Checks.safe(plan.testCases()).size() + " test cases and executed them with '" + testRunner.name()
                        + "': " + report.passed() + " passed, " + report.failed() + " failed, " + report.notExecuted() + " not executed.",
                List.of(new AgentResult.Produced(Artifacts.TEST_PLAN, plan, g.origin(), g.model()),
                        new AgentResult.Produced(Artifacts.TEST_REPORT, report, testRunner.origin(), null)),
                List.of(Checks.evidence("test execution (" + testRunner.name() + ")", report.allExecutedPassed(),
                                report.passed() + "/" + report.executed() + " passed"),
                        Checks.evidence("product test suite executed", report.productTestsExecuted(), report.notice())),
                List.of("Tests ran against: " + tree + "."),
                report.productTestsExecuted() ? List.of() : List.of("No product test was executed (plan verification only)."),
                List.of(Checks.decision("Executed with runner '" + testRunner.name() + "'.",
                                "Configured by app.workflow.test-runner.mode; commands are never taken from the plan."),
                        Checks.decision("Test plan by " + Checks.producedBy(g) + ".", "Execution mode of the run.")),
                null, null, g.usage());
    }
}
