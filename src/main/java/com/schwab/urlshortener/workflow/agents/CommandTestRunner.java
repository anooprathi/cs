package com.schwab.urlshortener.workflow.agents;

import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.TestPlan;
import com.schwab.urlshortener.workflow.artifact.TestReport;
import com.schwab.urlshortener.workflow.engine.ArtifactOrigin;
import com.schwab.urlshortener.workflow.engine.ToolPolicy;
import com.schwab.urlshortener.workflow.workspace.ProcessRunner;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The COMMAND test execution: really executes the configured test command, e.g.
 * {@code mvn -B -o test -Dtest={tests} -Djacoco.skip=true}, inside the run's
 * isolated workspace with the patch applied. {@code {tests}} covers the plan's
 * existing test classes AND every test class the patch adds or changes, so a
 * generated test is actually run, not just listed.
 *
 * <p><b>Evidence, not exit codes.</b> A zero exit code does not prove a test ran: an
 * empty test class, a class whose tests are all skipped, or a selection that
 * matched nothing can all exit 0. So before the command runs, stale Surefire
 * reports are deleted from {@value #REPORTS_DIR}. Afterwards, the fresh
 * {@code TEST-*.xml} reports are the evidence. A targeted class PASSES only if
 * the command exited 0 AND its own report exists, shows at least one test, has no
 * failures or errors, and didn't skip every test. {@code productTestsExecuted} is
 * true only when the reports show at least one executed test.
 *
 * <p>Safety properties:
 * <ul>
 *   <li>The command comes only from server configuration, never from a request or a model.</li>
 *   <li>It runs through {@link ProcessRunner}: no shell, hard timeout, bounded output.</li>
 *   <li>{@code {tests}} is replaced only by simple class names derived from test-plan targets that exist as
 *       files in the working tree AND match {@code [A-Za-z0-9_]+}, so a model-generated target cannot inject
 *       an argument.</li>
 *   <li>Reports are parsed with DTDs and external entities disabled: their content comes from code under test.</li>
 * </ul>
 *
 * <p>Result rows: each patch test class gets its own row ({@code patch:<ClassName>}); each plan case
 * targeting a class takes that class's verdict. A command without {@code {tests}} runs the whole suite: every
 * case then takes the overall verdict, which needs at least one executed test and no failures.
 */
public class CommandTestRunner implements TestRunner {

    static final String PLACEHOLDER = "{tests}";
    /** Maven Surefire's default report directory, relative to the working tree. */
    static final String REPORTS_DIR = "target/surefire-reports";
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
    public TestReport run(TestPlan plan, RequirementSpec spec, Path workingTree, List<String> patchTests) {
        Set<String> classNames = new LinkedHashSet<>();
        List<String> patchClasses = new ArrayList<>();
        for (String target : Checks.safe(patchTests)) {
            String simple = simpleClassName(target, workingTree);
            if (simple != null && classNames.add(simple)) {
                patchClasses.add(simple);
            }
        }
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
            return report(none, null, null, null, "Command needs " + PLACEHOLDER + " but the plan has no runnable JUnit targets.");
        }

        Path reports = workingTree.resolve(REPORTS_DIR);
        deleteStaleReports(reports);
        List<String> argv = command.stream().map(a -> a.replace(PLACEHOLDER, String.join(",", classNames))).toList();
        ProcessRunner.Result exec = ProcessRunner.run(argv, workingTree, timeout, null);
        Reports read = readReports(reports);
        Map<String, TestReport.ClassResult> bySimpleName = read.classes();
        TestReport.JUnitTotals totals = totals(bySimpleName);
        String commandProblem = exec.succeeded() ? null
                : exec.exitCode() == null ? exec.error() : "command exit code " + exec.exitCode();

        List<TestReport.Result> results = new ArrayList<>();
        if (needsTargets) {
            Map<String, Verdict> verdicts = new LinkedHashMap<>();
            classNames.forEach(c -> verdicts.put(c, verdict(bySimpleName.get(c), commandProblem)));
            patchClasses.forEach(c -> results.add(verdicts.get(c).row("patch:" + c, c, " (test class in the patch)")));
            targeted.forEach(tc -> results.add(verdicts.get(simpleClassName(tc.targetTestClass(), workingTree))
                    .row(tc.id(), tc.targetTestClass(), "")));
            untargeted.forEach(tc -> results.add(new TestReport.Result(tc.id(), String.valueOf(tc.targetTestClass()),
                    TestReport.Status.NOT_EXECUTED, "Not covered by the targeted command.")));
        } else {
            Verdict overall = overallVerdict(totals, commandProblem);
            Checks.safe(plan.testCases()).forEach(tc -> results.add(overall.row(tc.id(), String.valueOf(tc.targetTestClass()), "")));
        }
        return report(results, String.join(" ", argv), exec, totals, read.unreadable().isEmpty() ? null
                : "Unreadable reports, treated as not run: " + read.unreadable() + ".");
    }

    /** Parsed reports by simple class name, plus any report file that could not be parsed (never counted as evidence). */
    record Reports(Map<String, TestReport.ClassResult> classes, List<String> unreadable) {
    }

    record Verdict(boolean passed, String detail) {
        TestReport.Result row(String id, String target, String suffix) {
            return new TestReport.Result(id, target, passed ? TestReport.Status.PASSED : TestReport.Status.FAILED, detail + suffix);
        }
    }

    /** One targeted class: its own fresh report must exist and show executed, passing tests, and the command must have succeeded. */
    static Verdict verdict(TestReport.ClassResult r, String commandProblem) {
        if (r == null) {
            return new Verdict(false, "no Surefire report: the class did not run" + (commandProblem == null ? "" : "; " + commandProblem));
        }
        String counts = r.tests() + " tests, " + r.failures() + " failures, " + r.errors() + " errors, " + r.skipped() + " skipped";
        if (r.tests() == 0) {
            return new Verdict(false, "0 tests ran in this class (" + counts + ")");
        }
        if (r.skipped() >= r.tests()) {
            return new Verdict(false, "every test was skipped (" + counts + ")");
        }
        if (r.failures() + r.errors() > 0) {
            return new Verdict(false, counts);
        }
        if (commandProblem != null) {
            return new Verdict(false, commandProblem + " although the report shows " + counts);
        }
        return new Verdict(true, counts);
    }

    private static Verdict overallVerdict(TestReport.JUnitTotals t, String commandProblem) {
        String counts = t.tests() + " tests in " + t.classes().size() + " classes, " + t.failures() + " failures, " + t.errors()
                + " errors, " + t.skipped() + " skipped";
        boolean passed = commandProblem == null && t.executed() > 0 && t.failures() + t.errors() == 0;
        return new Verdict(passed, (t.executed() == 0 ? "no test executed: " : "") + counts + (commandProblem == null ? "" : "; " + commandProblem));
    }

    private static TestReport.JUnitTotals totals(Map<String, TestReport.ClassResult> classes) {
        int tests = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        for (TestReport.ClassResult r : classes.values()) {
            tests += r.tests();
            failures += r.failures();
            errors += r.errors();
            skipped += r.skipped();
        }
        return new TestReport.JUnitTotals(tests, failures, errors, skipped, List.copyOf(classes.values()));
    }

    /** Deletes TEST-*.xml left by earlier runs, so only reports this run writes can count as evidence. */
    static void deleteStaleReports(Path reports) {
        if (!Files.isDirectory(reports)) {
            return;
        }
        try (DirectoryStream<Path> stale = Files.newDirectoryStream(reports, "TEST-*.xml")) {
            for (Path p : stale) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot clear stale test reports in " + reports, e);
        }
    }

    /** Parses every TEST-*.xml testsuite, keyed by simple class name. An unreadable report counts as missing and is listed. */
    static Reports readReports(Path reports) {
        Map<String, TestReport.ClassResult> results = new LinkedHashMap<>();
        List<String> unreadable = new ArrayList<>();
        if (!Files.isDirectory(reports)) {
            return new Reports(results, unreadable);
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (javax.xml.parsers.ParserConfigurationException e) {
            throw new IllegalStateException("XML parser cannot be hardened", e);
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(reports, "TEST-*.xml")) {
            for (Path file : files) {
                try {
                    Element suite = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
                    String fqcn = suite.getAttribute("name");
                    String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
                    results.put(simple, new TestReport.ClassResult(fqcn, intAttr(suite, "tests"), intAttr(suite, "failures"),
                            intAttr(suite, "errors"), intAttr(suite, "skipped")));
                } catch (org.xml.sax.SAXException | IOException | javax.xml.parsers.ParserConfigurationException | NumberFormatException e) {
                    // a malformed or hostile report is not evidence: the class counts as not having run, and the file is listed
                    unreadable.add(String.valueOf(file.getFileName()) + " (" + e.getClass().getSimpleName() + ")");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read test reports in " + reports, e);
        }
        return new Reports(results, unreadable);
    }

    private static int intAttr(Element e, String name) {
        String v = e.getAttribute(name);
        return v.isBlank() ? 0 : Integer.parseInt(v.trim());
    }

    private TestReport report(List<TestReport.Result> results, String commandLine, ProcessRunner.Result exec,
                              TestReport.JUnitTotals junit, String extraNotice) {
        int passed = PlanVerificationTestRunner.count(results, TestReport.Status.PASSED);
        int failed = PlanVerificationTestRunner.count(results, TestReport.Status.FAILED);
        boolean executedTests = junit != null && junit.executed() > 0;
        String notice = "Executed the configured test command as a child process in the run's workspace; verdicts come from the "
                + "fresh Surefire reports, not the exit code alone."
                + (junit == null ? "" : " JUnit: " + junit.tests() + " tests, " + junit.failures() + " failures, " + junit.errors()
                + " errors, " + junit.skipped() + " skipped.")
                + (extraNotice == null ? "" : " " + extraNotice);
        return new TestReport(name(), executedTests, passed + failed, passed, failed,
                PlanVerificationTestRunner.count(results, TestReport.Status.NOT_EXECUTED), List.copyOf(results), commandLine,
                exec == null ? null : exec.exitCode(), exec == null ? null : exec.output(), notice, junit);
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
