package com.schwab.urlshortener.workflow.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.workflow.engine.RunStatus;
import com.schwab.urlshortener.workflow.persistence.WorkflowEvent;
import com.schwab.urlshortener.workflow.persistence.WorkflowEvent.Type;
import com.schwab.urlshortener.workflow.persistence.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Each metric against hand-built runs and events, so the documented definitions are pinned exactly. */
class WorkflowMetricsServiceTest {

    private final WorkflowMetricsService service = new WorkflowMetricsService(null, null, new ObjectMapper());
    private final Instant t0 = Instant.parse("2026-11-01T10:00:00Z");
    private long seq;

    private WorkflowRun run(long id, RunStatus status, long durationMs) {
        return WorkflowRun.builder().id(id).status(status).createdAt(t0)
                .completedAt(status == RunStatus.SUCCEEDED ? t0.plusMillis(durationMs) : null).build();
    }

    private WorkflowEvent ev(long run, Type type, String task, long atMs, String from, String to, String details) {
        return WorkflowEvent.builder().runId(run).sequence(++seq).type(type).taskKey(task).fromState(from).toState(to)
                .createdAt(t0.plusMillis(atMs)).detailsJson(details).message(type == Type.WORKSPACE_CREATED ? "Isolated worktree" : null).hash("h").build();
    }

    @Test
    void successRate_countsOnlyTerminalRuns() {
        List<WorkflowRun> runs = List.of(run(1, RunStatus.SUCCEEDED, 1000), run(2, RunStatus.FAILED, 0), run(3, RunStatus.CANCELLED, 0),
                run(4, RunStatus.WAITING_APPROVAL, 0), run(5, RunStatus.SUCCEEDED, 3000));

        WorkflowMetricsService.Metrics m = service.compute("t", runs, List.of());

        assertThat(m.terminalRuns()).isEqualTo(4);
        assertThat(m.openRuns()).isEqualTo(1);
        assertThat(m.runSuccessRate()).isEqualTo(0.5);
        assertThat(m.endToEndLatency().count()).isEqualTo(2);
        assertThat(m.endToEndLatency().meanMs()).isEqualTo(2000.0);
        assertThat(m.endToEndLatency().p50Ms()).isEqualTo(1000L);
        assertThat(m.endToEndLatency().maxMs()).isEqualTo(3000L);
    }

    @Test
    void retryAndRollbackFrequency_followTheirDefinitions() {
        List<WorkflowEvent> e = new ArrayList<>();
        e.add(ev(1, Type.WORKSPACE_CREATED, null, 0, null, null, null));
        e.add(ev(2, Type.WORKSPACE_CREATED, null, 0, null, null, null));
        for (int i = 0; i < 4; i++) {
            e.add(ev(1, Type.TASK_ATTEMPT_STARTED, "a", i, null, null, null));
        }
        e.add(ev(1, Type.TASK_RETRY_SCHEDULED, "a", 5, null, null, null));
        e.add(ev(1, Type.WORKSPACE_ROLLED_BACK, null, 6, null, null, null));
        e.add(ev(1, Type.WORKSPACE_ROLLED_BACK, null, 7, null, null, null));

        WorkflowMetricsService.Metrics m = service.compute("t", List.of(run(1, RunStatus.FAILED, 0), run(2, RunStatus.FAILED, 0)), e);

        assertThat(m.retryFrequency()).isEqualTo(0.25);
        assertThat(m.rollbackEvents()).isEqualTo(2);
        assertThat(m.rollbackFrequency()).isEqualTo(0.5);
    }

    @Test
    void mttr_measuresFirstFailureToNextSuccess_perTask_excludingUnrecovered() {
        List<WorkflowEvent> e = List.of(
                ev(1, Type.TASK_ATTEMPT_FAILED, "a", 100, null, null, null),
                ev(1, Type.TASK_ATTEMPT_FAILED, "a", 200, null, null, null),
                ev(1, Type.TASK_TRANSITION, "a", 400, "RUNNING", "SUCCEEDED", null),
                ev(1, Type.TASK_TRANSITION, "b", 500, "RUNNING", "FAILED", null),
                ev(1, Type.TASK_TRANSITION, "b", 1500, "RUNNING", "SUCCEEDED", null),
                ev(1, Type.TASK_ATTEMPT_FAILED, "c", 600, null, null, null));

        WorkflowMetricsService.Metrics m = service.compute("t", List.of(run(1, RunStatus.FAILED, 0)), e);

        assertThat(m.recoveredIncidents()).isEqualTo(2);
        assertThat(m.unrecoveredIncidents()).isEqualTo(1);
        assertThat(m.mttrMs()).isEqualTo((300 + 1000) / 2.0);
    }

    @Test
    void activeExecution_sumsDriveDurations_andEmptyInputsGiveNulls() {
        List<WorkflowEvent> e = List.of(
                ev(1, Type.RUN_DRIVE_ENDED, null, 10, null, null, "{\"durationMs\":40}"),
                ev(1, Type.RUN_DRIVE_ENDED, null, 20, null, null, "{\"durationMs\":60}"));

        WorkflowMetricsService.Metrics m = service.compute("t", List.of(run(1, RunStatus.SUCCEEDED, 5000)), e);
        WorkflowMetricsService.Metrics empty = service.compute("t", List.of(), List.of());

        assertThat(m.activeExecution().meanMs()).isEqualTo(100.0);
        assertThat(empty.runSuccessRate()).isNull();
        assertThat(empty.retryFrequency()).isNull();
        assertThat(empty.mttrMs()).isNull();
        assertThat(empty.definitions()).containsKeys("runSuccessRate", "retryFrequency", "rollbackFrequency", "endToEndLatency", "mttr");
    }

    @Test
    void concurrencyEvidence_isDerivedFromRunningIntervals() {
        List<WorkflowEvent> e = List.of(
                ev(1, Type.TASK_TRANSITION, "x", 0, "READY", "RUNNING", null),
                ev(1, Type.TASK_TRANSITION, "y", 10, "READY", "RUNNING", null),
                ev(1, Type.TASK_TRANSITION, "x", 50, "RUNNING", "SUCCEEDED", null),
                ev(1, Type.TASK_TRANSITION, "y", 60, "RUNNING", "SUCCEEDED", null),
                ev(1, Type.TASK_TRANSITION, "z", 70, "READY", "RUNNING", null),
                ev(1, Type.TASK_TRANSITION, "z", 80, "RUNNING", "SUCCEEDED", null));

        assertThat(WorkflowMetricsService.maxConcurrentRunning(e)).isEqualTo(2);
        assertThat(WorkflowMetricsService.tasksThatRanConcurrently(e)).containsExactlyInAnyOrder("x", "y");
    }
}
