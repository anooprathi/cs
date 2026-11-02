package com.schwab.urlshortener.workflow.scenario;

import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis;
import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.workflow.WorkflowReports;
import com.schwab.urlshortener.workflow.WorkflowService;
import com.schwab.urlshortener.workflow.api.WorkflowRequests;
import com.schwab.urlshortener.workflow.api.WorkflowViews;
import com.schwab.urlshortener.workflow.engine.RunStatus;
import com.schwab.urlshortener.workflow.engine.TaskState;
import com.schwab.urlshortener.workflow.engine.WorkflowEngine;
import com.schwab.urlshortener.workflow.engine.WorkflowStore;
import com.schwab.urlshortener.workflow.metrics.WorkflowMetricsService;
import com.schwab.urlshortener.workflow.persistence.WorkflowEvent;
import com.schwab.urlshortener.workflow.persistence.WorkflowEvent.Type;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Three runnable end-to-end scenarios that drive the real workflow service, the
 * same code paths the API uses. Each step is recorded, and the evidence checks
 * are computed from the durable audit log, not asserted by the script.
 *
 * Human approvals here are made by a SCRIPTED reviewer named
 * {@value #REVIEWER}. They go through exactly the same version-bound decision
 * API a person would use. In real use, approvals come from people through
 * POST /api/v1/workflows/runs/{id}/tasks/{task}/decision.
 *
 * Mode: whatever the caller chooses. DETERMINISTIC_DEMO (the default) needs no
 * credentials. The fault injection used by the greenfield scenario exists only
 * in DETERMINISTIC_DEMO, so in LLM mode that step is skipped and reported as such.
 */
@Service
public class ScenarioService {

    public static final String REVIEWER = "scenario-reviewer (scripted)";
    public static final List<String> NAMES = List.of("greenfield", "brownfield", "ambiguous");

    public record Step(String action, String outcome) {
    }

    public record Check(String name, boolean passed, String detail) {
    }

    public record Report(String scenario, String description, Long runId, ExecutionMode mode, RunStatus finalStatus,
                         List<Step> steps, List<Check> checks, boolean allChecksPassed, String summaryMarkdown,
                         String timelineMarkdown) {
        public String markdown() {
            StringBuilder sb = new StringBuilder("# Scenario: ").append(scenario).append("\n\n").append(description).append("\n\n")
                    .append("Run **").append(runId).append("**, mode **").append(mode).append("**, final status **").append(finalStatus)
                    .append("**. Approvals in this scenario were made by the scripted reviewer `").append(REVIEWER)
                    .append("` through the same version-bound decision API a person uses.\n\n## Steps\n\n| # | Action | Outcome |\n|---:|---|---|\n");
            for (int i = 0; i < steps.size(); i++) {
                sb.append("| ").append(i + 1).append(" | ").append(steps.get(i).action().replace("|", "/")).append(" | ")
                        .append(steps.get(i).outcome().replace("|", "/")).append(" |\n");
            }
            sb.append("\n## Validation evidence (computed from the audit log)\n\n| Check | Result | Detail |\n|---|---|---|\n");
            checks.forEach(c -> sb.append("| ").append(c.name()).append(" | ").append(c.passed() ? "PASS" : "**FAIL**").append(" | ")
                    .append(c.detail().replace("|", "/").replace("\n", " ")).append(" |\n"));
            sb.append("\n").append(summaryMarkdown).append("\n").append(timelineMarkdown);
            return sb.toString();
        }
    }

    private final WorkflowService service;
    private final WorkflowReports reports;
    private final WorkflowStore store;
    private final WorkflowEngine engine;
    private final WorkflowMetricsService metrics;

    public ScenarioService(WorkflowService service, WorkflowReports reports, WorkflowStore store, WorkflowEngine engine,
                           WorkflowMetricsService metrics) {
        this.service = service;
        this.reports = reports;
        this.store = store;
        this.engine = engine;
        this.metrics = metrics;
    }

    public Report run(String name, ExecutionMode mode) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "greenfield" -> greenfield(mode);
            case "brownfield" -> brownfield(mode);
            case "ambiguous" -> ambiguous(mode);
            default -> throw new IllegalArgumentException("Unknown scenario '" + name + "'; choose one of " + NAMES);
        };
    }

    // ------------------------------------------------------------------ 1. greenfield

    static final String GREENFIELD_REQUIREMENT = String.join("\n",
            "Tenants must be able to set an optional maximum click limit when creating a short link.",
            "When the click limit is reached, the redirect must return 410 Gone.",
            "A click limit below 1 must be rejected with 400 Bad Request.",
            "The stats endpoint must show the remaining clicks for each link.");

    private Report greenfield(ExecutionMode mode) {
        List<Step> steps = new ArrayList<>();
        boolean demo = mode == ExecutionMode.DETERMINISTIC_DEMO;
        WorkflowViews.Run run = service.create(new WorkflowRequests.CreateRun("Greenfield: click-limited short links", GREENFIELD_REQUIREMENT,
                mode, null, demo ? Map.of("tests", 1) : null));
        Long id = run.id();
        steps.add(new Step("Submit a clear requirement (4 statements)" + (demo ? " with one injected transient fault on 'tests'" : ""),
                "Run " + id + " CREATED with " + run.tasks().size() + " tasks; parallel layers " + run.parallelLayers()));
        service.start(id);
        run = await(id);
        steps.add(new Step("Start the run", "Status " + run.status() + "; requirement normalized with "
                + spec(id).acceptanceCriteria().size() + " acceptance criteria and " + spec(id).ambiguities().size() + " ambiguities"));
        run = approveAll(id, steps, "Release readiness checklist reviewed");
        WorkflowViews.Task patchTask = task(run, "implementation");
        if (run.status() == RunStatus.SUCCEEDED && run.workspace() != null && patchTask.outputs().containsKey("change-patch")) {
            int patchVersion = version(patchTask.outputs().get("change-patch"));
            run = service.promote(id, new WorkflowRequests.Promote(REVIEWER, "Promote approved patch to a local review branch.", patchVersion, true));
            steps.add(new Step("Promote the approved patch (explicit human action, bound to change-patch@v" + patchVersion + ")",
                    "Local branch " + run.promotion().branch() + " in the sandbox clone; nothing pushed or merged"));
        }
        // Upstream change with NO semantic effect: reformat the requirement as bullets.
        String reformatted = GREENFIELD_REQUIREMENT.lines().map(l -> "- " + l).collect(Collectors.joining("\n"));
        service.amendRequirement(id, new WorkflowRequests.AmendRequirement(reformatted, "Reformat the requirement as a bulleted list (no content change).", REVIEWER));
        service.resume(id);
        run = await(id);
        long reusedAfter = countEvents(id, Type.TASK_REUSED);
        steps.add(new Step("Amend the requirement (formatting only) and resume: dynamic re-planning",
                "Status " + run.status() + "; " + reusedAfter + " task(s) reused, re-executed only " + reexecutedAfterLastAmendment(id)));

        List<WorkflowEvent> events = store.history(id);
        List<Check> checks = new ArrayList<>();
        checks.add(check("Requirement normalized into testable criteria", !spec(id).acceptanceCriteria().isEmpty(),
                spec(id).acceptanceCriteria().size() + " acceptance criteria, " + spec(id).ambiguities().size() + " ambiguities"));
        checks.add(check("Parallel execution of independent branches",
                WorkflowMetricsService.tasksThatRanConcurrently(events).size() >= 2,
                "tasks that ran concurrently: " + WorkflowMetricsService.tasksThatRanConcurrently(events)
                        + "; max running at once " + WorkflowMetricsService.maxConcurrentRunning(events)));
        checks.add(check("Dependency barrier: release started only after tests, security and docs succeeded",
                startedAfterAll(events, "release", List.of("tests", "security", "docs")), "from TASK_TRANSITION order"));
        if (demo) {
            checks.add(check("Recoverable failure: transient fault retried with backoff, then recovered",
                    countEvents(id, Type.TASK_RETRY_SCHEDULED) >= 1 && task(service.view(id), "tests").state() == TaskState.SUCCEEDED,
                    countEvents(id, Type.TASK_RETRY_SCHEDULED) + " retry scheduled; MTTR " + mttr(id)));
        }
        checks.add(check("Human approval bound to the exact readiness version", approvedCount(id) >= 1, approvalsLine(id)));
        if (run.workspace() != null) {
            List<WorkflowEvent> validations = events.stream().filter(e -> e.getType() == Type.VALIDATION_COMMAND).toList();
            checks.add(check("Patch applied in an isolated git worktree and validated by approved commands",
                    countEvents(id, Type.WORKSPACE_PATCH_APPLIED) >= 1 && !validations.isEmpty()
                            && validations.stream().allMatch(e -> e.getMessage().contains("passed")),
                    validations.stream().map(WorkflowEvent::getMessage).collect(Collectors.joining("; "))));
        }
        checks.add(check("Formatting-only requirement change preserved unaffected work",
                reusedAfter >= 3 && run.status() == RunStatus.SUCCEEDED,
                reusedAfter + " TASK_REUSED events; re-executed after amendment: " + reexecutedAfterLastAmendment(id)));
        checks.add(check("Release approval still valid after re-planning (same readiness version reused)",
                service.view(id).approvals().stream().anyMatch(a -> a.task().equals("release") && a.status().equals("VALID")), approvalsLine(id)));
        checks.add(audit(id));
        return finish("greenfield", "Build an agreed capability (click-limited short links) from a clear requirement: normalization, "
                + "parallel stages, a recoverable transient failure, human approval, promotion of the patch to a local branch, "
                + "and re-planning that preserves unaffected work.", id, mode, steps, checks);
    }

    // ------------------------------------------------------------------ 2. brownfield

    static final String BROWNFIELD_REQUIREMENT = String.join("\n",
            "Tenants reactivating a deactivated link whose expiry is in the past must receive 409 Conflict instead of a reactivated link whose next redirect returns 410 Gone.",
            "The update endpoint must still allow a tenant to extend the expiry and then reactivate the link.",
            "Reactivation of links that have not expired must keep its current behavior.",
            "When a reactivation is refused, charge the card on file a one dollar reactivation fee.");

    private Report brownfield(ExecutionMode mode) {
        List<Step> steps = new ArrayList<>();
        WorkflowViews.Run run = service.create(new WorkflowRequests.CreateRun("Brownfield: refuse reactivation of expired links",
                BROWNFIELD_REQUIREMENT, mode, null, null));
        Long id = run.id();
        steps.add(new Step("Submit a change to existing behavior (reactivation of expired links)", "Run " + id + " CREATED"));
        service.start(id);
        run = await(id);
        List<String> impacted = latest(id, "architecture-design", ImpactAnalysis.class)
                .map(d -> d.impactedComponents().stream().map(ImpactAnalysis.ImpactedComponent::component).toList())
                .orElse(List.of());
        steps.add(new Step("Start: analyze impacted modules, APIs, persistence, and tests",
                "Impacted: " + impacted + "; security task " + task(run, "security").state() + " (" + firstLine(task(run, "security").error()) + ")"));
        WorkflowViews.Task security = task(run, "security");
        boolean rejected = security.state() == TaskState.FAILED;
        boolean parallelSurvived = task(run, "tests").state() == TaskState.SUCCEEDED && task(run, "docs").state() == TaskState.SUCCEEDED;
        steps.add(new Step("Observe the rejected gate", "security " + security.state() + ", release " + task(run, "release").state()
                + "; tests " + task(run, "tests").state() + ", docs " + task(run, "docs").state() + " (independent branches unaffected)"));

        String amended = BROWNFIELD_REQUIREMENT.lines().filter(l -> !l.contains("charge the card")).collect(Collectors.joining("\n"));
        service.amendRequirement(id, new WorkflowRequests.AmendRequirement(amended,
                "Product owner removed the out-of-scope payment clause flagged by POL-SCOPE.", REVIEWER));
        service.resume(id);
        run = await(id);
        steps.add(new Step("Amend the requirement (remove the payment clause) and resume: re-plan affected stages",
                "Status " + run.status() + "; invalidated " + countEvents(id, Type.TASK_INVALIDATED) + " task(s); re-executed "
                        + reexecutedAfterLastAmendment(id)));
        run = approveAll(id, steps, "Brownfield readiness reviewed after re-planning");

        List<WorkflowEvent> events = store.history(id);
        List<Check> checks = new ArrayList<>();
        checks.add(check("Impact analysis names existing modules (service, API, persistence, tests)",
                impacted.stream().anyMatch(c -> c.contains("Service")) && impacted.stream().anyMatch(c -> c.contains("Controller") || c.contains("Dto")),
                "impacted " + impacted));
        checks.add(check("Rejected gate: POL-SCOPE blocked the payment clause at the security stage", rejected,
                firstLine(security.error())));
        checks.add(check("Policy violation was NOT retried automatically", noRetriesFor(events, "security"), "no TASK_RETRY_SCHEDULED for 'security'"));
        checks.add(check("Independent branches finished despite the rejection", parallelSurvived, "tests and docs SUCCEEDED in the first drive"));
        checks.add(check("Requirement change re-planned and re-ran affected stages",
                countEvents(id, Type.REQUIREMENT_UPDATED) == 1 && run.status() == RunStatus.SUCCEEDED,
                "re-executed after amendment: " + reexecutedAfterLastAmendment(id)));
        checks.add(check("Release needed a fresh human approval after re-planning", approvedCount(id) >= 1, approvalsLine(id)));
        checks.add(check("Parallel execution observed", WorkflowMetricsService.maxConcurrentRunning(events) >= 2,
                "max running at once " + WorkflowMetricsService.maxConcurrentRunning(events)));
        checks.add(audit(id));
        return finish("brownfield", "Enhance existing URL-shortener behavior (refuse reactivation of expired links) after analyzing the impacted "
                + "modules, then show a rejected gate (an out-of-scope payment clause) and upstream-change re-planning.", id, mode, steps, checks);
    }

    // ------------------------------------------------------------------ 3. ambiguous

    static final String AMBIGUOUS_REQUIREMENT = "Make links safer and analytics more useful.";
    static final String AMBIGUOUS_CLARIFIED = String.join("\n",
            "Tenants must have links to known-malicious destinations rejected with 400 Bad Request when the URL safety check is enabled.",
            "The stats endpoint must show clicks per day for the last 30 days for each link of the tenant.",
            "Analytics must not record IP addresses or user agents.");

    private Report ambiguous(ExecutionMode mode) {
        List<Step> steps = new ArrayList<>();
        WorkflowViews.Run run = service.create(new WorkflowRequests.CreateRun("Ambiguous: safer links, more useful analytics",
                AMBIGUOUS_REQUIREMENT, mode, null, null));
        Long id = run.id();
        steps.add(new Step("Submit \"" + AMBIGUOUS_REQUIREMENT + "\"", "Run " + id + " CREATED"));
        service.start(id);
        run = await(id);
        RequirementSpec first = spec(id);
        steps.add(new Step("Start: requirement analysis identifies ambiguities and proposes assumptions",
                "Status " + run.status() + "; " + first.ambiguities().size() + " ambiguities: "
                        + first.ambiguities().stream().map(a -> a.id() + " " + a.statement()).toList()));
        boolean downstreamHeld = task(run, "design").state() == TaskState.PENDING;
        steps.add(new Step("Clarification checkpoint holds all downstream work",
                "requirements " + task(run, "requirements").state() + ", design " + task(run, "design").state()));

        int v1 = version(task(run, "requirements").outputs().get("requirement-spec"));
        service.decide(id, "requirements", new WorkflowRequests.Decision(WorkflowRequests.Decision.Verdict.REJECTED, REVIEWER,
                "Too vague to build. 'Safer' means rejecting malicious destinations; 'more useful' means daily click counts. See amended requirement.",
                Map.of("requirement-spec", v1)));
        run = await(id);
        steps.add(new Step("Reviewer rejects the proposed assumptions and requests clarification", "Status " + run.status()
                + "; requirements " + task(run, "requirements").state() + " (rejection is never retried automatically)"));
        service.amendRequirement(id, new WorkflowRequests.AmendRequirement(AMBIGUOUS_CLARIFIED, "Clarification from the product owner.", REVIEWER));
        service.resume(id);
        run = await(id);
        RequirementSpec second = spec(id);
        steps.add(new Step("Amend with the clarified requirement and resume", "Status " + run.status() + "; " + second.ambiguities().size()
                + " remaining ambiguity/ies: " + second.ambiguities().stream().map(a -> a.id() + " " + a.statement()).toList()));
        run = approveAll(id, steps, "Remaining assumptions / readiness reviewed after clarification");

        List<WorkflowEvent> events = store.history(id);
        List<Check> checks = new ArrayList<>();
        checks.add(check("Ambiguities identified with proposed assumptions", first.ambiguities().size() >= 3,
                first.ambiguities().size() + " ambiguities, each with an assumption"));
        checks.add(check("Execution held until resolution: design never ran before the clarification was approved",
                downstreamHeld && startedOnlyAfter(events, "design", Type.APPROVED, "requirements"), "from audit order"));
        checks.add(check("Rejected approval went STALE when the requirement-spec changed",
                service.view(id).approvals().stream().anyMatch(a -> a.decision().equals("REJECTED") && a.status().equals("STALE")),
                approvalsLine(id)));
        checks.add(check("Clarified requirement produced a more specific spec", second.acceptanceCriteria().size() > first.acceptanceCriteria().size(),
                first.acceptanceCriteria().size() + " -> " + second.acceptanceCriteria().size() + " acceptance criteria"));
        checks.add(check("Run completed only after human decisions", run.status() == RunStatus.SUCCEEDED && approvedCount(id) >= 2,
                approvalsLine(id)));
        checks.add(check("Parallel execution observed", WorkflowMetricsService.maxConcurrentRunning(events) >= 2,
                "max running at once " + WorkflowMetricsService.maxConcurrentRunning(events)));
        checks.add(audit(id));
        return finish("ambiguous", "Process \"" + AMBIGUOUS_REQUIREMENT + "\": identify ambiguities, propose assumptions, stop for clarification, "
                + "re-plan on the clarified requirement, and execute only after a human resolves it.", id, mode, steps, checks);
    }

    // ------------------------------------------------------------------ helpers

    /** Approves every checkpoint the run reaches, one at a time, until it stops waiting (bounded). */
    private WorkflowViews.Run approveAll(Long id, List<Step> steps, String what) {
        WorkflowViews.Run run = service.view(id);
        for (int guard = 0; run.status() == RunStatus.WAITING_APPROVAL && guard < 5; guard++) {
            run = approveWaiting(id, steps, what);
        }
        return run;
    }

    private WorkflowViews.Run approveWaiting(Long id, List<Step> steps, String what) {
        WorkflowViews.Run run = service.view(id);
        for (WorkflowViews.Task t : run.tasks()) {
            if (t.state() != TaskState.WAITING_APPROVAL) {
                continue;
            }
            Map<String, Integer> versions = new LinkedHashMap<>();
            t.outputs().forEach((name, label) -> versions.put(name, version(label)));
            service.decide(id, t.key(), new WorkflowRequests.Decision(WorkflowRequests.Decision.Verdict.APPROVED, REVIEWER, what, versions));
            run = await(id);
            steps.add(new Step("Human approval of '" + t.key() + "' bound to " + versions + " (reason: " + firstLine(t.reviewReason()) + ")",
                    "Status " + run.status()));
            return run;
        }
        return run;
    }

    private WorkflowViews.Run await(Long id) {
        long deadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < deadline) {
            WorkflowViews.Run run = service.view(id);
            if (!engine.isActive(id) && run.status() != RunStatus.RUNNING) {
                return run;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("Scenario run " + id + " did not settle within 180 s");
    }

    private Report finish(String name, String description, Long id, ExecutionMode mode, List<Step> steps, List<Check> checks) {
        WorkflowViews.Run run = service.view(id);
        return new Report(name, description, id, mode, run.status(), List.copyOf(steps), List.copyOf(checks),
                checks.stream().allMatch(Check::passed), reports.summary(id), reports.timeline(id));
    }

    private <T> java.util.Optional<T> latest(Long id, String name, Class<T> type) {
        List<com.schwab.urlshortener.workflow.persistence.WorkflowArtifact> versions = store.artifactVersions(id, name);
        return versions.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(store.read(versions.get(versions.size() - 1).getContentJson(), type));
    }

    private RequirementSpec spec(Long id) {
        Long artifactId = store.outputRefs(store.task(id, "requirements")).get("requirement-spec");
        if (artifactId == null) {
            List<com.schwab.urlshortener.workflow.persistence.WorkflowArtifact> versions = store.artifactVersions(id, "requirement-spec");
            artifactId = versions.get(versions.size() - 1).getId();
        }
        return store.read(store.artifact(artifactId).getContentJson(), RequirementSpec.class);
    }

    private static WorkflowViews.Task task(WorkflowViews.Run run, String key) {
        return run.tasks().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow();
    }

    private static int version(String label) {
        String v = label.substring(label.indexOf("@v") + 2);
        return Integer.parseInt(v.contains("#") ? v.substring(0, v.indexOf('#')) : v);
    }

    private long countEvents(Long id, Type type) {
        return store.history(id).stream().filter(e -> e.getType() == type).count();
    }

    private int approvedCount(Long id) {
        return (int) service.view(id).approvals().stream().filter(a -> a.decision().equals("APPROVED")).count();
    }

    private String approvalsLine(Long id) {
        return service.view(id).approvals().stream().map(a -> "#" + a.id() + " " + a.task() + " " + a.decision() + " " + a.artifact() + " " + a.status())
                .collect(Collectors.joining("; "));
    }

    private String mttr(Long id) {
        WorkflowMetricsService.Metrics m = metrics.forRun(id);
        return m.mttrMs() == null ? "no recovered incident" : String.format("%.0f ms over %d recovered incident(s)", m.mttrMs(), m.recoveredIncidents());
    }

    private List<String> reexecutedAfterLastAmendment(Long id) {
        List<WorkflowEvent> events = store.history(id);
        long after = events.stream().filter(e -> e.getType() == Type.REQUIREMENT_UPDATED).mapToLong(WorkflowEvent::getSequence).max().orElse(0);
        return events.stream().filter(e -> e.getSequence() > after && e.getType() == Type.TASK_ATTEMPT_STARTED)
                .map(WorkflowEvent::getTaskKey).distinct().toList();
    }

    private static boolean startedAfterAll(List<WorkflowEvent> events, String task, List<String> deps) {
        long start = events.stream().filter(e -> task.equals(e.getTaskKey()) && "RUNNING".equals(e.getToState()))
                .mapToLong(WorkflowEvent::getSequence).min().orElse(Long.MAX_VALUE);
        return deps.stream().allMatch(d -> events.stream().anyMatch(e -> d.equals(e.getTaskKey()) && "SUCCEEDED".equals(e.getToState())
                && e.getSequence() < start));
    }

    private static boolean startedOnlyAfter(List<WorkflowEvent> events, String task, Type type, String typeTask) {
        long firstStart = events.stream().filter(e -> task.equals(e.getTaskKey()) && "RUNNING".equals(e.getToState()))
                .mapToLong(WorkflowEvent::getSequence).min().orElse(Long.MAX_VALUE);
        long firstApproval = events.stream().filter(e -> e.getType() == type && typeTask.equals(e.getTaskKey()))
                .mapToLong(WorkflowEvent::getSequence).min().orElse(Long.MAX_VALUE);
        return firstApproval < firstStart;
    }

    private static boolean noRetriesFor(List<WorkflowEvent> events, String task) {
        return events.stream().noneMatch(e -> e.getType() == Type.TASK_RETRY_SCHEDULED && task.equals(e.getTaskKey()));
    }

    private Check audit(Long id) {
        var v = store.auditLog().verify(id);
        return check("Audit hash chain intact", v.valid(), v.eventsChecked() + " events verified: " + v.reason());
    }

    private static Check check(String name, boolean passed, String detail) {
        return new Check(name, passed, detail == null ? "" : detail);
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "-";
        }
        String line = s.lines().findFirst().orElse("");
        return line.length() > 160 ? line.substring(0, 157) + "..." : line;
    }

    static Set<String> names() {
        return Set.copyOf(NAMES);
    }
}
