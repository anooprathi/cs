package com.schwab.urlshortener.workflow.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.exception.WorkflowConflictException;
import com.schwab.urlshortener.workflow.WorkflowProperties;
import com.schwab.urlshortener.workflow.audit.AuditEntry;
import com.schwab.urlshortener.workflow.graph.GraphValidationResult;
import com.schwab.urlshortener.workflow.graph.GraphValidator;
import com.schwab.urlshortener.workflow.graph.TaskNode;
import com.schwab.urlshortener.workflow.graph.WorkflowDefinition;
import com.schwab.urlshortener.workflow.persistence.WorkflowArtifact;
import com.schwab.urlshortener.workflow.persistence.WorkflowEvent.Type;
import com.schwab.urlshortener.workflow.persistence.WorkflowRun;
import com.schwab.urlshortener.workflow.persistence.WorkflowTask;
import com.schwab.urlshortener.workflow.workspace.WorkspaceManager;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Executes a workflow run's dependency graph, with governance and failure recovery.
 *
 * <h3>Scheduling (one "drive")</h3>
 * The driver thread loops in rounds:
 * <ol>
 *   <li><b>Skip</b>: a schedulable task with a FAILED/SKIPPED/CANCELLED dependency becomes SKIPPED.
 *       Independent branches keep running.</li>
 *   <li><b>Promote</b>: PENDING/INVALIDATED → READY only when <em>every</em> dependency is SUCCEEDED. This is
 *       the synchronization barrier: no task starts on partial upstream results, and a dependency waiting
 *       for approval is not SUCCEEDED.</li>
 *   <li><b>Dispatch</b>: READY tasks get versioned input refs and an input fingerprint. A prior accepted
 *       output with the same fingerprint (and a still-valid approval, where one is needed) is reused without
 *       executing. Otherwise the task becomes RUNNING on the task pool, up to {@code max-parallelism} at once.
 *       max-parallelism=1 means strictly sequential, in topological order.</li>
 *   <li><b>Wait</b> for any in-flight task, then start the next round. A stop or cancel request ends
 *       dispatching. In-flight tasks finish first.</li>
 * </ol>
 *
 * <h3>Task execution (worker thread)</h3>
 * Entry gate → attempt loop → store each artifact as an unaccepted new version with lineage → exit gate →
 * accept and transition (SUCCEEDED, or WAITING_APPROVAL when a human checkpoint applies) in one transaction.
 * In the attempt loop:
 * <ul>
 *   <li>{@link AgentFailures.Transient} (timeout, rate limit, overload, connection): bounded retries with
 *       exponential backoff ({@code app.workflow.retry.*}).</li>
 *   <li>{@link AgentFailures.AgentFailure}, an exhausted transient, or a FAILED agent result: in LLM mode, for
 *       an agent that supports it, the defined fallback runs the deterministic path, labeled and gated on human
 *       approval. Otherwise the task FAILS.</li>
 *   <li>Gate failures, policy violations, rejected approvals, tool violations: FAIL immediately, never retried.</li>
 *   <li>After any failure that may have left local side effects, the agent's {@link StageAgent#compensate} runs
 *       (workspace rollback) and is audited.</li>
 * </ul>
 *
 * <h3>Resume and re-planning</h3>
 * FAILED/SKIPPED/CANCELLED tasks and interrupted RUNNING tasks go back to PENDING. Each SUCCEEDED and
 * WAITING_APPROVAL task is re-validated: upstream still SUCCEEDED, output still accepted and not
 * invalidated, and the fingerprint recomputed from current inputs (including the requirement hash, for
 * agents that read it) equal to the recorded one. A mismatch INVALIDATES the task and its descendants with
 * the reason recorded. When they're rescheduled, any whose inputs turn out unchanged reuse their prior output
 * (and approval), so only genuinely affected work and approvals are redone.
 *
 * <h3>Concurrency boundary</h3>
 * One driver per run, enforced in-process ({@link #claim}). That is correct for a single instance. A
 * multi-instance deployment would need a database lease (documented limitation).
 */
@Component
@Slf4j
public class WorkflowEngine {

    public enum DriveMode {
        /** First execution of a CREATED run. */
        START,
        /** Retry after FAILED / CANCELLED / STOPPED / INTERRUPTED / PAUSED: reset and re-validate first. */
        RESUME,
        /** Pick up after a human approval decision: no reset, no re-validation. */
        CONTINUE
    }

    private static final Set<TaskState> VALIDATABLE = EnumSet.of(TaskState.SUCCEEDED, TaskState.WAITING_APPROVAL);
    private static final Set<TaskState> CANCELLABLE = EnumSet.of(TaskState.PENDING, TaskState.READY, TaskState.INVALIDATED,
            TaskState.WAITING_APPROVAL);

    private final WorkflowStore store;
    private final AgentRegistry registry;
    private final GraphValidator validator;
    private final WorkflowArtifactRenderer renderer;
    private final WorkspaceManager workspaces;
    private final Executor taskPool;
    private final ExecutorService agentPool;
    private final WorkflowProperties properties;
    private final MeterRegistry meters;
    private final Set<Long> activeRuns = ConcurrentHashMap.newKeySet();

    public WorkflowEngine(WorkflowStore store, AgentRegistry registry, GraphValidator validator, WorkflowArtifactRenderer renderer,
                          WorkspaceManager workspaces, @Qualifier("workflowTaskExecutor") Executor taskPool,
                          @Qualifier("workflowAgentExecutor") ExecutorService agentPool, WorkflowProperties properties,
                          MeterRegistry meters) {
        this.store = store;
        this.registry = registry;
        this.validator = validator;
        this.renderer = renderer;
        this.workspaces = workspaces;
        this.taskPool = taskPool;
        this.agentPool = agentPool;
        this.properties = properties;
        this.meters = meters;
    }

    /** Graph context computed once per drive from the run's definition snapshot. */
    public record Graph(Map<String, TaskNode> nodes, List<String> order, Map<String, String> producers,
                        Map<String, Set<String>> descendants, List<List<String>> layers) {
    }

    // ------------------------------------------------------------------ claiming

    public boolean isActive(Long runId) {
        return activeRuns.contains(runId);
    }

    /** Claims the run for one driver, or throws 409. Asynchronous callers claim on the request thread first. */
    public void claim(Long runId) {
        if (!activeRuns.add(runId)) {
            throw new WorkflowConflictException("Workflow run " + runId + " is already executing.");
        }
    }

    public void release(Long runId) {
        activeRuns.remove(runId);
    }

    public void drive(Long runId, DriveMode mode) {
        claim(runId);
        try {
            driveClaimed(runId, mode);
        } finally {
            release(runId);
        }
    }

    /** Drives a run the caller already claimed; does not release it. */
    public void driveClaimed(Long runId, DriveMode mode) {
        long start = System.nanoTime();
        try {
            doDrive(runId, mode);
        } catch (RuntimeException e) {
            log.error("Workflow run {} driver failed", runId, e);
            store.updateRun(runId, r -> {
                r.setStatus(RunStatus.FAILED);
                r.setFailureReason(truncate("Engine error: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
                r.setCompletedAt(Instant.now());
            });
            store.event(runId, null, null, Type.RUN_FAILED, "Engine error: " + e.getClass().getSimpleName());
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            WorkflowRun run = store.run(runId);
            store.audit(AuditEntry.of(runId, Type.RUN_DRIVE_ENDED).mode(run.getExecutionMode().name(), run.getModel())
                    .message("Drive " + mode + " ended with run " + run.getStatus() + " after " + ms + " ms.")
                    .details(Map.of("durationMs", ms, "mode", mode.name(), "status", run.getStatus().name())).build());
            meters.timer("workflow.drive.duration", "mode", mode.name()).record(Duration.ofMillis(ms));
        }
    }

    public Graph graph(WorkflowRun run) {
        WorkflowDefinition definition = store.read(run.getDefinitionJson(), WorkflowDefinition.class);
        GraphValidationResult validation = validator.validate(definition, registry);
        if (!validation.valid()) {
            throw new IllegalStateException("Graph snapshot is no longer valid: " + validation.errors().stream()
                    .map(GraphValidationResult.GraphError::message).collect(Collectors.joining(" ")));
        }
        Map<String, TaskNode> nodes = new LinkedHashMap<>();
        definition.tasks().forEach(n -> nodes.put(n.key(), n));
        return new Graph(nodes, validation.topologicalOrder(), validation.producers(), GraphValidator.descendants(nodes),
                validation.parallelLayers());
    }

    private void doDrive(Long runId, DriveMode mode) {
        WorkflowRun run = store.run(runId);
        Graph graph = graph(run);

        WorkflowRun started = store.updateRun(runId, r -> {
            r.setStatus(RunStatus.RUNNING);
            r.setCancelRequested(false);
            r.setStopRequested(false);
            r.setDriveCount(r.getDriveCount() + 1);
            if (r.getStartedAt() == null) {
                r.setStartedAt(Instant.now());
            }
            r.setCompletedAt(null);
            r.setFailureReason(null);
        });
        Type startType = switch (mode) {
            case START -> Type.RUN_STARTED;
            case RESUME -> Type.RUN_RESUMED;
            case CONTINUE -> Type.RUN_CONTINUED;
        };
        store.audit(AuditEntry.of(runId, startType).mode(run.getExecutionMode().name(), run.getModel())
                .message("Drive #" + started.getDriveCount() + " (" + mode + "), max parallelism " + properties.maxParallelism()
                        + ", layers " + graph.layers() + ".")
                .details(Map.of("requirementRevision", run.getRequirementRevision(),
                        "requirementHash", Hashes.shortHash(run.getRequirementHash()),
                        "simulatedDemoLatencyMs", properties.demo().simulatedLatency().toMillis()))
                .build());

        started = openWorkspace(started);
        if (mode != DriveMode.CONTINUE) {
            prepare(started, graph);
        }
        schedule(started, graph);
        finish(runId, graph);
    }

    private WorkflowRun openWorkspace(WorkflowRun run) {
        if (!workspaces.enabled()) {
            return run;
        }
        Optional<String> unavailable = workspaces.unavailableReason();
        if (unavailable.isPresent()) {
            store.event(run.getId(), null, null, Type.WORKSPACE_CREATED, "Workspace unavailable: " + unavailable.get()
                    + ". Patches will not be applied, and the implementation gate will fail.");
            return run;
        }
        boolean first = run.getWorkspacePath() == null;
        WorkspaceManager.Workspace ws = workspaces.open(run.getId(), run.getBaseCommit());
        if (first) {
            store.audit(AuditEntry.of(run.getId(), Type.WORKSPACE_CREATED)
                    .message("Isolated worktree " + ws.directory().getFileName() + " created at base commit " + Hashes.shortHash(ws.baseCommit())
                            + " in the orchestrator's sandbox clone; the source repository is not modified.")
                    .details(Map.of("baseCommit", ws.baseCommit(), "workspace", ws.directory().toString())).build());
        }
        return store.updateRun(run.getId(), r -> {
            r.setBaseCommit(ws.baseCommit());
            r.setWorkspacePath(ws.directory().toString());
        });
    }

    // ------------------------------------------------------------------ resume preparation

    private void prepare(WorkflowRun run, Graph graph) {
        Long runId = run.getId();
        Map<String, WorkflowTask> tasks = store.tasksByKey(runId);
        for (String key : graph.order()) {
            WorkflowTask t = tasks.get(key);
            if (TaskState.RESET_ON_RESUME.contains(t.getState())) {
                TaskState previous = t.getState();
                if (previous == TaskState.RUNNING) {
                    store.event(runId, key, t.getAttempts(), Type.TASK_INTERRUPTED,
                            "Found RUNNING at resume: attempt " + t.getAttempts() + " never finished (process stopped mid-task).");
                } else if (previous != TaskState.READY) {
                    store.event(runId, key, t.getAttempts(), Type.TASK_RESET, "Was " + previous + "; reset to PENDING for retry.");
                }
                tasks.put(key, store.updateTask(t.getId(), x -> {
                    x.setState(TaskState.PENDING);
                    x.setErrorMessage(null);
                }));
            } else if (VALIDATABLE.contains(t.getState())) {
                Optional<String> reason = invalidReason(run, graph, tasks, key);
                if (reason.isPresent()) {
                    invalidateWithDescendants(run, graph, tasks, key, reason.get(), false);
                }
            }
        }
    }

    /** Why a SUCCEEDED/WAITING_APPROVAL task's work is no longer valid, or empty if it still is. */
    private Optional<String> invalidReason(WorkflowRun run, Graph graph, Map<String, WorkflowTask> tasks, String key) {
        WorkflowTask t = tasks.get(key);
        TaskNode node = graph.nodes().get(key);
        for (String dep : node.dependsOn()) {
            if (tasks.get(dep).getState() != TaskState.SUCCEEDED) {
                return Optional.of("Upstream '" + dep + "' is " + tasks.get(dep).getState() + ".");
            }
        }
        Map<String, Long> refs = store.outputRefs(t);
        StageAgent agent = registry.get(node.agent());
        for (String name : agent.produces()) {
            Long id = refs.get(name);
            if (id == null) {
                return Optional.of("No recorded output for '" + name + "'.");
            }
            WorkflowArtifact a = store.artifact(id);
            if (!a.isAccepted() || a.isInvalidated()) {
                return Optional.of("Output " + name + "@v" + a.getVersion() + " is " + (a.isInvalidated() ? "invalidated" : "not accepted") + ".");
            }
        }
        String current;
        try {
            current = fingerprint(run, agent, resolveInputs(graph, tasks, agent));
        } catch (RuntimeException e) {
            return Optional.of("Inputs unavailable: " + e.getMessage() + ".");
        }
        if (!current.equals(t.getFingerprint())) {
            String why = !agent.version().equals(t.getAgentVersion())
                    ? "agent version changed " + t.getAgentVersion() + " -> " + agent.version()
                    : agent.readsRequirement() ? "requirement or input content changed" : "input content changed";
            return Optional.of("Input fingerprint changed (" + Hashes.shortHash(t.getFingerprint()) + " -> " + Hashes.shortHash(current)
                    + "): " + why + ".");
        }
        return Optional.empty();
    }

    /**
     * Marks a task INVALIDATED (optionally marking its current outputs invalid so
     * they can never be reused) and cascades to every SUCCEEDED/WAITING_APPROVAL
     * descendant. Approvals of invalidated outputs go stale. Descendants keep
     * their artifacts valid: if their inputs turn out unchanged when they're
     * rescheduled, they are reused (preserved), not re-executed.
     */
    public void invalidateWithDescendants(WorkflowRun run, Graph graph, Map<String, WorkflowTask> tasks, String key, String reason,
                                          boolean invalidateOutputs) {
        WorkflowTask t = tasks.get(key);
        if (invalidateOutputs) {
            List<Long> ids = List.copyOf(store.outputRefs(t).values());
            store.updateArtifacts(ids, a -> a.setInvalidated(true));
            store.staleApprovals(run.getId(), key, List.of(), "output invalidated: " + reason);
        }
        tasks.put(key, markInvalidated(run.getId(), t, reason));
        for (String d : graph.descendants().get(key)) {
            if (VALIDATABLE.contains(tasks.get(d).getState())) {
                tasks.put(d, markInvalidated(run.getId(), tasks.get(d), "Upstream '" + key + "' was invalidated (" + reason + ")"));
            }
        }
    }

    private WorkflowTask markInvalidated(Long runId, WorkflowTask t, String reason) {
        store.event(runId, t.getTaskKey(), t.getAttempts(), Type.TASK_INVALIDATED, reason);
        return store.updateTask(t.getId(), x -> {
            x.setState(TaskState.INVALIDATED);
            x.setErrorMessage(truncate(reason));
        });
    }

    // ------------------------------------------------------------------ scheduling

    private void schedule(WorkflowRun run, Graph graph) {
        Long runId = run.getId();
        CompletionService<String> completions = new ExecutorCompletionService<>(taskPool);
        Set<String> inFlight = new HashSet<>();
        while (true) {
            WorkflowRun current = store.run(runId);
            boolean halted = current.isCancelRequested() || current.isStopRequested();
            boolean progressed = false;
            if (!halted) {
                Map<String, WorkflowTask> tasks = store.tasksByKey(runId);
                progressed |= propagateSkips(runId, graph, tasks);
                progressed |= promoteReady(runId, graph, tasks);
                progressed |= dispatch(current, graph, tasks, inFlight, completions);
            }
            if (inFlight.isEmpty()) {
                if (progressed) {
                    continue;
                }
                break;
            }
            inFlight.remove(awaitOne(completions));
        }
    }

    private boolean propagateSkips(Long runId, Graph graph, Map<String, WorkflowTask> tasks) {
        boolean changed = false;
        for (String key : graph.order()) {
            WorkflowTask t = tasks.get(key);
            if (!TaskState.SCHEDULABLE.contains(t.getState())) {
                continue;
            }
            Optional<String> blocker = graph.nodes().get(key).dependsOn().stream()
                    .filter(dep -> TaskState.BLOCKING.contains(tasks.get(dep).getState()))
                    .findFirst();
            if (blocker.isPresent()) {
                String reason = "Upstream '" + blocker.get() + "' is " + tasks.get(blocker.get()).getState() + ".";
                store.event(runId, key, t.getAttempts(), Type.TASK_SKIPPED, reason);
                tasks.put(key, store.updateTask(t.getId(), x -> {
                    x.setState(TaskState.SKIPPED);
                    x.setErrorMessage(reason);
                }));
                changed = true;
            }
        }
        return changed;
    }

    private boolean promoteReady(Long runId, Graph graph, Map<String, WorkflowTask> tasks) {
        boolean changed = false;
        for (String key : graph.order()) {
            WorkflowTask t = tasks.get(key);
            if (t.getState() != TaskState.PENDING && t.getState() != TaskState.INVALIDATED) {
                continue;
            }
            List<String> deps = graph.nodes().get(key).dependsOn();
            if (deps.stream().allMatch(dep -> tasks.get(dep).getState() == TaskState.SUCCEEDED)) {
                store.event(runId, key, t.getAttempts(), Type.TASK_READY,
                        deps.isEmpty() ? "No dependencies." : "All dependencies SUCCEEDED: " + deps + ".");
                tasks.put(key, store.updateTask(t.getId(), x -> x.setState(TaskState.READY)));
                changed = true;
            }
        }
        return changed;
    }

    private boolean dispatch(WorkflowRun run, Graph graph, Map<String, WorkflowTask> tasks, Set<String> inFlight,
                             CompletionService<String> completions) {
        boolean changed = false;
        Long runId = run.getId();
        for (String key : graph.order()) {
            WorkflowTask t = tasks.get(key);
            if (t.getState() != TaskState.READY || inFlight.contains(key)) {
                continue;
            }
            if (inFlight.size() >= properties.maxParallelism()) {
                break;
            }
            TaskNode node = graph.nodes().get(key);
            StageAgent agent = registry.get(node.agent());
            Map<String, ArtifactRef> inputs;
            try {
                inputs = resolveInputs(graph, tasks, agent);
            } catch (RuntimeException e) {
                failTask(runId, t.getId(), key, t.getAttempts(), "Inputs unavailable: " + e.getMessage());
                changed = true;
                continue;
            }
            String fingerprint = fingerprint(run, agent, inputs);
            if (tryReuse(runId, t, agent, fingerprint)) {
                changed = true;
                continue;
            }
            WorkflowTask running = store.updateTask(t.getId(), x -> {
                x.setState(TaskState.RUNNING);
                x.setAgentVersion(agent.version());
                x.setReused(false);
                x.setFallbackUsed(false);
                x.setReviewReason(null);
                x.setStartedAt(Instant.now());
                x.setFinishedAt(null);
                x.setErrorMessage(null);
                x.setEntryGateJson(null);
                x.setExitGateJson(null);
            });
            tasks.put(key, running);
            store.audit(AuditEntry.of(runId, Type.TASK_STARTED).task(key, running.getAttempts() + 1)
                    .agent(agentId(agent)).mode(run.getExecutionMode().name(), run.getModel())
                    .refs(inputs.values().stream().map(WorkflowEngine::refLabel).toList())
                    .message("Fingerprint " + Hashes.shortHash(fingerprint) + "; inputs " + labels(inputs.values()) + ".")
                    .build());
            completions.submit(() -> {
                try {
                    runTask(run, running.getId(), node, agent, fingerprint, inputs);
                } catch (RuntimeException e) {
                    log.error("Workflow task {} of run {} crashed outside agent handling", key, runId, e);
                    failTask(runId, running.getId(), key, store.task(runId, key).getAttempts(),
                            "Engine error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                return key;
            });
            inFlight.add(key);
            changed = true;
        }
        return changed;
    }

    private String awaitOne(CompletionService<String> completions) {
        try {
            Future<String> done = completions.take();
            return done.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for workflow tasks", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Workflow task wrapper failed", e.getCause());
        }
    }

    /** Versioned refs to the current accepted output of each declared input's producer. */
    private Map<String, ArtifactRef> resolveInputs(Graph graph, Map<String, WorkflowTask> tasks, StageAgent agent) {
        Map<String, ArtifactRef> inputs = new LinkedHashMap<>();
        for (String name : agent.consumes()) {
            String producerKey = graph.producers().get(name);
            WorkflowTask producer = tasks.get(producerKey);
            if (producer.getState() != TaskState.SUCCEEDED) {
                throw new IllegalStateException("producer '" + producerKey + "' of '" + name + "' is " + producer.getState());
            }
            Long id = store.outputRefs(producer).get(name);
            if (id == null) {
                throw new IllegalStateException("producer '" + producerKey + "' has no recorded '" + name + "'");
            }
            WorkflowArtifact a = store.artifact(id);
            inputs.put(name, new ArtifactRef(a.getId(), a.getName(), a.getVersion(), a.getContentHash(), producerKey));
        }
        return inputs;
    }

    private String fingerprint(WorkflowRun run, StageAgent agent, Map<String, ArtifactRef> inputs) {
        Map<String, String> hashes = new LinkedHashMap<>();
        inputs.forEach((name, ref) -> hashes.put(name, ref.contentHash()));
        return Hashes.fingerprint(agent.key(), agent.version(), run.getExecutionMode().name() + "/" + run.getTestExecution(),
                agent.readsRequirement() ? run.getRequirementHash() : "not-read", hashes);
    }

    /**
     * Reuses a prior accepted, non-invalidated output produced from exactly these
     * inputs instead of executing again. Where the prior output needed human
     * approval, it's reused only if that exact version still has a VALID APPROVED
     * approval. Approval travels with the artifact version, never with the task.
     */
    private boolean tryReuse(Long runId, WorkflowTask t, StageAgent agent, String fingerprint) {
        Map<String, WorkflowArtifact> found = new LinkedHashMap<>();
        for (String name : agent.produces()) {
            Optional<WorkflowArtifact> candidate = store.reusable(runId, name, fingerprint);
            if (candidate.isEmpty()) {
                return false;
            }
            found.put(name, candidate.get());
        }
        Map<String, Long> refs = new LinkedHashMap<>();
        found.forEach((name, a) -> refs.put(name, a.getId()));
        AgentResult.Envelope envelope = envelope(found.values().iterator().next());
        boolean fallback = found.values().stream().anyMatch(WorkflowArtifact::isFallback);
        String reviewReason = reviewReason(t, envelope == null ? null : envelope.reviewReason(), fallback);
        if (reviewReason != null && !store.approvedVersions(runId, t.getTaskKey(), refs)) {
            return false;
        }
        store.updateTask(t.getId(), x -> {
            x.setState(TaskState.SUCCEEDED);
            x.setReused(true);
            x.setFallbackUsed(fallback);
            x.setReviewReason(reviewReason);
            x.setFingerprint(fingerprint);
            x.setAgentVersion(agent.version());
            x.setOutputRefsJson(store.json(refs));
            x.setErrorMessage(null);
            x.setFinishedAt(Instant.now());
        });
        store.audit(AuditEntry.of(runId, Type.TASK_REUSED).task(t.getTaskKey(), t.getAttempts()).agent(agentId(agent))
                .refs(found.values().stream().map(a -> a.getName() + "@v" + a.getVersion() + "#" + Hashes.shortHash(a.getContentHash())).toList())
                .message("Inputs unchanged (fingerprint " + Hashes.shortHash(fingerprint) + "): reusing "
                        + found.values().stream().map(a -> a.getName() + "@v" + a.getVersion() + " from attempt " + a.getTaskAttempt())
                        .collect(Collectors.joining(", ")) + " without re-executing"
                        + (reviewReason == null ? "." : "; its approval is still valid for that exact version."))
                .build());
        meters.counter("workflow.task.reused").increment();
        return true;
    }

    // ------------------------------------------------------------------ task execution (worker thread)

    private record Outcome(AgentResult result, AgentRequest request, boolean fallback) {
    }

    private void runTask(WorkflowRun run, Long taskId, TaskNode node, StageAgent agent, String fingerprint,
                         Map<String, ArtifactRef> inputs) {
        Long runId = run.getId();
        String key = node.key();
        AgentRequest request = buildRequest(run, key, store.task(runId, key).getAttempts() + 1, agent, inputs);

        GateResult entry = GateResult.evaluate(GateResult.Kind.ENTRY, agent.entryCriteria(), GateInput.entry(request));
        store.updateTask(taskId, t -> t.setEntryGateJson(store.json(entry)));
        gateEvent(runId, key, request.attempt(), agent, entry);
        if (!entry.passed()) {
            failTask(runId, taskId, key, request.attempt(), "Entry gate failed: " + entry.failedIds() + ". Not retried (gate failure).");
            return;
        }

        Outcome outcome = attemptWithRecovery(run, taskId, key, agent, request);
        if (outcome == null) {
            return; // failed (already recorded) or stopped
        }
        AgentResult result = outcome.result();
        AgentRequest used = outcome.request();
        int attempt = store.task(runId, key).getAttempts();

        List<String> producedNames = result.artifacts().stream().map(AgentResult.Produced::name).toList();
        if (producedNames.size() != new HashSet<>(producedNames).size()
                || !new HashSet<>(producedNames).equals(new HashSet<>(agent.produces()))) {
            compensate(runId, key, attempt, agent, used, "agent contract violation");
            failTask(runId, taskId, key, attempt, "Agent contract violated: produced " + producedNames + ", declared " + agent.produces() + ".");
            return;
        }

        List<ArtifactRef> read = used.accessedRefs();
        Map<String, Object> outputs = new LinkedHashMap<>();
        Map<String, Long> refs = new LinkedHashMap<>();
        List<Long> ids = new ArrayList<>();
        String resultJson = store.json(result.envelope());
        boolean usageCharged = false;
        for (AgentResult.Produced p : result.artifacts()) {
            String contentJson = store.json(p.content());
            boolean chargeHere = p.origin() == ArtifactOrigin.LLM && !usageCharged;
            usageCharged |= chargeHere;
            WorkflowArtifact saved = store.storeArtifact(WorkflowArtifact.builder()
                    .runId(runId).name(p.name()).taskKey(key).taskAttempt(attempt)
                    .agentKey(agent.key()).agentVersion(agent.version())
                    .origin(p.origin()).adapter(adapterName(p.origin())).model(p.model()).fallback(outcome.fallback())
                    .contentType(p.content().getClass().getSimpleName())
                    .contentJson(contentJson).contentMarkdown(renderer.render(p.name(), p.content(), p.origin(), p.model()))
                    .contentHash(Hashes.sha256(contentJson)).inputFingerprint(fingerprint)
                    .resultJson(resultJson)
                    .inputTokens(chargeHere ? result.usage().inputTokens() : null)
                    .outputTokens(chargeHere ? result.usage().outputTokens() : null)
                    .estimatedCostUsd(chargeHere ? result.usage().estimatedCostUsd() : null)
                    .build(), read);
            outputs.put(p.name(), p.content());
            refs.put(p.name(), saved.getId());
            ids.add(saved.getId());
            store.audit(AuditEntry.of(runId, Type.ARTIFACT_STORED).task(key, attempt).agent(agentId(agent))
                    .mode(run.getExecutionMode().name(), p.model())
                    .refs(List.of(p.name() + "@v" + saved.getVersion() + "#" + Hashes.shortHash(saved.getContentHash())))
                    .message(p.name() + "@v" + saved.getVersion() + " (" + p.origin() + (outcome.fallback() ? ", FALLBACK" : "")
                            + ") produced from " + labels(read) + ".")
                    .details(Map.of("inputs", read.stream().map(WorkflowEngine::refLabel).toList(), "origin", p.origin().name()))
                    .build());
        }
        for (AgentResult.Produced p : result.artifacts()) {
            if (p.content() instanceof com.schwab.urlshortener.workflow.workspace.ChangePatch patch) {
                auditPatch(runId, key, attempt, agent, patch);
            }
        }
        store.audit(AuditEntry.of(runId, Type.AGENT_RESULT).task(key, attempt).agent(agentId(agent))
                .mode(run.getExecutionMode().name(), result.artifacts().stream().map(AgentResult.Produced::model)
                        .filter(java.util.Objects::nonNull).findFirst().orElse(null))
                .message(result.summary())
                .details(Map.of(
                        "status", result.status().name(),
                        "decisions", result.decisions(),
                        "assumptions", result.assumptions(),
                        "risks", result.risks(),
                        "validationEvidence", result.validationEvidence(),
                        "usage", result.usage()))
                .build());

        GateResult exit = GateResult.evaluate(GateResult.Kind.EXIT, agent.exitCriteria(), GateInput.exit(used, outputs));
        String gatesJson = store.json(List.of(entry, exit));
        store.updateTask(taskId, t -> t.setExitGateJson(store.json(exit)));
        gateEvent(runId, key, attempt, agent, exit);
        if (!exit.passed()) {
            store.updateArtifacts(ids, a -> a.setGatesJson(gatesJson));
            compensate(runId, key, attempt, agent, used, "exit gate failed");
            failTask(runId, taskId, key, attempt, "Exit gate failed: " + exit.failedIds()
                    + ". Produced versions are kept but not accepted. Not retried (gate/policy failure).");
            return;
        }

        WorkflowTask current = store.task(runId, key);
        String reviewReason = reviewReason(current, result.reviewReason(), outcome.fallback());
        TaskState target = reviewReason != null ? TaskState.WAITING_APPROVAL : TaskState.SUCCEEDED;
        store.acceptOutputs(taskId, ids, gatesJson, t -> {
            t.setState(target);
            t.setFingerprint(fingerprint);
            t.setOutputRefsJson(store.json(refs));
            t.setReviewReason(reviewReason);
            t.setFallbackUsed(outcome.fallback());
            t.setFinishedAt(Instant.now());
            t.setErrorMessage(null);
        });
        int staled = store.staleApprovals(runId, key, ids, "superseded: " + key + " produced new output "
                + refs.entrySet().stream().map(e -> e.getKey() + "#" + e.getValue()).collect(Collectors.joining(", ")));
        if (target == TaskState.WAITING_APPROVAL) {
            store.audit(AuditEntry.of(runId, Type.APPROVAL_REQUESTED).task(key, attempt).agent(agentId(agent))
                    .refs(ids.stream().map(id -> {
                        WorkflowArtifact a = store.artifact(id);
                        return a.getName() + "@v" + a.getVersion() + "#" + Hashes.shortHash(a.getContentHash());
                    }).toList())
                    .message("Human approval required: " + reviewReason + (staled > 0 ? " (" + staled + " earlier approval(s) went stale.)" : ""))
                    .build());
        } else {
            store.event(runId, key, attempt, Type.TASK_SUCCEEDED, "Accepted " + String.join(", ", refs.keySet()) + ".");
        }
    }

    /**
     * The attempt loop: transient failures are retried with backoff, an agent
     * failure or exhausted retries takes the defined fallback where allowed, and
     * everything else fails. Returns null when the task failed or was stopped
     * (state already recorded).
     */
    private Outcome attemptWithRecovery(WorkflowRun run, Long taskId, String key, StageAgent agent, AgentRequest first) {
        Long runId = run.getId();
        WorkflowProperties.Retry retry = properties.retry();
        int driveAttempts = 0;
        AgentRequest request = first;
        String lastFailure = null;
        while (true) {
            int attempt = store.updateTask(taskId, t -> t.setAttempts(t.getAttempts() + 1)).getAttempts();
            driveAttempts++;
            request = buildRequest(run, key, attempt, agent, first.inputRefs());
            store.audit(AuditEntry.of(runId, Type.TASK_ATTEMPT_STARTED).task(key, attempt).agent(agentId(agent))
                    .mode(run.getExecutionMode().name(), run.getModel()).message("Attempt " + driveAttempts + " of at most "
                            + retry.maxAttempts() + " in this drive.").build());
            try {
                AgentResult result = invokeWithTimeout(run, key, attempt, agent, request);
                if (result.status() == AgentResult.Status.FAILED) {
                    throw new AgentFailures.AgentFailure("Agent reported FAILED: " + result.failureReason());
                }
                return new Outcome(result, request, false);
            } catch (AgentFailures.Transient e) {
                lastFailure = e.getMessage();
                attemptFailed(runId, key, attempt, agent, "TRANSIENT", e.getMessage());
                compensate(runId, key, attempt, agent, request, "transient failure");
                if (driveAttempts >= retry.maxAttempts()) {
                    store.event(runId, key, attempt, Type.TASK_RETRIES_EXHAUSTED, "Transient failures persisted through "
                            + driveAttempts + " attempts: " + lastFailure);
                    meters.counter("workflow.task.retries_exhausted").increment();
                    return fallbackOrFail(run, taskId, key, agent, request, "retries exhausted: " + lastFailure);
                }
                Duration backoff = retry.backoffBefore(driveAttempts + 1);
                store.audit(AuditEntry.of(runId, Type.TASK_RETRY_SCHEDULED).task(key, attempt).agent(agentId(agent))
                        .message("Retrying after " + backoff.toMillis() + " ms (attempt " + (driveAttempts + 1) + " of " + retry.maxAttempts() + ").")
                        .details(Map.of("backoffMs", backoff.toMillis(), "cause", e.getMessage())).build());
                meters.counter("workflow.task.retries").increment();
                store.updateTask(taskId, t -> t.setRetries(t.getRetries() + 1));
                if (!sleepUnlessHalted(runId, backoff)) {
                    WorkflowRun now = store.run(runId);
                    String why = now.isCancelRequested() ? "cancel" : "stop";
                    store.updateTask(taskId, t -> {
                        t.setState(TaskState.PENDING);
                        t.setErrorMessage("Retry abandoned: " + why + " requested during backoff.");
                    });
                    return null;
                }
            } catch (AgentFailures.ToolNotPermitted e) {
                attemptFailed(runId, key, attempt, agent, "TOOL_NOT_PERMITTED", e.getMessage());
                compensate(runId, key, attempt, agent, request, "tool policy violation");
                failTask(runId, taskId, key, attempt, "Tool policy violation: " + e.getMessage() + ". Not retried.");
                return null;
            } catch (AgentFailures.AgentFailure e) {
                attemptFailed(runId, key, attempt, agent, e instanceof AgentFailures.BudgetExceeded ? "BUDGET" : "AGENT_FAILURE", e.getMessage());
                compensate(runId, key, attempt, agent, request, "agent failure");
                return fallbackOrFail(run, taskId, key, agent, request, e.getMessage());
            } catch (RuntimeException e) {
                String msg = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                attemptFailed(runId, key, attempt, agent, "ERROR", msg);
                compensate(runId, key, attempt, agent, request, "agent error");
                failTask(runId, taskId, key, attempt, "Agent failed: " + msg + ". Not retried (not a transient failure).");
                return null;
            }
        }
    }

    private Outcome fallbackOrFail(WorkflowRun run, Long taskId, String key, StageAgent agent, AgentRequest request, String cause) {
        Long runId = run.getId();
        boolean eligible = run.getExecutionMode() == ExecutionMode.LLM && agent.supportsDeterministicFallback()
                && WorkflowProperties.FALLBACK_DETERMINISTIC_WITH_REVIEW.equals(properties.fallback());
        if (!eligible) {
            String why = run.getExecutionMode() != ExecutionMode.LLM ? "no fallback in DETERMINISTIC_DEMO mode"
                    : !agent.supportsDeterministicFallback() ? "agent has no deterministic fallback" : "fallback disabled by app.workflow.fallback=fail";
            failTask(runId, taskId, key, store.task(runId, key).getAttempts(), "Agent could not complete: " + cause + " (" + why + ").");
            return null;
        }
        int attempt = store.updateTask(taskId, t -> t.setAttempts(t.getAttempts() + 1)).getAttempts();
        AgentRequest fallback = buildRequest(run, key, attempt, agent, request.inputRefs()).asFallback();
        store.audit(AuditEntry.of(runId, Type.TASK_FALLBACK).task(key, attempt).agent(agentId(agent))
                .mode(run.getExecutionMode().name(), run.getModel())
                .message("LLM path could not complete (" + cause + "); running the defined fallback: the deterministic path, "
                        + "labeled DETERMINISTIC_DEMO, gated on human approval.").build());
        meters.counter("workflow.task.fallbacks").increment();
        try {
            AgentResult result = invokeWithTimeout(run, key, attempt, agent, fallback);
            if (result.status() == AgentResult.Status.FAILED) {
                throw new AgentFailures.AgentFailure("Fallback reported FAILED: " + result.failureReason());
            }
            return new Outcome(result, fallback, true);
        } catch (RuntimeException e) {
            attemptFailed(runId, key, attempt, agent, "FALLBACK_FAILED", e.getMessage());
            compensate(runId, key, attempt, agent, fallback, "fallback failure");
            failTask(runId, taskId, key, attempt, "Fallback also failed: " + e.getMessage());
            return null;
        }
    }

    private AgentResult invokeWithTimeout(WorkflowRun run, String key, int attempt, StageAgent agent, AgentRequest request) {
        Map<String, Integer> faults = faults(run);
        if (run.getExecutionMode() == ExecutionMode.DETERMINISTIC_DEMO && attempt <= faults.getOrDefault(key, 0)) {
            throw new AgentFailures.Transient("Injected fault (demo, configured at run creation): attempt " + attempt + " of "
                    + faults.get(key) + " scheduled transient failures");
        }
        Duration timeout = properties.limits().taskTimeout();
        Future<AgentResult> future = agentPool.submit(() -> agent.execute(request));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AgentFailures.Transient("Attempt exceeded the task timeout of " + timeout);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AgentFailures.Transient("Interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new AgentFailures.AgentFailure("Agent threw " + cause.getClass().getSimpleName(), cause);
        }
    }

    private boolean sleepUnlessHalted(Long runId, Duration backoff) {
        long deadline = System.currentTimeMillis() + backoff.toMillis();
        while (System.currentTimeMillis() < deadline) {
            WorkflowRun r = store.run(runId);
            if (r.isCancelRequested() || r.isStopRequested()) {
                return false;
            }
            try {
                Thread.sleep(Math.min(50, Math.max(1, deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** Workspace side effects and validation-command evidence of a patch, as first-class audit events. */
    private void auditPatch(Long runId, String key, int attempt, StageAgent agent, com.schwab.urlshortener.workflow.workspace.ChangePatch patch) {
        if (patch.applied()) {
            store.audit(AuditEntry.of(runId, Type.WORKSPACE_PATCH_APPLIED).task(key, attempt).agent(agentId(agent))
                    .message("Patch applied in the isolated worktree at base " + Hashes.shortHash(patch.baseCommit()) + ": "
                            + (patch.diffStat() == null ? "" : patch.diffStat().lines().reduce((a, b) -> b).orElse("")))
                    .details(Map.of("files", patch.files().stream().map(f -> f.path() + " (" + f.action() + ")").toList(),
                            "protectedPaths", patch.protectedPathsTouched()))
                    .build());
        }
        for (com.schwab.urlshortener.workflow.workspace.ChangePatch.CommandEvidence v : patch.validation()) {
            store.audit(AuditEntry.of(runId, Type.VALIDATION_COMMAND).task(key, attempt).agent(agentId(agent))
                    .message("Validation command '" + v.name() + "' " + (v.passed() ? "passed" : "FAILED") + " (exit " + v.exitCode()
                            + (v.timedOut() ? ", timed out" : "") + ", " + v.durationMs() + " ms).")
                    .details(Map.of("argv", v.argv(), "exitCode", String.valueOf(v.exitCode()), "passed", v.passed()))
                    .build());
        }
    }

    private void attemptFailed(Long runId, String key, int attempt, StageAgent agent, String kind, String message) {
        store.audit(AuditEntry.of(runId, Type.TASK_ATTEMPT_FAILED).task(key, attempt).agent(agentId(agent))
                .message(kind + ": " + message).details(Map.of("failureKind", kind)).build());
    }

    private void compensate(Long runId, String key, int attempt, StageAgent agent, AgentRequest request, String why) {
        try {
            agent.compensate(request).ifPresent(done -> {
                store.audit(AuditEntry.of(runId, Type.WORKSPACE_ROLLED_BACK).task(key, attempt).agent(agentId(agent))
                        .message("Compensation after " + why + ": " + done + ".").build());
                meters.counter("workflow.workspace.rollbacks").increment();
            });
        } catch (RuntimeException e) {
            log.error("Compensation for task {} of run {} failed", key, runId, e);
            store.event(runId, key, attempt, Type.WORKSPACE_ROLLED_BACK, "Compensation FAILED after " + why + ": " + e.getMessage());
        }
    }

    private String reviewReason(WorkflowTask task, String agentReason, boolean fallback) {
        List<String> reasons = new ArrayList<>();
        if (task.isRequiresApproval()) {
            reasons.add("approval checkpoint required for task '" + task.getTaskKey() + "'");
        }
        if (agentReason != null) {
            reasons.add(agentReason);
        }
        if (fallback) {
            reasons.add("output came from the deterministic FALLBACK after the LLM path failed");
        }
        return reasons.isEmpty() ? null : truncate(String.join("; ", reasons));
    }

    private AgentRequest buildRequest(WorkflowRun run, String key, int attempt, StageAgent agent, Map<String, ArtifactRef> inputs) {
        List<String> constraints = new ArrayList<>(agent.constraints());
        constraints.add("Execution mode " + run.getExecutionMode() + (run.getModel() == null ? "" : " (" + run.getModel() + ")") + ".");
        constraints.add("Time limit " + properties.limits().taskTimeout() + " per attempt; tools limited to " + agent.toolPolicy().allowedTools() + ".");
        List<String> criteria = agent.exitCriteria().stream().map(c -> c.id() + ": " + c.description()).toList();
        Path workspace = run.getWorkspacePath() == null ? null : Path.of(run.getWorkspacePath());
        AgentRequest.Limits limits = new AgentRequest.Limits(properties.limits().taskTimeout(),
                properties.limits().maxTokensPerRun(), properties.limits().maxCostUsdPerRun(), properties.limits().maxOutputTokensPerCall());
        return new AgentRequest(run.getId(), key, attempt, agentId(agent), run.getTitle(),
                agent.readsRequirement() ? run.getRequirementText() : null, run.getExecutionMode(), run.getTestExecution(), false,
                agent.intent(), constraints, criteria,
                new AgentRequest.RepositoryContext(run.getBaseCommit(), workspace, registry.all().size()),
                agent.toolPolicy(), limits, inputs,
                (ref, type) -> store.read(store.artifact(ref.artifactId()).getContentJson(), type));
    }

    private void failTask(Long runId, Long taskId, String key, int attempt, String reason) {
        store.updateTask(taskId, t -> {
            t.setState(TaskState.FAILED);
            t.setErrorMessage(truncate(reason));
            t.setFinishedAt(Instant.now());
        });
        store.event(runId, key, attempt, Type.TASK_FAILED, reason);
    }

    private void gateEvent(Long runId, String key, int attempt, StageAgent agent, GateResult gate) {
        Type type = gate.kind() == GateResult.Kind.ENTRY
                ? (gate.passed() ? Type.ENTRY_GATE_PASSED : Type.ENTRY_GATE_FAILED)
                : (gate.passed() ? Type.EXIT_GATE_PASSED : Type.EXIT_GATE_FAILED);
        store.audit(AuditEntry.of(runId, type).task(key, attempt).agent(agentId(agent))
                .message(gate.kind() + " gate " + (gate.passed() ? "passed" : "FAILED") + ": " + gate.criteria().stream()
                        .map(o -> o.id() + (o.passed() ? " ok" : " FAILED (" + o.detail() + ")")).collect(Collectors.joining("; ")))
                .details(Map.of("criteria", gate.criteria())).build());
    }

    // ------------------------------------------------------------------ end of drive

    private void finish(Long runId, Graph graph) {
        WorkflowRun run = store.run(runId);
        List<WorkflowTask> tasks = store.tasks(runId);
        if (run.isCancelRequested()) {
            cancelTasks(run, tasks, "engine");
            return;
        }
        if (run.isStopRequested()) {
            for (WorkflowTask t : tasks) {
                if (t.getState() == TaskState.READY) {
                    store.updateTask(t.getId(), x -> x.setState(TaskState.PENDING));
                }
            }
            store.updateRun(runId, r -> {
                r.setStatus(RunStatus.STOPPED);
                r.setStopRequested(false);
            });
            store.event(runId, null, null, Type.RUN_STOPPED, "Safe-stopped: in-flight tasks finished, nothing new started, all work kept. "
                    + "Resume to continue.");
            return;
        }
        List<String> failed = tasks.stream().filter(t -> t.getState() == TaskState.FAILED).map(WorkflowTask::getTaskKey).toList();
        List<String> skipped = tasks.stream().filter(t -> t.getState() == TaskState.SKIPPED).map(WorkflowTask::getTaskKey).toList();
        List<String> waiting = tasks.stream().filter(t -> t.getState() == TaskState.WAITING_APPROVAL).map(WorkflowTask::getTaskKey).toList();
        if (tasks.stream().allMatch(t -> t.getState() == TaskState.SUCCEEDED)) {
            store.updateRun(runId, r -> {
                r.setStatus(RunStatus.SUCCEEDED);
                r.setCompletedAt(Instant.now());
            });
            store.event(runId, null, null, Type.RUN_SUCCEEDED, "All " + tasks.size() + " tasks SUCCEEDED.");
            meters.counter("workflow.runs.completed", "outcome", "SUCCEEDED").increment();
        } else if (!failed.isEmpty()) {
            String reason = "Failed: " + failed + (skipped.isEmpty() ? "" : "; skipped: " + skipped) + ".";
            store.updateRun(runId, r -> {
                r.setStatus(RunStatus.FAILED);
                r.setFailureReason(truncate(reason));
                r.setCompletedAt(Instant.now());
            });
            store.event(runId, null, null, Type.RUN_FAILED, reason);
            meters.counter("workflow.runs.completed", "outcome", "FAILED").increment();
        } else if (!waiting.isEmpty()) {
            store.updateRun(runId, r -> r.setStatus(RunStatus.WAITING_APPROVAL));
            store.event(runId, null, null, Type.RUN_WAITING_APPROVAL, "Waiting for a human decision on " + waiting + ".");
        } else {
            store.updateRun(runId, r -> {
                r.setStatus(RunStatus.PAUSED);
                r.setFailureReason("No task could make progress.");
            });
        }
    }

    /** Cancels every non-terminal task and rolls back the run's workspace. Also used for cancelling an idle run. */
    public void cancelTasks(WorkflowRun run, List<WorkflowTask> tasks, String actor) {
        Long runId = run.getId();
        for (WorkflowTask t : tasks) {
            if (CANCELLABLE.contains(t.getState())) {
                store.event(runId, t.getTaskKey(), t.getAttempts(), Type.TASK_CANCELLED, "Run cancelled while " + t.getState() + ".");
                store.updateTask(t.getId(), actor, x -> x.setState(TaskState.CANCELLED));
            }
        }
        rollbackWorkspace(run, actor, "run cancelled");
        store.updateRun(runId, r -> {
            r.setStatus(RunStatus.CANCELLED);
            r.setCancelRequested(false);
            r.setCompletedAt(Instant.now());
        });
        store.audit(AuditEntry.of(runId, Type.RUN_CANCELLED).actor(actor)
                .message("Cancelled. SUCCEEDED tasks keep their outputs for resume; the workspace was rolled back to its base commit.").build());
        meters.counter("workflow.runs.completed", "outcome", "CANCELLED").increment();
    }

    /** Resets the run's worktree to its base commit, if it has one. Returns false when there was nothing to roll back. */
    public boolean rollbackWorkspace(WorkflowRun run, String actor, String why) {
        if (run.getWorkspacePath() == null || run.getBaseCommit() == null) {
            return false;
        }
        WorkspaceManager.Workspace ws = new WorkspaceManager.Workspace(run.getId(), Path.of(run.getWorkspacePath()), run.getBaseCommit());
        workspaces.reset(ws);
        store.audit(AuditEntry.of(run.getId(), Type.WORKSPACE_ROLLED_BACK).actor(actor)
                .message("Workspace rolled back to base " + Hashes.shortHash(run.getBaseCommit()) + " (" + why + "); worktree status after rollback: "
                        + (workspaces.status(ws).isBlank() ? "clean" : "NOT clean")).build());
        meters.counter("workflow.workspace.rollbacks").increment();
        return true;
    }

    // ------------------------------------------------------------------ crash recovery

    /**
     * A run still marked RUNNING when the application starts was being driven by a
     * process that no longer exists. Mark it INTERRUPTED so an operator can see it
     * and resume it. Resume turns its RUNNING tasks back into PENDING.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        for (WorkflowRun run : store.runsWithStatus(RunStatus.RUNNING)) {
            if (isActive(run.getId())) {
                continue;
            }
            store.updateRun(run.getId(), r -> r.setStatus(RunStatus.INTERRUPTED));
            store.audit(AuditEntry.of(run.getId(), Type.RUN_INTERRUPTED).actor("system:startup")
                    .message("Found RUNNING at application startup; the driving process stopped. Resume to continue.").build());
            log.warn("Workflow run {} was RUNNING at startup; marked INTERRUPTED", run.getId());
        }
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Integer> faults(WorkflowRun run) {
        if (run.getFaultInjectionJson() == null || run.getFaultInjectionJson().isBlank()) {
            return Map.of();
        }
        try {
            return store.objectMapper().readValue(run.getFaultInjectionJson(), new TypeReference<Map<String, Integer>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private AgentResult.Envelope envelope(WorkflowArtifact a) {
        if (a.getResultJson() == null) {
            return null;
        }
        try {
            return store.read(a.getResultJson(), AgentResult.Envelope.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String agentId(StageAgent agent) {
        return agent.key() + "@" + agent.version();
    }

    private static String adapterName(ArtifactOrigin origin) {
        return switch (origin) {
            case LLM -> "anthropic-llm";
            case DETERMINISTIC_DEMO -> "deterministic-demo";
            case DETERMINISTIC -> "deterministic";
            case EXTERNAL_COMMAND -> "command";
        };
    }

    static String refLabel(ArtifactRef ref) {
        return ref.label() + "#" + Hashes.shortHash(ref.contentHash());
    }

    private static String labels(java.util.Collection<ArtifactRef> refs) {
        return refs.isEmpty() ? "[]" : refs.stream().map(ArtifactRef::label).toList().toString();
    }

    static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 997) + "...";
    }
}
