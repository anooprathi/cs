package com.schwab.urlshortener.agentic.review;

import com.schwab.urlshortener.agentic.ComponentCatalog;
import com.schwab.urlshortener.agentic.agent.AgentContext;
import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis.ImpactedComponent;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis.ProposedComponent;
import com.schwab.urlshortener.agentic.model.ImplementationPlan;
import com.schwab.urlshortener.agentic.model.ImplementationPlan.PlanTask;
import com.schwab.urlshortener.agentic.model.QualityGateReport;
import com.schwab.urlshortener.agentic.model.QualityGateReport.Finding;
import com.schwab.urlshortener.agentic.model.QualityGateReport.FindingSeverity;
import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.RequirementSpec.AcceptanceCriterion;
import com.schwab.urlshortener.agentic.model.RequirementSpec.Ambiguity;
import com.schwab.urlshortener.agentic.model.RiskAssessment;
import com.schwab.urlshortener.agentic.model.RiskAssessment.Risk;
import com.schwab.urlshortener.agentic.model.TestPlan;
import com.schwab.urlshortener.agentic.model.TestPlan.TestCase;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The REVIEW stage: deterministic consistency and traceability checks over
 * the five generated artifacts. This is the guardrail that makes LLM mode
 * reviewable â€” a model can produce fluent, plausible artifacts that
 * reference a class that doesn't exist, a task that depends on itself, or
 * an acceptance criterion nothing tests. None of those are judgment calls,
 * so none of them are left to a model (or to a reviewer's eye) to catch.
 *
 * It runs identically in every execution mode and is never model-generated.
 * It does NOT judge whether the plan is a good idea â€” that's the human
 * reviewer's job; the gate only guarantees the artifacts are internally
 * consistent and grounded in the real codebase before a human spends time
 * on them.
 *
 * Any BLOCKER fails the gate, and a failed gate blocks approval
 * (SdlcRunService.review).
 */
@Component
public class QualityGateReviewer {

    public static final String RULE_NO_CRITERIA = "requirements.acceptance-criteria-present";
    public static final String RULE_DUPLICATE_ID = "ids.unique";
    public static final String RULE_UNKNOWN_COMPONENT = "impact.component-in-catalog";
    public static final String RULE_UNKNOWN_CRITERION = "traceability.criterion-exists";
    public static final String RULE_DANGLING_DEPENDENCY = "plan.dependency-exists";
    public static final String RULE_CYCLE = "plan.acyclic";
    public static final String RULE_CRITERION_NO_TASK = "coverage.criterion-has-task";
    public static final String RULE_CRITERION_NO_TEST = "coverage.criterion-has-test";
    public static final String RULE_TASK_UNKNOWN_COMPONENT = "plan.component-known";
    public static final String RULE_COMPONENT_NO_TASK = "coverage.component-has-task";
    public static final String RULE_HIGH_RISK_UNMITIGATED = "risks.high-risk-mitigated";
    public static final String RULE_AMBIGUITY_NO_ASSUMPTION = "requirements.ambiguity-has-assumption";
    public static final String RULE_EXECUTION_MODE = "provenance.execution-mode";

    public QualityGateReport review(AgentContext context) {
        RequirementSpec spec = context.requirementSpec();
        ImpactAnalysis impact = context.impactAnalysis();
        ImplementationPlan plan = context.implementationPlan();
        TestPlan tests = context.testPlan();
        RiskAssessment risks = context.riskAssessment();
        ComponentCatalog catalog = context.catalog();
        List<Finding> findings = new ArrayList<>();

        List<AcceptanceCriterion> criteria = nullSafe(spec.acceptanceCriteria());
        List<PlanTask> tasks = nullSafe(plan.tasks());
        List<TestCase> testCases = nullSafe(tests.testCases());
        List<ImpactedComponent> impacted = nullSafe(impact.impactedComponents());
        List<Risk> riskList = nullSafe(risks.risks());
        List<Ambiguity> ambiguities = nullSafe(spec.ambiguities());

        // --- ids ---
        if (criteria.isEmpty()) {
            findings.add(blocker(RULE_NO_CRITERIA, "No acceptance criteria were produced; nothing in this run is testable."));
        }
        checkUnique(findings, "acceptance criterion", criteria, AcceptanceCriterion::id);
        checkUnique(findings, "task", tasks, PlanTask::id);
        checkUnique(findings, "test case", testCases, TestCase::id);
        checkUnique(findings, "risk", riskList, Risk::id);
        Set<String> criterionIds = criteria.stream().map(AcceptanceCriterion::id).filter(Objects::nonNull).collect(Collectors.toSet());

        // --- impact grounded in the real codebase ---
        for (ImpactedComponent ic : impacted) {
            if (!catalog.contains(ic.component())) {
                findings.add(blocker(RULE_UNKNOWN_COMPONENT, "Impacted component '" + ic.component()
                        + "' does not exist in this codebase's component catalog."));
            }
            reportUnknownCriteria(findings, "impact entry " + ic.component(), ic.relatedCriteria(), criterionIds);
        }
        Set<String> knownComponents = new HashSet<>();
        catalog.all().forEach(c -> knownComponents.add(c.name()));
        nullSafe(impact.newComponents()).stream().map(ProposedComponent::name).forEach(knownComponents::add);

        // --- plan graph ---
        Set<String> taskIds = tasks.stream().map(PlanTask::id).filter(Objects::nonNull).collect(Collectors.toSet());
        for (PlanTask t : tasks) {
            for (String dep : nullSafe(t.dependsOn())) {
                if (!taskIds.contains(dep)) {
                    findings.add(blocker(RULE_DANGLING_DEPENDENCY, "Task " + t.id() + " depends on unknown task '" + dep + "'."));
                }
            }
            for (String component : nullSafe(t.components())) {
                if (!knownComponents.contains(component)) {
                    findings.add(warning(RULE_TASK_UNKNOWN_COMPONENT, "Task " + t.id() + " changes '" + component
                            + "', which is neither in the catalog nor a proposed new component."));
                }
            }
            reportUnknownCriteria(findings, "task " + t.id(), t.acceptanceCriteria(), criterionIds);
        }
        findCycle(tasks).ifPresent(cycle -> findings.add(blocker(RULE_CYCLE,
                "Task dependencies form a cycle: " + String.join(" -> ", cycle) + ".")));

        // --- tests ---
        for (TestCase tc : testCases) {
            reportUnknownCriteria(findings, "test case " + tc.id(), tc.acceptanceCriteria(), criterionIds);
        }

        // --- coverage ---
        Set<String> coveredByTasks = tasks.stream().flatMap(t -> nullSafe(t.acceptanceCriteria()).stream()).collect(Collectors.toSet());
        Set<String> coveredByTests = testCases.stream().flatMap(t -> nullSafe(t.acceptanceCriteria()).stream()).collect(Collectors.toSet());
        for (String ac : sorted(criterionIds)) {
            if (!coveredByTasks.contains(ac)) {
                findings.add(blocker(RULE_CRITERION_NO_TASK, ac + " is not covered by any implementation task."));
            }
            if (!coveredByTests.contains(ac)) {
                findings.add(blocker(RULE_CRITERION_NO_TEST, ac + " is not verified by any test case."));
            }
        }
        Set<String> componentsWithTasks = tasks.stream().flatMap(t -> nullSafe(t.components()).stream()).collect(Collectors.toSet());
        for (ImpactedComponent ic : impacted) {
            if (!componentsWithTasks.contains(ic.component())) {
                findings.add(warning(RULE_COMPONENT_NO_TASK, "Impacted component '" + ic.component() + "' has no task that changes it."));
            }
        }

        // --- risks and ambiguities ---
        for (Risk r : riskList) {
            if (r.severity() == RiskAssessment.Severity.HIGH && isBlank(r.mitigation())) {
                findings.add(blocker(RULE_HIGH_RISK_UNMITIGATED, "HIGH risk " + r.id() + " has no mitigation."));
            }
        }
        for (Ambiguity a : ambiguities) {
            if (isBlank(a.assumption())) {
                findings.add(warning(RULE_AMBIGUITY_NO_ASSUMPTION, "Ambiguity " + a.id() + " has no stated assumption to proceed on."));
            }
        }

        // --- provenance ---
        findings.add(new Finding(FindingSeverity.INFO, RULE_EXECUTION_MODE, context.mode() == ExecutionMode.LLM
                ? "Stages 1-5 were generated by a language model. This gate checks consistency and grounding only; "
                + "it cannot tell whether the content is correct or wise. Human review is required."
                : "Stages 1-5 were produced by deterministic rule-based templates (DETERMINISTIC_DEMO), not by a "
                + "language model. Content reflects keyword rules, not understanding of the requirement."));

        int blockers = (int) findings.stream().filter(f -> f.severity() == FindingSeverity.BLOCKER).count();
        int warnings = (int) findings.stream().filter(f -> f.severity() == FindingSeverity.WARNING).count();
        QualityGateReport.Metrics metrics = new QualityGateReport.Metrics(
                criterionIds.size(),
                (int) criterionIds.stream().filter(coveredByTasks::contains).count(),
                (int) criterionIds.stream().filter(coveredByTests::contains).count(),
                impacted.size(), tasks.size(), testCases.size(), riskList.size(), ambiguities.size());
        return new QualityGateReport(blockers == 0, blockers, warnings, findings, metrics);
    }

    private static <T> void checkUnique(List<Finding> findings, String kind, List<T> items, Function<T, String> id) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (T item : items) {
            String value = id.apply(item);
            if (isBlank(value)) {
                findings.add(blocker(RULE_DUPLICATE_ID, "A " + kind + " has no id."));
            } else {
                counts.merge(value, 1, Integer::sum);
            }
        }
        counts.forEach((value, count) -> {
            if (count > 1) {
                findings.add(blocker(RULE_DUPLICATE_ID, "Duplicate " + kind + " id '" + value + "' (" + count + " occurrences)."));
            }
        });
    }

    private static void reportUnknownCriteria(List<Finding> findings, String where, List<String> refs, Set<String> criterionIds) {
        for (String ref : nullSafe(refs)) {
            if (!criterionIds.contains(ref)) {
                findings.add(blocker(RULE_UNKNOWN_CRITERION, where + " references unknown acceptance criterion '" + ref + "'."));
            }
        }
    }

    /**
     * Iterative three-colour DFS over the task graph (edges task â†’ dependency),
     * ignoring dangling ids (reported separately). Returns the first cycle
     * found as a closed path, e.g. [T-1, T-2, T-1].
     */
    static Optional<List<String>> findCycle(List<PlanTask> tasks) {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (PlanTask t : tasks) {
            if (t.id() != null) {
                edges.putIfAbsent(t.id(), nullSafe(t.dependsOn()));
            }
        }
        Map<String, Integer> state = new HashMap<>(); // absent = unvisited, 1 = on stack, 2 = done
        for (String start : edges.keySet()) {
            if (state.containsKey(start)) {
                continue;
            }
            Deque<String> path = new ArrayDeque<>();
            Deque<Iterator<String>> iterators = new ArrayDeque<>();
            path.push(start);
            iterators.push(edges.get(start).iterator());
            state.put(start, 1);
            while (!path.isEmpty()) {
                Iterator<String> it = iterators.peek();
                if (it.hasNext()) {
                    String next = it.next();
                    if (!edges.containsKey(next)) {
                        continue;
                    }
                    Integer s = state.get(next);
                    if (s == null) {
                        state.put(next, 1);
                        path.push(next);
                        iterators.push(edges.get(next).iterator());
                    } else if (s == 1) {
                        List<String> stack = new ArrayList<>(path);
                        Collections.reverse(stack);
                        List<String> cycle = new ArrayList<>(stack.subList(stack.indexOf(next), stack.size()));
                        cycle.add(next);
                        return Optional.of(cycle);
                    }
                } else {
                    state.put(path.pop(), 2);
                    iterators.pop();
                }
            }
        }
        return Optional.empty();
    }

    private static Finding blocker(String rule, String message) {
        return new Finding(FindingSeverity.BLOCKER, rule, message);
    }

    private static Finding warning(String rule, String message) {
        return new Finding(FindingSeverity.WARNING, rule, message);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static List<String> sorted(Collection<String> ids) {
        return new ArrayList<>(new LinkedHashSet<>(ids.stream().sorted(QualityGateReviewer::compareIds).toList()));
    }

    /** "AC-2" before "AC-10": compare the numeric suffix when both ids have one. */
    static int compareIds(String a, String b) {
        int ia = a.lastIndexOf('-');
        int ib = b.lastIndexOf('-');
        if (ia > 0 && ib > 0 && a.substring(0, ia).equals(b.substring(0, ib))) {
            try {
                return Integer.compare(Integer.parseInt(a.substring(ia + 1)), Integer.parseInt(b.substring(ib + 1)));
            } catch (NumberFormatException ignored) {
                // fall through to lexical order
            }
        }
        return a.compareTo(b);
    }
}
