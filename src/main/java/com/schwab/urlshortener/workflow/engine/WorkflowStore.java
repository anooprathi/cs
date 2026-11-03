package com.schwab.urlshortener.workflow.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.exception.WorkflowNotFoundException;
import com.schwab.urlshortener.workflow.audit.AuditEntry;
import com.schwab.urlshortener.workflow.audit.AuditLog;
import com.schwab.urlshortener.workflow.persistence.WorkflowApproval;
import com.schwab.urlshortener.workflow.persistence.WorkflowApprovalRepository;
import com.schwab.urlshortener.workflow.persistence.WorkflowArtifact;
import com.schwab.urlshortener.workflow.persistence.WorkflowArtifactRepository;
import com.schwab.urlshortener.workflow.persistence.WorkflowEvent;
import com.schwab.urlshortener.workflow.persistence.WorkflowLineageEdge;
import com.schwab.urlshortener.workflow.persistence.WorkflowLineageRepository;
import com.schwab.urlshortener.workflow.persistence.WorkflowRun;
import com.schwab.urlshortener.workflow.persistence.WorkflowRunRepository;
import com.schwab.urlshortener.workflow.persistence.WorkflowTask;
import com.schwab.urlshortener.workflow.persistence.WorkflowTaskRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * All persistence for the workflow engine. Every method runs in its own short
 * transaction and reloads the row it mutates, so the driver thread and task
 * worker threads never hold an entity across a transaction boundary, and
 * {@code @Version} catches any write that does interleave.
 *
 * Short transactions are deliberate: workflow state is the source of truth for
 * resume, so each transition must be durable when it happens.
 *
 * Every task state change made through {@link #updateTask} automatically
 * appends a TASK_TRANSITION audit event (from → to). No transition can go
 * unrecorded, whichever code path made it.
 */
@Component
public class WorkflowStore {

    private final WorkflowRunRepository runs;
    private final WorkflowTaskRepository tasks;
    private final WorkflowArtifactRepository artifacts;
    private final WorkflowLineageRepository lineage;
    private final WorkflowApprovalRepository approvals;
    private final AuditLog audit;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public WorkflowStore(WorkflowRunRepository runs, WorkflowTaskRepository tasks, WorkflowArtifactRepository artifacts,
                         WorkflowLineageRepository lineage, WorkflowApprovalRepository approvals, AuditLog audit,
                         ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.tasks = tasks;
        this.artifacts = artifacts;
        this.lineage = lineage;
        this.approvals = approvals;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ---------- runs ----------

    public WorkflowRun run(Long runId) {
        return runs.findById(runId).orElseThrow(() -> new WorkflowNotFoundException(runId));
    }

    public List<WorkflowRun> allRuns() {
        return runs.findAll();
    }

    public WorkflowRun createRun(WorkflowRun run, List<WorkflowTask> taskRows) {
        return tx.execute(s -> {
            WorkflowRun saved = runs.save(run);
            taskRows.forEach(t -> {
                t.setRunId(saved.getId());
                tasks.save(t);
            });
            return saved;
        });
    }

    public WorkflowRun updateRun(Long runId, Consumer<WorkflowRun> mutation) {
        return tx.execute(s -> {
            WorkflowRun run = run(runId);
            mutation.accept(run);
            return runs.save(run);
        });
    }

    public List<WorkflowRun> runsWithStatus(RunStatus status) {
        return runs.findByStatus(status);
    }

    public org.springframework.data.domain.Page<WorkflowRun> runs(org.springframework.data.domain.Pageable pageable) {
        return runs.findAll(pageable);
    }

    // ---------- tasks ----------

    public List<WorkflowTask> tasks(Long runId) {
        return tasks.findByRunIdOrderByIdAsc(runId);
    }

    public Map<String, WorkflowTask> tasksByKey(Long runId) {
        Map<String, WorkflowTask> map = new LinkedHashMap<>();
        tasks(runId).forEach(t -> map.put(t.getTaskKey(), t));
        return map;
    }

    public WorkflowTask task(Long runId, String taskKey) {
        return tasks.findByRunIdAndTaskKey(runId, taskKey)
                .orElseThrow(() -> new WorkflowNotFoundException(runId, "task", taskKey));
    }

    public WorkflowTask updateTask(Long taskId, Consumer<WorkflowTask> mutation) {
        return updateTask(taskId, "engine", mutation);
    }

    /** Mutates a task. If its state changed, appends a TASK_TRANSITION event attributed to {@code actor}. */
    public WorkflowTask updateTask(Long taskId, String actor, Consumer<WorkflowTask> mutation) {
        WorkflowTask[] before = new WorkflowTask[1];
        WorkflowTask saved = tx.execute(s -> {
            WorkflowTask task = tasks.findById(taskId).orElseThrow(() -> new IllegalStateException("Task row " + taskId + " vanished"));
            before[0] = WorkflowTask.builder().state(task.getState()).build();
            mutation.accept(task);
            return tasks.save(task);
        });
        if (saved == null) {
            throw new IllegalStateException("task update returned no row");
        }
        if (before[0].getState() != saved.getState()) {
            audit.append(AuditEntry.of(saved.getRunId(), WorkflowEvent.Type.TASK_TRANSITION)
                    .task(saved.getTaskKey(), saved.getAttempts())
                    .agent(saved.getAgentKey() + "@" + saved.getAgentVersion())
                    .transition(before[0].getState().name(), saved.getState().name())
                    .actor(actor)
                    .message(saved.getErrorMessage())
                    .build());
        }
        return saved;
    }

    // ---------- artifacts ----------

    public WorkflowArtifact artifact(Long artifactId) {
        return artifacts.findById(artifactId).orElseThrow(() -> new IllegalStateException("Artifact " + artifactId + " vanished"));
    }

    public List<WorkflowArtifact> artifacts(Long runId) {
        return artifacts.findByRunIdOrderByIdAsc(runId);
    }

    public List<WorkflowArtifact> artifactVersions(Long runId, String name) {
        return artifacts.findByRunIdAndNameOrderByVersionAsc(runId, name);
    }

    public Optional<WorkflowArtifact> artifactVersion(Long runId, String name, int version) {
        return artifacts.findByRunIdAndNameAndVersion(runId, name, version);
    }

    public Optional<WorkflowArtifact> reusable(Long runId, String name, String fingerprint) {
        return artifacts.findFirstByRunIdAndNameAndInputFingerprintAndAcceptedTrueAndInvalidatedFalseOrderByVersionDesc(
                runId, name, fingerprint);
    }

    /**
     * Stores a new version (max + 1) as NOT accepted, with lineage edges to the
     * inputs actually read. Graph validation guarantees one producer per artifact
     * name per run, so the version number can't race.
     */
    public WorkflowArtifact storeArtifact(WorkflowArtifact draft, List<ArtifactRef> inputsRead) {
        return tx.execute(s -> {
            draft.setVersion(artifacts.maxVersion(draft.getRunId(), draft.getName()) + 1);
            draft.setAccepted(false);
            WorkflowArtifact saved = artifacts.save(draft);
            for (ArtifactRef in : inputsRead) {
                lineage.save(WorkflowLineageEdge.builder()
                        .runId(saved.getRunId()).outputArtifactId(saved.getId())
                        .inputArtifactId(in.artifactId()).inputName(in.name()).inputVersion(in.version()).inputHash(in.contentHash())
                        .build());
            }
            return saved;
        });
    }

    /** Marks artifacts accepted AND moves the task to its success state in one transaction, so neither happens without the other. */
    public WorkflowTask acceptOutputs(Long taskId, List<Long> artifactIds, String gatesJson, Consumer<WorkflowTask> taskMutation) {
        WorkflowTask[] before = new WorkflowTask[1];
        WorkflowTask saved = tx.execute(s -> {
            for (Long id : artifactIds) {
                WorkflowArtifact a = artifact(id);
                a.setAccepted(true);
                a.setGatesJson(gatesJson);
                artifacts.save(a);
            }
            WorkflowTask task = tasks.findById(taskId).orElseThrow();
            before[0] = WorkflowTask.builder().state(task.getState()).build();
            taskMutation.accept(task);
            return tasks.save(task);
        });
        if (saved == null) {
            throw new IllegalStateException("acceptOutputs returned no row");
        }
        if (before[0].getState() != saved.getState()) {
            audit.append(AuditEntry.of(saved.getRunId(), WorkflowEvent.Type.TASK_TRANSITION)
                    .task(saved.getTaskKey(), saved.getAttempts()).agent(saved.getAgentKey() + "@" + saved.getAgentVersion())
                    .transition(before[0].getState().name(), saved.getState().name()).build());
        }
        return saved;
    }

    public void updateArtifacts(List<Long> artifactIds, Consumer<WorkflowArtifact> mutation) {
        tx.executeWithoutResult(s -> artifactIds.forEach(id -> {
            WorkflowArtifact a = artifact(id);
            mutation.accept(a);
            artifacts.save(a);
        }));
    }

    public List<WorkflowLineageEdge> lineageOf(Long outputArtifactId) {
        return lineage.findByOutputArtifactIdOrderByIdAsc(outputArtifactId);
    }

    public List<WorkflowLineageEdge> consumersOf(Long inputArtifactId) {
        return lineage.findByInputArtifactIdOrderByIdAsc(inputArtifactId);
    }

    // ---------- approvals ----------

    public List<WorkflowApproval> approvals(Long runId) {
        return approvals.findByRunIdOrderByIdAsc(runId);
    }

    public List<WorkflowApproval> validApprovals(Long runId, String taskKey) {
        return approvals.findByRunIdAndTaskKeyAndStatus(runId, taskKey, WorkflowApproval.Status.VALID);
    }

    /** True if every given artifact has a VALID approval with decision APPROVED bound to it. */
    public boolean approvedVersions(Long runId, String taskKey, Map<String, Long> outputRefs) {
        if (outputRefs.isEmpty()) {
            return false;
        }
        List<WorkflowApproval> valid = validApprovals(runId, taskKey);
        return outputRefs.values().stream().allMatch(id -> valid.stream()
                .anyMatch(a -> a.getArtifactId().equals(id) && a.getDecision() == WorkflowApproval.Decision.APPROVED));
    }

    public WorkflowApproval recordApproval(WorkflowApproval approval) {
        return tx.execute(s -> approvals.save(approval));
    }

    /**
     * Marks STALE every VALID approval of {@code taskKey} bound to an artifact
     * NOT in {@code currentArtifactIds} (all of them when the set is empty), and
     * appends an APPROVAL_STALE event for each. Returns how many went stale.
     */
    public int staleApprovals(Long runId, String taskKey, java.util.Collection<Long> currentArtifactIds, String reason) {
        List<WorkflowApproval> stale = tx.execute(s -> {
            List<WorkflowApproval> changed = new java.util.ArrayList<>();
            for (WorkflowApproval a : approvals.findByRunIdAndTaskKeyAndStatus(runId, taskKey, WorkflowApproval.Status.VALID)) {
                if (!currentArtifactIds.contains(a.getArtifactId())) {
                    a.setStatus(WorkflowApproval.Status.STALE);
                    a.setStaleReason(reason.length() > 500 ? reason.substring(0, 497) + "..." : reason);
                    a.setStaledAt(Instant.now());
                    changed.add(approvals.save(a));
                }
            }
            return changed;
        });
        if (stale == null) {
            return 0;
        }
        for (WorkflowApproval a : stale) {
            audit.append(AuditEntry.of(runId, WorkflowEvent.Type.APPROVAL_STALE).task(taskKey, null)
                    .refs(List.of(a.getArtifactName() + "@v" + a.getArtifactVersion() + "#" + Hashes.shortHash(a.getArtifactHash())))
                    .message("Approval #" + a.getId() + " (" + a.getDecision() + " by " + a.getReviewer() + ") is stale: " + reason)
                    .details(Map.of("approvalId", a.getId(), "reviewer", a.getReviewer(), "decidedAt", String.valueOf(a.getDecidedAt())))
                    .build());
        }
        return stale.size();
    }

    // ---------- audit ----------

    public void event(Long runId, String taskKey, Integer attempt, WorkflowEvent.Type type, String message) {
        audit.append(AuditEntry.of(runId, type).task(taskKey, attempt).message(message).build());
    }

    public void audit(AuditEntry entry) {
        audit.append(entry);
    }

    public List<WorkflowEvent> history(Long runId) {
        return audit.events(runId);
    }

    public AuditLog auditLog() {
        return audit;
    }

    // ---------- JSON helpers ----------

    public Map<String, Long> outputRefs(WorkflowTask task) {
        if (task.getOutputRefsJson() == null || task.getOutputRefsJson().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(task.getOutputRefsJson(), new TypeReference<LinkedHashMap<String, Long>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable outputRefsJson on task " + task.getId(), e);
        }
    }

    public String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize " + value.getClass().getSimpleName(), e);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to read stored " + type.getSimpleName(), e);
        }
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }
}
