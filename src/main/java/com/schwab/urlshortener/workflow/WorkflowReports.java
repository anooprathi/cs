package com.schwab.urlshortener.workflow;

import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.agentic.model.ImplementationPlan;
import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.RiskAssessment;
import com.schwab.urlshortener.agentic.review.MarkdownRenderer;
import com.schwab.urlshortener.workflow.api.WorkflowViews;
import com.schwab.urlshortener.workflow.artifact.InterfaceContract;
import com.schwab.urlshortener.workflow.artifact.ReadinessReport;
import com.schwab.urlshortener.workflow.artifact.TestReport;
import com.schwab.urlshortener.workflow.workspace.ChangePatch;
import com.schwab.urlshortener.workflow.audit.AuditLog;
import com.schwab.urlshortener.workflow.engine.Hashes;
import com.schwab.urlshortener.workflow.engine.WorkflowStore;
import com.schwab.urlshortener.workflow.metrics.WorkflowMetricsService;
import com.schwab.urlshortener.workflow.persistence.WorkflowArtifact;
import com.schwab.urlshortener.workflow.persistence.WorkflowEvent;
import com.schwab.urlshortener.workflow.persistence.WorkflowEvent.Type;
import com.schwab.urlshortener.workflow.persistence.WorkflowRun;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Human-readable views over a run's durable state: the execution timeline, the
 * full run report, and the final engineering summary. All are derived only from
 * stored run, task, artifact, approval, and audit rows, so they can be
 * regenerated at any time and never claim more than the record shows.
 */
@Component
public class WorkflowReports {

    private static final Set<Type> TIMELINE_NOISE = EnumSet.of(Type.TASK_READY);

    private final WorkflowService service;
    private final WorkflowStore store;
    private final AuditLog audit;
    private final WorkflowMetricsService metrics;
    private final MarkdownRenderer cells;

    public WorkflowReports(WorkflowService service, WorkflowStore store, WorkflowMetricsService metrics, MarkdownRenderer cells) {
        this.service = service;
        this.store = store;
        this.audit = store.auditLog();
        this.metrics = metrics;
        this.cells = cells;
    }

    public static String banner(ExecutionMode mode, String model) {
        return mode == ExecutionMode.LLM
                ? "> **Execution mode: LLM (`" + model + "`).** Agent steps called the model through the Anthropic API. Gates, policies, "
                + "the workspace, and approvals are deterministic code. Review model output before acting on it.\n"
                : "> **Execution mode: DETERMINISTIC_DEMO. No language model was called.** Agent steps ran rule-based demo logic. Gates, "
                + "policies, the git workspace, approvals, retries, and audit are real.\n";
    }

    /** A per-task span chart plus every audit event, with times relative to run creation. */
    public String timeline(Long runId) {
        WorkflowRun run = store.run(runId);
        List<WorkflowEvent> events = audit.events(runId);
        long t0 = run.getCreatedAt().toEpochMilli();
        long tEnd = events.isEmpty() ? t0 : events.get(events.size() - 1).getCreatedAt().toEpochMilli();
        StringBuilder sb = new StringBuilder("# Execution timeline: run ").append(runId).append(" (").append(cells.cell(run.getTitle())).append(")\n\n")
                .append(banner(run.getExecutionMode(), run.getModel())).append('\n')
                .append("Status **").append(run.getStatus()).append("**, requirement revision ").append(run.getRequirementRevision())
                .append(", ").append(run.getDriveCount()).append(" drive(s), ").append(events.size()).append(" audit events. ")
                .append("Max tasks RUNNING at once: **").append(WorkflowMetricsService.maxConcurrentRunning(events)).append("**.\n\n");

        Map<String, long[]> spans = new LinkedHashMap<>();
        Map<String, Long> since = new LinkedHashMap<>();
        Map<String, StringBuilder> bars = new LinkedHashMap<>();
        int width = 48;
        double scale = Math.max(1, tEnd - t0) / (double) width;
        for (WorkflowEvent e : events) {
            if (e.getType() != Type.TASK_TRANSITION) {
                continue;
            }
            long at = e.getCreatedAt().toEpochMilli();
            bars.computeIfAbsent(e.getTaskKey(), k -> new StringBuilder(" ".repeat(width)));
            if ("RUNNING".equals(e.getToState())) {
                since.put(e.getTaskKey(), at);
            } else if ("RUNNING".equals(e.getFromState()) && since.containsKey(e.getTaskKey())) {
                long from = since.remove(e.getTaskKey());
                int a = (int) Math.min(width - 1, (from - t0) / scale);
                int b = (int) Math.min(width - 1, Math.max(a, (at - t0) / scale));
                for (int i = a; i <= b; i++) {
                    bars.get(e.getTaskKey()).setCharAt(i, '█');
                }
                spans.merge(e.getTaskKey(), new long[]{from - t0, at - t0}, (x, y) -> new long[]{Math.min(x[0], y[0]), Math.max(x[1], y[1])});
            }
            if ("WAITING_APPROVAL".equals(e.getToState())) {
                int a = (int) Math.min(width - 1, (at - t0) / scale);
                bars.get(e.getTaskKey()).setCharAt(a, '◆');
            }
        }
        sb.append("```\n").append(String.format("%-16s|%s| 0 .. %d ms%n", "task", "-".repeat(width), tEnd - t0));
        bars.forEach((task, bar) -> sb.append(String.format("%-16s|%s|%n", task, bar)));
        sb.append("```\n_█ = RUNNING (executing an attempt), ◆ = paused for human approval._\n\n");

        sb.append("| +ms | # | task | event | transition | actor | detail |\n|---:|---:|---|---|---|---|---|\n");
        for (WorkflowEvent e : events) {
            if (TIMELINE_NOISE.contains(e.getType())) {
                continue;
            }
            String transition = e.getFromState() == null ? "" : e.getFromState() + " → " + e.getToState();
            String msg = e.getMessage() == null ? "" : e.getMessage();
            sb.append("| ").append(e.getCreatedAt().toEpochMilli() - t0).append(" | ").append(e.getSequence()).append(" | ")
                    .append(e.getTaskKey() == null ? "" : e.getTaskKey()).append(" | ").append(e.getType()).append(" | ")
                    .append(transition).append(" | ").append(e.getActor() == null ? "" : cells.cell(e.getActor())).append(" | ")
                    .append(cells.cell(msg.length() > 220 ? msg.substring(0, 217) + "..." : msg)).append(" |\n");
        }
        AuditLog.Verification v = audit.verify(runId);
        sb.append("\nAudit chain: ").append(v.valid() ? "**intact**" : "**BROKEN** at #" + v.firstBrokenSequence() + " (" + v.reason() + ")")
                .append(" (").append(v.eventsChecked()).append(" events verified).\n");
        return sb.toString();
    }

    /** The run's final engineering summary: what was asked, what ran, what was produced, how it was validated, who approved. */
    public String summary(Long runId) {
        WorkflowViews.Run run = service.view(runId);
        List<WorkflowEvent> events = audit.events(runId);
        WorkflowMetricsService.Metrics m = metrics.forRun(runId);
        StringBuilder sb = new StringBuilder("## Final engineering summary: run ").append(runId).append("\n\n")
                .append(banner(run.executionMode(), run.model())).append('\n')
                .append("| | |\n|---|---|\n")
                .append("| Title | ").append(cells.cell(run.title())).append(" |\n")
                .append("| Final status | **").append(run.status()).append("** |\n")
                .append("| Requirement revisions | ").append(run.requirementRevision()).append(" |\n")
                .append("| Drives (start/resume/continue) | ").append(run.driveCount()).append(" |\n")
                .append("| Graph layers (parallel groups) | ").append(run.parallelLayers()).append(" |\n")
                .append("| Max tasks running at once | ").append(WorkflowMetricsService.maxConcurrentRunning(events)).append(" |\n")
                .append("| Agent attempts / automatic retries | ").append(m.agentAttempts()).append(" / ").append(m.automaticRetries()).append(" |\n")
                .append("| Recovered failures (MTTR) | ").append(m.recoveredIncidents())
                .append(m.mttrMs() == null ? "" : String.format(" (mean %.0f ms)", m.mttrMs())).append(" |\n")
                .append("| Workspace rollbacks | ").append(m.rollbackEvents()).append(" |\n")
                .append("| Injected demo faults | ").append(run.injectedFaults().isEmpty() ? "none" : run.injectedFaults()).append(" |\n")
                .append("| Base commit / workspace | ").append(run.baseCommit() == null ? "-" : Hashes.shortHash(run.baseCommit()))
                .append(" / ").append(run.workspace() == null ? "-" : cells.cell(run.workspace())).append(" |\n")
                .append("| Promotion | ").append(run.promotion() == null ? "not promoted" : run.promotion().branch() + " @ "
                        + Hashes.shortHash(run.promotion().commit()) + (run.promotion().compensatedAt() == null ? "" : " (compensated)")).append(" |\n\n");

        Map<String, WorkflowArtifact> current = new LinkedHashMap<>();
        service.artifacts(runId).stream().filter(WorkflowViews.ArtifactSummary::current)
                .forEach(a -> current.put(a.name(), store.artifact(a.id())));
        ChangePatch patch = content(current, "change-patch", ChangePatch.class);
        TestReport tests = content(current, "test-report", TestReport.class);
        builtSection(sb, patch, tests, content(current, "interface-contract", InterfaceContract.class));
        List<Map<String, Object>> results = latestAgentResults(events);
        planSection(sb, content(current, "implementation-plan", ImplementationPlan.class), results);
        validationSection(sb, run.tasks(), content(current, "readiness-report", ReadinessReport.class));
        risksSection(sb, content(current, "risk-assessment", RiskAssessment.class), results);
        assumptionsSection(sb, content(current, "requirement-spec", RequirementSpec.class), results);

        sb.append("### Tasks\n\n| Task | Stage | State | Attempts | Retries | Reused | Fallback | Outputs |\n|---|---|---|---:|---:|---|---|---|\n");
        for (WorkflowViews.Task t : run.tasks()) {
            sb.append("| ").append(t.key()).append(" | ").append(t.stage()).append(" | ").append(t.state()).append(" | ").append(t.attempts())
                    .append(" | ").append(t.retries()).append(" | ").append(t.reused() ? "yes" : "").append(" | ").append(t.fallbackUsed() ? "YES" : "")
                    .append(" | ").append(String.join(", ", t.outputs().values())).append(" |\n");
        }
        sb.append("\n### Human decisions\n\n");
        if (run.approvals().isEmpty()) {
            sb.append("_None recorded._\n\n");
        } else {
            sb.append("| # | Task | Bound to | Decision | Reviewer | At | Status |\n|---:|---|---|---|---|---|---|\n");
            run.approvals().forEach(a -> sb.append("| ").append(a.id()).append(" | ").append(a.task()).append(" | ").append(a.artifact())
                    .append(" | ").append(a.decision()).append(" | ").append(cells.cell(a.reviewer())).append(" | ").append(a.decidedAt())
                    .append(" | ").append(a.status()).append(a.staleReason() == null ? "" : " (" + cells.cell(a.staleReason()) + ")").append(" |\n"));
            sb.append('\n');
        }
        sb.append("### Notable events\n\n");
        Set<Type> notable = EnumSet.of(Type.TASK_RETRY_SCHEDULED, Type.TASK_RETRIES_EXHAUSTED, Type.TASK_FALLBACK, Type.EXIT_GATE_FAILED,
                Type.ENTRY_GATE_FAILED, Type.REJECTED, Type.APPROVED, Type.APPROVAL_STALE, Type.REQUIREMENT_UPDATED, Type.TASK_INVALIDATED,
                Type.TASK_REUSED, Type.WORKSPACE_ROLLED_BACK, Type.RUN_STOPPED, Type.RUN_CANCELLED, Type.PATCH_PROMOTED, Type.PROMOTION_COMPENSATED);
        List<WorkflowEvent> picked = events.stream().filter(e -> notable.contains(e.getType())).toList();
        if (picked.isEmpty()) {
            sb.append("_None._\n");
        }
        picked.forEach(e -> sb.append("- `#").append(e.getSequence()).append("` **").append(e.getType()).append("**")
                .append(e.getTaskKey() == null ? "" : " `" + e.getTaskKey() + "`").append(": ")
                .append(e.getMessage() == null ? "" : e.getMessage().replace("\n", " ")).append('\n'));
        sb.append("\n### Limitations (what this run did NOT do)\n\n")
                .append("- It did not merge, push, or deploy anything. Promotion (if shown) is a local branch in the orchestrator's sandbox clone.\n");
        if (run.executionMode() == ExecutionMode.DETERMINISTIC_DEMO) {
            sb.append(patch != null && patch.kind() == ChangePatch.Kind.CODE
                    ? "- No language model was called. The code came from a pre-authored deterministic recipe (`" + patch.author()
                    + "`); it is not AI-generated and only exists for requirements a recipe recognizes.\n"
                    : "- No language model was called; the patch is a change-record document, not product code.\n");
        }
        if (tests != null && !tests.productTestsExecuted()) {
            sb.append("- No product test suite was executed (test execution: plan verification).\n");
        } else if (tests != null) {
            sb.append("- Only the targeted test classes ran (`").append(cells.cell(tests.command()))
                    .append("`), not the full suite with its coverage gate; run `mvn clean verify` on the promoted branch before merging.\n");
        }
        return sb.toString();
    }

    private <T> T content(Map<String, WorkflowArtifact> current, String name, Class<T> type) {
        WorkflowArtifact a = current.get(name);
        return a == null ? null : store.read(a.getContentJson(), type);
    }

    /** The details of the last AGENT_RESULT per task: what each agent decided, assumed, and flagged. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> latestAgentResults(List<WorkflowEvent> events) {
        Map<String, Map<String, Object>> byTask = new LinkedHashMap<>();
        events.stream().filter(e -> e.getType() == Type.AGENT_RESULT && e.getDetailsJson() != null).forEach(e -> {
            Map<String, Object> details = new LinkedHashMap<>(store.read(e.getDetailsJson(), Map.class));
            details.put("task", e.getTaskKey());
            byTask.put(e.getTaskKey(), details);
        });
        return List.copyOf(byTask.values());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> details, String key) {
        Object v = details.get(key);
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private void builtSection(StringBuilder sb, ChangePatch patch, TestReport tests, InterfaceContract contract) {
        sb.append("### What was built\n\n");
        if (patch == null) {
            sb.append("_No accepted change-patch._\n\n");
            return;
        }
        sb.append("- **Patch:** ").append(patch.kind()).append(" by `").append(patch.author()).append("`, ")
                .append(patch.applied() ? "applied in the isolated worktree (" + cells.cell(lastLine(patch.diffStat())) + ")" : "not applied")
                .append(". ").append(cells.cell(patch.summary())).append('\n');
        if (patch.notice() != null) {
            sb.append("- **Provenance:** ").append(cells.cell(patch.notice())).append('\n');
        }
        if (!patch.testFiles().isEmpty()) {
            sb.append("- **Tests in the patch:** ").append(patch.testFiles().stream().map(f -> "`" + f + "`").collect(Collectors.joining(", ")))
                    .append('\n');
        }
        if (tests != null) {
            sb.append("- **Test execution:** runner `").append(tests.runner()).append("`, product tests executed: **")
                    .append(tests.productTestsExecuted() ? "yes" : "no").append("**, ").append(tests.passed()).append('/')
                    .append(tests.executed()).append(" passed");
            if (tests.command() != null) {
                sb.append(", command `").append(cells.cell(tests.command())).append("` exit ").append(tests.exitCode());
            }
            sb.append(".\n");
            junitTotals(tests.outputTail()).ifPresent(t -> sb.append("- **JUnit totals reported by the command:** `").append(cells.cell(t))
                    .append("`\n"));
        }
        if (contract != null) {
            sb.append("- **Interface contract (").append(contract.source()).append("):** ");
            List<String> items = new java.util.ArrayList<>();
            contract.apiChanges().forEach(a -> items.add((a.method() == null ? "" : a.method() + " ") + "`" + cells.cell(a.path()) + "` "
                    + a.change()));
            contract.schemaChanges().forEach(s -> items.add(s.ddl() == null ? "schema: " + cells.cell(s.table()) + " " + s.change()
                    : "`" + cells.cell(s.ddl()) + "`"));
            sb.append(items.isEmpty() ? "no API or schema change" : String.join("; ", items)).append('\n');
        }
        sb.append('\n');
    }

    private static String lastLine(String text) {
        if (text == null || text.isBlank()) {
            return "-";
        }
        List<String> lines = text.strip().lines().toList();
        return lines.get(lines.size() - 1).strip();
    }

    /** The last Maven Surefire "Tests run: n, Failures: n, ..." totals line in the command output, if any. */
    static java.util.Optional<String> junitTotals(String output) {
        if (output == null) {
            return java.util.Optional.empty();
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Tests run: \\d+, Failures: \\d+, Errors: \\d+, Skipped: \\d+(?=\\s*$)",
                java.util.regex.Pattern.MULTILINE).matcher(output);
        String last = null;
        while (m.find()) {
            last = m.group();
        }
        return java.util.Optional.ofNullable(last);
    }

    private void planSection(StringBuilder sb, ImplementationPlan plan, List<Map<String, Object>> results) {
        sb.append("### Plan and rationale\n\n");
        if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) {
            sb.append("_No accepted implementation plan._\n\n");
        } else {
            sb.append("| Task | Title | Depends on | Components | Criteria |\n|---|---|---|---|---|\n");
            plan.tasks().forEach(t -> sb.append("| ").append(t.id()).append(" | ").append(cells.cell(t.title())).append(" | ")
                    .append(t.dependsOn() == null ? "" : String.join(", ", t.dependsOn())).append(" | ")
                    .append(t.components() == null ? "" : cells.cell(String.join(", ", t.components()))).append(" | ")
                    .append(t.acceptanceCriteria() == null ? "" : String.join(", ", t.acceptanceCriteria())).append(" |\n"));
            sb.append('\n');
        }
        sb.append("Key decisions (as recorded by each stage's agent):\n\n");
        boolean any = false;
        for (Map<String, Object> r : results) {
            for (Object d : list(r, "decisions")) {
                if (d instanceof Map<?, ?> m) {
                    sb.append("- `").append(r.get("task")).append("`: ").append(cells.cell(String.valueOf(m.get("decision"))))
                            .append(" _Why:_ ").append(cells.cell(String.valueOf(m.get("rationale")))).append('\n');
                    any = true;
                }
            }
        }
        sb.append(any ? "\n" : "_None recorded._\n\n");
    }

    /** Every task's exit gate as recorded (criteria passed / total, failed ids), then the release-readiness checklist. */
    private void validationSection(StringBuilder sb, List<WorkflowViews.Task> tasks, ReadinessReport readiness) {
        sb.append("### Validation\n\n| Task | State | Exit gate | Failed criteria |\n|---|---|---|---|\n");
        for (WorkflowViews.Task t : tasks) {
            com.fasterxml.jackson.databind.JsonNode gate = t.exitGate();
            String result = "not evaluated";
            String failed = "";
            if (gate != null && gate.has("criteria")) {
                int total = gate.get("criteria").size();
                List<String> failedIds = new java.util.ArrayList<>();
                gate.get("criteria").forEach(c -> {
                    if (!c.path("passed").asBoolean()) {
                        failedIds.add(c.path("id").asText());
                    }
                });
                result = (failedIds.isEmpty() ? "passed " : "FAILED ") + (total - failedIds.size()) + "/" + total;
                failed = String.join(", ", failedIds);
            }
            sb.append("| ").append(t.key()).append(" | ").append(t.state()).append(" | ").append(result).append(" | ")
                    .append(failed).append(" |\n");
        }
        sb.append('\n');
        if (readiness != null && readiness.checks() != null) {
            sb.append("Release readiness (").append(readiness.ready() ? "**ready**" : "**not ready**").append("):\n\n")
                    .append("| Check | Blocking | Result | Detail |\n|---|---|---|---|\n");
            readiness.checks().forEach(c -> sb.append("| ").append(c.id()).append(" ").append(cells.cell(c.description())).append(" | ")
                    .append(c.blocking() ? "yes" : "advisory").append(" | ").append(c.passed() ? "pass" : "**FAIL**").append(" | ")
                    .append(cells.cell(c.detail())).append(" |\n"));
            sb.append('\n');
        }
    }

    private void risksSection(StringBuilder sb, RiskAssessment risks, List<Map<String, Object>> results) {
        sb.append("### Risks and trade-offs\n\n");
        if (risks != null && risks.risks() != null && !risks.risks().isEmpty()) {
            sb.append("| Risk | Severity | Description | Mitigation |\n|---|---|---|---|\n");
            risks.risks().forEach(r -> sb.append("| ").append(r.id()).append(" | ").append(r.severity()).append(" | ")
                    .append(cells.cell(r.description())).append(" | ").append(cells.cell(r.mitigation())).append(" |\n"));
            sb.append('\n');
        } else {
            sb.append("_No accepted risk assessment._\n\n");
        }
        List<String> flagged = new java.util.ArrayList<>();
        results.forEach(r -> list(r, "risks").forEach(x -> flagged.add("- `" + r.get("task") + "`: " + cells.cell(String.valueOf(x)))));
        if (!flagged.isEmpty()) {
            sb.append("Flagged by agents during execution:\n\n").append(String.join("\n", flagged)).append("\n\n");
        }
    }

    private void assumptionsSection(StringBuilder sb, RequirementSpec spec, List<Map<String, Object>> results) {
        sb.append("### Assumptions\n\n");
        if (spec != null && spec.ambiguities() != null && !spec.ambiguities().isEmpty()) {
            sb.append("Approved by a reviewer, NOT confirmed by the requirement owner:\n\n| Id | Ambiguity | Assumption taken |\n|---|---|---|\n");
            spec.ambiguities().forEach(a -> sb.append("| ").append(a.id()).append(" | ").append(cells.cell(a.statement())).append(" | ")
                    .append(cells.cell(a.assumption())).append(" |\n"));
            sb.append('\n');
        } else {
            sb.append("No requirement ambiguities remained in the accepted requirement-spec.\n\n");
        }
        List<String> stated = new java.util.ArrayList<>();
        results.forEach(r -> list(r, "assumptions").forEach(x -> stated.add("- `" + r.get("task") + "`: " + cells.cell(String.valueOf(x)))));
        if (!stated.isEmpty()) {
            sb.append("Stated by agents:\n\n").append(String.join("\n", stated)).append("\n\n");
        }
    }

    /** Run report: banner, summary, then every current artifact's rendered Markdown. */
    public String report(Long runId) {
        WorkflowViews.Run run = service.view(runId);
        StringBuilder sb = new StringBuilder("# Workflow run ").append(runId).append(": ").append(cells.cell(run.title())).append("\n\n")
                .append(summary(runId)).append("\n## Current artifacts\n\n");
        boolean any = false;
        for (WorkflowViews.ArtifactSummary a : service.artifacts(runId)) {
            if (!a.current()) {
                continue;
            }
            WorkflowArtifact full = store.artifact(a.id());
            sb.append(full.getContentMarkdown() == null ? "" : full.getContentMarkdown()).append('\n');
            any = true;
        }
        if (!any) {
            sb.append("_No accepted artifacts yet._\n");
        }
        return sb.toString();
    }
}
