package com.schwab.urlshortener.workflow.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.workflow.engine.RunStatus;
import com.schwab.urlshortener.workflow.persistence.WorkflowEvent;
import com.schwab.urlshortener.workflow.persistence.WorkflowEvent.Type;
import com.schwab.urlshortener.workflow.persistence.WorkflowEventRepository;
import com.schwab.urlshortener.workflow.persistence.WorkflowRun;
import com.schwab.urlshortener.workflow.persistence.WorkflowRunRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Workflow metrics computed from DURABLE data (run rows and the append-only
 * audit log), so they survive restarts and can be recomputed and checked by
 * anyone with database read access. The Micrometer counters the engine also
 * emits (workflow.task.retries, workflow.workspace.rollbacks, ...) are
 * per-process conveniences for Prometheus. These are the definitions of record.
 *
 * Precise definitions. Every one is computed over the selected runs: all runs,
 * or one run.
 * <dl>
 *   <dt>Run success rate</dt>
 *   <dd>{@code succeeded / terminal}, where terminal = runs whose CURRENT status is SUCCEEDED, FAILED, or CANCELLED.
 *       Runs that are still open (CREATED, RUNNING, WAITING_APPROVAL, STOPPED, PAUSED, INTERRUPTED) are excluded and
 *       counted separately. A FAILED run that is later resumed to SUCCEEDED counts once, as SUCCEEDED. Null if no
 *       run is terminal.</dd>
 *   <dt>Retry frequency</dt>
 *   <dd>{@code TASK_RETRY_SCHEDULED events / TASK_ATTEMPT_STARTED events}: the fraction of agent invocations that
 *       were followed by an AUTOMATIC retry. Manual resumes are not retries. Fallback executions are not counted as
 *       attempts.</dd>
 *   <dt>Rollback frequency</dt>
 *   <dd>{@code runs with at least one WORKSPACE_ROLLED_BACK event / runs with a WORKSPACE_CREATED event}, plus the
 *       raw count of rollback events (compensation after a failed attempt, cancellation, or an operator request).</dd>
 *   <dt>End-to-end execution latency</dt>
 *   <dd>For runs whose current status is SUCCEEDED: {@code completedAt - createdAt}, reported as count, mean, p50,
 *       p95, and max in milliseconds. It INCLUDES time spent waiting for human approval and between manual resumes.
 *       {@code activeExecution} is the same statistic over the sum of each run's drive durations
 *       (RUN_DRIVE_ENDED.durationMs), which excludes idle and approval time.</dd>
 *   <dt>Mean time to recovery (MTTR)</dt>
 *   <dd>Per (run, task): an incident OPENS at the first failure signal while none is open (a TASK_ATTEMPT_FAILED
 *       event, or a TASK_TRANSITION to FAILED). It CLOSES at the next TASK_TRANSITION of that task to SUCCEEDED,
 *       whether by retry, fallback, resume, reuse, or approval. MTTR = mean(close - open) over CLOSED incidents
 *       only. Incidents still open are reported as {@code unrecoveredIncidents} and excluded from the mean.</dd>
 * </dl>
 *
 * Cost: this scans the selected runs' events, O(events). That's fine for a prototype's H2. A production system
 * would maintain these incrementally or in a metrics store.
 */
@Service
public class WorkflowMetricsService {

    private static final Set<RunStatus> TERMINAL = EnumSet.of(RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED);

    public record LatencyStats(int count, Double meanMs, Long p50Ms, Long p95Ms, Long maxMs) {
    }

    public record Metrics(
            String scope,
            int runs,
            int terminalRuns,
            int openRuns,
            int succeededRuns,
            Double runSuccessRate,
            long agentAttempts,
            long automaticRetries,
            Double retryFrequency,
            int runsWithWorkspace,
            int runsWithRollback,
            long rollbackEvents,
            Double rollbackFrequency,
            LatencyStats endToEndLatency,
            LatencyStats activeExecution,
            int recoveredIncidents,
            int unrecoveredIncidents,
            Double mttrMs,
            Map<String, String> definitions
    ) {
    }

    public static final Map<String, String> DEFINITIONS;

    static {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("runSuccessRate", "succeeded / terminal; terminal = current status SUCCEEDED, FAILED or CANCELLED; open runs excluded");
        d.put("retryFrequency", "TASK_RETRY_SCHEDULED events / TASK_ATTEMPT_STARTED events (automatic retries per agent invocation)");
        d.put("rollbackFrequency", "runs with >=1 WORKSPACE_ROLLED_BACK / runs with a WORKSPACE_CREATED event");
        d.put("endToEndLatency", "SUCCEEDED runs: completedAt - createdAt (includes approval waits); count, mean, p50, p95, max ms");
        d.put("activeExecution", "SUCCEEDED runs: sum of RUN_DRIVE_ENDED.durationMs (excludes idle/approval time)");
        d.put("mttr", "mean(first SUCCEEDED transition - first failure signal) per (run, task) incident; open incidents excluded");
        DEFINITIONS = Map.copyOf(d);
    }

    private final WorkflowRunRepository runs;
    private final WorkflowEventRepository events;
    private final ObjectMapper objectMapper;

    public WorkflowMetricsService(WorkflowRunRepository runs, WorkflowEventRepository events, ObjectMapper objectMapper) {
        this.runs = runs;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    public Metrics all() {
        List<WorkflowRun> all = runs.findAll();
        Set<Long> ids = all.stream().map(WorkflowRun::getId).collect(Collectors.toSet());
        List<WorkflowEvent> ev = events.findAllByOrderByIdAsc().stream().filter(e -> ids.contains(e.getRunId())).toList();
        return compute("all runs", all, ev);
    }

    public Metrics forRun(Long runId) {
        List<WorkflowRun> one = runs.findById(runId).map(List::of).orElse(List.of());
        return compute("run " + runId, one, events.findByRunIdOrderBySequenceAsc(runId));
    }

    Metrics compute(String scope, Collection<WorkflowRun> selected, List<WorkflowEvent> ev) {
        int terminal = (int) selected.stream().filter(r -> TERMINAL.contains(r.getStatus())).count();
        int succeeded = (int) selected.stream().filter(r -> r.getStatus() == RunStatus.SUCCEEDED).count();

        long attempts = ev.stream().filter(e -> e.getType() == Type.TASK_ATTEMPT_STARTED).count();
        long retries = ev.stream().filter(e -> e.getType() == Type.TASK_RETRY_SCHEDULED).count();

        Set<Long> withWorkspace = ev.stream().filter(e -> e.getType() == Type.WORKSPACE_CREATED && e.getMessage() != null
                && !e.getMessage().startsWith("Workspace unavailable")).map(WorkflowEvent::getRunId).collect(Collectors.toSet());
        List<WorkflowEvent> rollbacks = ev.stream().filter(e -> e.getType() == Type.WORKSPACE_ROLLED_BACK).toList();
        Set<Long> withRollback = rollbacks.stream().map(WorkflowEvent::getRunId).collect(Collectors.toSet());

        List<Long> e2e = new ArrayList<>();
        List<Long> active = new ArrayList<>();
        Map<Long, Long> driveMs = new HashMap<>();
        for (WorkflowEvent e : ev) {
            if (e.getType() == Type.RUN_DRIVE_ENDED && e.getDetailsJson() != null) {
                long ms = durationMs(e.getDetailsJson());
                driveMs.merge(e.getRunId(), ms, Long::sum);
            }
        }
        for (WorkflowRun r : selected) {
            if (r.getStatus() == RunStatus.SUCCEEDED && r.getCompletedAt() != null) {
                e2e.add(Duration.between(r.getCreatedAt(), r.getCompletedAt()).toMillis());
                active.add(driveMs.getOrDefault(r.getId(), 0L));
            }
        }

        Map<String, Long> open = new HashMap<>();
        List<Long> recovered = new ArrayList<>();
        for (WorkflowEvent e : ev) {
            if (e.getTaskKey() == null) {
                continue;
            }
            String key = e.getRunId() + "/" + e.getTaskKey();
            boolean failure = e.getType() == Type.TASK_ATTEMPT_FAILED
                    || (e.getType() == Type.TASK_TRANSITION && "FAILED".equals(e.getToState()));
            boolean success = e.getType() == Type.TASK_TRANSITION && "SUCCEEDED".equals(e.getToState());
            if (failure && !open.containsKey(key)) {
                open.put(key, e.getCreatedAt().toEpochMilli());
            } else if (success && open.containsKey(key)) {
                recovered.add(e.getCreatedAt().toEpochMilli() - open.remove(key));
            }
        }

        return new Metrics(scope, selected.size(), terminal, selected.size() - terminal, succeeded,
                terminal == 0 ? null : (double) succeeded / terminal,
                attempts, retries, attempts == 0 ? null : (double) retries / attempts,
                withWorkspace.size(), (int) withRollback.stream().filter(withWorkspace::contains).count(), rollbacks.size(),
                withWorkspace.isEmpty() ? null : (double) withRollback.stream().filter(withWorkspace::contains).count() / withWorkspace.size(),
                stats(e2e), stats(active), recovered.size(), open.size(),
                recovered.isEmpty() ? null : recovered.stream().mapToLong(Long::longValue).average().orElse(0),
                DEFINITIONS);
    }

    static LatencyStats stats(List<Long> values) {
        if (values.isEmpty()) {
            return new LatencyStats(0, null, null, null, null);
        }
        List<Long> sorted = values.stream().sorted().toList();
        return new LatencyStats(sorted.size(), sorted.stream().mapToLong(Long::longValue).average().orElse(0),
                percentile(sorted, 50), percentile(sorted, 95), sorted.get(sorted.size() - 1));
    }

    /** Nearest-rank percentile. */
    static long percentile(List<Long> sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    private long durationMs(String detailsJson) {
        try {
            JsonNode n = objectMapper.readTree(detailsJson).get("durationMs");
            return n == null ? 0 : n.asLong();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Distinct task keys whose RUNNING intervals overlapped at some instant: evidence of parallel execution. */
    public static int maxConcurrentRunning(List<WorkflowEvent> runEvents) {
        Map<String, Long> runningSince = new HashMap<>();
        List<long[]> intervals = new ArrayList<>();
        for (WorkflowEvent e : runEvents) {
            if (e.getType() != Type.TASK_TRANSITION) {
                continue;
            }
            long at = e.getCreatedAt().toEpochMilli();
            if ("RUNNING".equals(e.getToState())) {
                runningSince.put(e.getTaskKey(), at);
            } else if ("RUNNING".equals(e.getFromState()) && runningSince.containsKey(e.getTaskKey())) {
                intervals.add(new long[]{runningSince.remove(e.getTaskKey()), at});
            }
        }
        int max = 0;
        for (long[] i : intervals) {
            int overlapping = 0;
            for (long[] j : intervals) {
                if (j[0] <= i[0] && i[0] < j[1]) {
                    overlapping++;
                }
            }
            max = Math.max(max, overlapping);
        }
        return max;
    }

    /** Keys of tasks whose RUNNING interval overlapped another task's RUNNING interval. */
    public static Set<String> tasksThatRanConcurrently(List<WorkflowEvent> runEvents) {
        Map<String, Long> since = new HashMap<>();
        Map<String, List<long[]>> byTask = new LinkedHashMap<>();
        for (WorkflowEvent e : runEvents) {
            if (e.getType() != Type.TASK_TRANSITION) {
                continue;
            }
            long at = e.getCreatedAt().toEpochMilli();
            if ("RUNNING".equals(e.getToState())) {
                since.put(e.getTaskKey(), at);
            } else if ("RUNNING".equals(e.getFromState()) && since.containsKey(e.getTaskKey())) {
                byTask.computeIfAbsent(e.getTaskKey(), k -> new ArrayList<>()).add(new long[]{since.remove(e.getTaskKey()), at});
            }
        }
        Set<String> result = new HashSet<>();
        for (var a : byTask.entrySet()) {
            for (var b : byTask.entrySet()) {
                if (a.getKey().equals(b.getKey())) {
                    continue;
                }
                for (long[] x : a.getValue()) {
                    for (long[] y : b.getValue()) {
                        if (x[0] < y[1] && y[0] < x[1]) {
                            result.add(a.getKey());
                        }
                    }
                }
            }
        }
        return result;
    }
}
