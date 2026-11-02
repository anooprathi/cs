package com.schwab.urlshortener.workflow;

import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.agentic.review.MarkdownRenderer;
import com.schwab.urlshortener.workflow.api.WorkflowViews;
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
        sb.append("\n### What this run did NOT do\n\n")
                .append("- It did not merge, push, or deploy anything. Promotion (if shown) is a local branch in the orchestrator's sandbox clone.\n");
        if (run.executionMode() == ExecutionMode.DETERMINISTIC_DEMO) {
            sb.append("- No language model was called; the patch is a change-record document, not product code.\n");
        }
        WorkflowArtifact testReport = store.artifacts(runId).stream().filter(a -> a.getName().equals("test-report")).reduce((a, b) -> b).orElse(null);
        if (testReport != null && testReport.getContentJson().contains("\"productTestsExecuted\":false")) {
            sb.append("- No product test suite was executed (test runner: plan-verification).\n");
        }
        return sb.toString();
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
