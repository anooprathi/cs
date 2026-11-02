package com.schwab.urlshortener.workflow.agents;

import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.TestPlan;
import com.schwab.urlshortener.workflow.artifact.TestReport;
import com.schwab.urlshortener.workflow.engine.ArtifactOrigin;
import com.schwab.urlshortener.workflow.engine.ToolPolicy;
import com.schwab.urlshortener.workflow.workspace.ProcessRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Opt-in runner ({@code app.workflow.test-runner.mode=command}) that really
 * executes the configured test command, e.g.
 * {@code mvn -q test -Dtest={tests} -Djacoco.skip=true}, inside the run's
 * isolated workspace with the patch applied.
 *
 * Safety properties:
 *  - The command comes only from server configuration, never from a request or a model.
 *  - It runs through {@link ProcessRunner}: no shell, hard timeout, bounded output.
 *  - {@code {tests}} is replaced only by simple class names derived from test-plan targets that
 *    exist as files in the working tree AND match {@code [A-Za-z0-9_]+}, so a model-generated
 *    target cannot inject an argument.
 *
 * Result mapping: one command run covers all targeted cases. Exit code 0 marks
 * them PASSED, anything else FAILED. A command without {@code {tests}} runs the
 * whole suite and covers every case.
 */
public class CommandTestRunner implements TestRunner {

    static final String PLACEHOLDER = "{tests}";
    private static final Pattern SIMPLE_NAME = Pattern.compile("[A-Za-z0-9_]+");

    private final List<String> command;
    private final Duration timeout;

    public CommandTestRunner(List<String> command, Duration timeout) {
        if (command.isEmpty()) {
            throw new IllegalStateException("app.workflow.test-runner.mode=command requires app.workflow.test-runner.command");
        }
        this.command = List.copyOf(command);
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return "command";
    }

    @Override
    public ArtifactOrigin origin() {
        return ArtifactOrigin.EXTERNAL_COMMAND;
    }

    @Override
    public ToolPolicy.Tool requiredTool() {
        return ToolPolicy.Tool.RUN_TEST_COMMAND;
    }

    @Override
    public TestReport run(TestPlan plan, RequirementSpec spec, Path workingTree) {
        Set<String> classNames = new LinkedHashSet<>();
        List<TestPlan.TestCase> targeted = new ArrayList<>();
        List<TestPlan.TestCase> untargeted = new ArrayList<>();
        for (TestPlan.TestCase tc : Checks.safe(plan.testCases())) {
            String simple = simpleClassName(tc.targetTestClass(), workingTree);
            if (simple != null) {
                classNames.add(simple);
                targeted.add(tc);
            } else {
                untargeted.add(tc);
            }
        }
        boolean needsTargets = command.stream().anyMatch(a -> a.contains(PLACEHOLDER));
        if (needsTargets && classNames.isEmpty()) {
            List<TestReport.Result> none = Checks.safe(plan.testCases()).stream()
                    .map(tc -> new TestReport.Result(tc.id(), String.valueOf(tc.targetTestClass()), TestReport.Status.NOT_EXECUTED,
                            "No runnable JUnit target."))
                    .toList();
            return report(none, null, null, "Command needs " + PLACEHOLDER + " but the plan has no runnable JUnit targets.");
        }
        List<String> argv = command.stream().map(a -> a.replace(PLACEHOLDER, String.join(",", classNames))).toList();
        ProcessRunner.Result exec = ProcessRunner.run(argv, workingTree, timeout, null);
        TestReport.Status status = exec.succeeded() ? TestReport.Status.PASSED : TestReport.Status.FAILED;
        String detail = exec.exitCode() == null ? exec.error() : "command exit code " + exec.exitCode();

        List<TestReport.Result> results = new ArrayList<>();
        targeted.forEach(tc -> results.add(new TestReport.Result(tc.id(), tc.targetTestClass(), status, detail)));
        for (TestPlan.TestCase tc : untargeted) {
            results.add(needsTargets
                    ? new TestReport.Result(tc.id(), String.valueOf(tc.targetTestClass()), TestReport.Status.NOT_EXECUTED,
                    "Not covered by the targeted command.")
                    : new TestReport.Result(tc.id(), String.valueOf(tc.targetTestClass()), status, detail));
        }
        return report(results, String.join(" ", argv), exec, null);
    }

    private TestReport report(List<TestReport.Result> results, String commandLine, ProcessRunner.Result exec, String extraNotice) {
        int passed = PlanVerificationTestRunner.count(results, TestReport.Status.PASSED);
        int failed = PlanVerificationTestRunner.count(results, TestReport.Status.FAILED);
        String notice = "Executed the configured test command as a child process in the run's workspace."
                + (extraNotice == null ? "" : " " + extraNotice);
        return new TestReport(name(), exec != null, passed + failed, passed, failed,
                PlanVerificationTestRunner.count(results, TestReport.Status.NOT_EXECUTED), List.copyOf(results), commandLine,
                exec == null ? null : exec.exitCode(), exec == null ? null : exec.output(), notice);
    }

    /** "src/test/java/a/b/FooTest.java" → "FooTest", only if the file exists and the name is a plain identifier. */
    static String simpleClassName(String target, Path workingTree) {
        if (target == null || !target.endsWith(".java") || !Files.isRegularFile(workingTree.resolve(target))) {
            return null;
        }
        Path fileName = Path.of(target).getFileName();
        if (fileName == null) {
            return null;
        }
        String file = fileName.toString();
        String name = file.substring(0, file.length() - ".java".length());
        return SIMPLE_NAME.matcher(name).matches() ? name : null;
    }
}
