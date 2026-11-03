package com.schwab.urlshortener.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.dto.PageResponse;
import com.schwab.urlshortener.exception.InvalidWorkflowDefinitionException;
import com.schwab.urlshortener.exception.LlmUnavailableException;
import com.schwab.urlshortener.exception.WorkflowCapacityException;
import com.schwab.urlshortener.exception.WorkflowConflictException;
import com.schwab.urlshortener.exception.WorkflowNotFoundException;
import com.schwab.urlshortener.workflow.adapter.AdapterRouter;
import com.schwab.urlshortener.workflow.agents.Artifacts;
import com.schwab.urlshortener.workflow.api.WorkflowRequests;
import com.schwab.urlshortener.workflow.api.WorkflowViews;
import com.schwab.urlshortener.workflow.artifact.ReadinessReport;
import com.schwab.urlshortener.workflow.audit.AuditEntry;
import com.schwab.urlshortener.workflow.audit.AuditLog;
import com.schwab.urlshortener.workflow.engine.AgentRegistry;
import com.schwab.urlshortener.workflow.engine.Hashes;
import com.schwab.urlshortener.workflow.engine.RunStatus;
import com.schwab.urlshortener.workflow.engine.StageAgent;
import com.schwab.urlshortener.workflow.engine.TaskState;
import com.schwab.urlshortener.workflow.engine.TestExecution;
import com.schwab.urlshortener.workflow.engine.WorkflowEngine;
import com.schwab.urlshortener.workflow.engine.WorkflowStore;
import com.schwab.urlshortener.workflow.graph.GraphValidationResult;
import com.schwab.urlshortener.workflow.graph.GraphValidator;
import com.schwab.urlshortener.workflow.graph.TaskNode;
import com.schwab.urlshortener.workflow.graph.WorkflowDefinition;
import com.schwab.urlshortener.workflow.persistence.WorkflowApproval;
import com.schwab.urlshortener.workflow.persistence.WorkflowArtifact;
import com.schwab.urlshortener.workflow.persistence.WorkflowEvent;
import com.schwab.urlshortener.workflow.persistence.WorkflowLineageEdge;
import com.schwab.urlshortener.workflow.persistence.WorkflowRun;
import com.schwab.urlshortener.workflow.persistence.WorkflowTask;
import com.schwab.urlshortener.workflow.workspace.ChangePatch;
import com.schwab.urlshortener.workflow.workspace.WorkspaceManager;
import com.schwab.urlshortener.agentic.AgenticProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Application facade over the workflow engine: submit a requirement, start,
 * stop, cancel, resume, decide approvals, invalidate, amend the requirement
 * (re-planning), roll back, promote, compensate, and query everything a
 * reviewer needs (status, artifacts, lineage, history, approvals).
 *
 * Every human action requires an actor name and is audited under it. Every
 * action that changes run state first claims the run, so it can't interleave
 * with a driver.
 */
@Service
@Slf4j
public class WorkflowService {

    private static final Set<RunStatus> RESUMABLE = EnumSet.of(RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.STOPPED,
            RunStatus.INTERRUPTED, RunStatus.PAUSED);

    private final WorkflowEngine engine;
    private final WorkflowStore store;
    private final AuditLog audit;
    private final AgentRegistry agents;
    private final GraphValidator validator;
    private final AdapterRouter adapters;
    private final WorkspaceManager workspaces;
    private final ThreadPoolTaskExecutor driverPool;
    private final WorkflowProperties properties;
    private final ExecutionMode defaultMode;

    public WorkflowService(WorkflowEngine engine, WorkflowStore store, AgentRegistry agents, GraphValidator validator,
                           AdapterRouter adapters, WorkspaceManager workspaces,
                           @Qualifier("workflowDriverExecutor") ThreadPoolTaskExecutor driverPool,
                           WorkflowProperties properties, AgenticProperties agenticProperties) {
        this.engine = engine;
        this.store = store;
        this.audit = store.auditLog();
        this.agents = agents;
        this.validator = validator;
        this.adapters = adapters;
        this.workspaces = workspaces;
        this.driverPool = driverPool;
        this.properties = properties;
        this.defaultMode = agenticProperties.defaultMode() == ExecutionMode.LLM && !adapters.llmAvailable()
                ? ExecutionMode.DETERMINISTIC_DEMO : agenticProperties.defaultMode();
        GraphValidationResult standard = validator.validate(WorkflowDefinition.standard(), agents);
        if (!standard.valid()) {
            throw new IllegalStateException("The standard workflow definition is invalid: " + standard.errors());
        }
    }

    // ------------------------------------------------------------------ capabilities & definitions

    public WorkflowViews.Capabilities capabilities() {
        List<ExecutionMode> modes = adapters.llmAvailable()
                ? List.of(ExecutionMode.DETERMINISTIC_DEMO, ExecutionMode.LLM) : List.of(ExecutionMode.DETERMINISTIC_DEMO);
        Map<String, Object> retry = new LinkedHashMap<>();
        retry.put("maxAttempts", properties.retry().maxAttempts());
        retry.put("initialBackoffMs", properties.retry().initialBackoff().toMillis());
        retry.put("multiplier", properties.retry().multiplier());
        retry.put("maxBackoffMs", properties.retry().maxBackoff().toMillis());
        retry.put("retriedFailures", "TRANSIENT only (timeouts, 429/5xx, connection errors). Gate, policy, approval, and tool failures are never retried.");
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxParallelism", properties.maxParallelism());
        limits.put("taskTimeout", properties.limits().taskTimeout().toString());
        limits.put("maxTokensPerRun", properties.limits().maxTokensPerRun());
        limits.put("maxCostUsdPerRun (estimate)", properties.limits().maxCostUsdPerRun());
        limits.put("maxOutputTokensPerCall", properties.limits().maxOutputTokensPerCall());
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("allowedPatchPaths", properties.policy().allowedPatchPaths());
        policy.put("protectedPaths", properties.policy().protectedPaths());
        policy.put("maxPatchFiles", properties.policy().maxPatchFiles());
        policy.put("maxPatchBytes", properties.policy().maxPatchBytes());
        policy.put("validationCommands", properties.policy().validationCommands());
        policy.put("patchValidation", properties.policy().patchValidation());
        policy.put("forbiddenTerms", properties.policy().forbiddenTerms());
        policy.put("clarifyAmbiguities", properties.policy().clarifyAmbiguities());
        policy.put("approvalRequiredTasks", properties.policy().approvalRequiredTasks());
        return new WorkflowViews.Capabilities(defaultMode, modes, adapters.llmModel(),
                adapters.llmAvailable() ? "LLM enabled (ANTHROPIC_API_KEY read by the SDK; never logged)."
                        : "LLM disabled. Set APP_AGENTIC_LLM_ENABLED=true and ANTHROPIC_API_KEY to enable.",
                workspaces.enabled(), workspaces.unavailableReason().orElse("available: sandbox clone under " + workspaces.root()),
                agentsTestRunner(), properties.fallback(), retry, limits, policy,
                agents.all().stream().map(this::agentView).toList());
    }

    private String agentsTestRunner() {
        return "default " + properties.testRunner().mode() + (properties.testRunner().command().isEmpty()
                ? "; COMMAND unavailable (app.workflow.test-runner.command not set)"
                : "; COMMAND runs " + properties.testRunner().command());
    }

    private WorkflowViews.Agent agentView(StageAgent a) {
        return new WorkflowViews.Agent(a.key(), a.version(), a.stage(), a.description(), a.consumes(), a.produces(),
                a.toolPolicy().allowedTools().stream().map(Enum::name).toList(),
                a.entryCriteria().stream().map(c -> c.id() + ": " + c.description()).toList(),
                a.exitCriteria().stream().map(c -> c.id() + ": " + c.description()).toList(),
                a.readsRequirement(), a.supportsDeterministicFallback());
    }

    public WorkflowDefinition standardDefinition() {
        return WorkflowDefinition.standard();
    }

    public WorkflowViews.DefinitionValidation validate(WorkflowDefinition definition) {
        GraphValidationResult r = validator.validate(definition, agents);
        return new WorkflowViews.DefinitionValidation(r.valid(), r.errors(), r.topologicalOrder(), r.parallelLayers());
    }

    // ------------------------------------------------------------------ lifecycle

    public WorkflowViews.Run create(WorkflowRequests.CreateRun request) {
        ExecutionMode mode = request.mode() == null ? defaultMode : request.mode();
        if (mode == ExecutionMode.LLM && !adapters.llmAvailable()) {
            throw new LlmUnavailableException();
        }
        Map<String, Integer> faults = request.demoFaults() == null ? Map.of() : request.demoFaults();
        if (!faults.isEmpty() && mode != ExecutionMode.DETERMINISTIC_DEMO) {
            throw new IllegalArgumentException("demoFaults are only accepted for DETERMINISTIC_DEMO runs.");
        }
        TestExecution testExecution = request.testExecution() == null ? properties.testRunner().defaultExecution() : request.testExecution();
        if (testExecution == TestExecution.COMMAND && properties.testRunner().command().isEmpty()) {
            throw new IllegalArgumentException("testExecution COMMAND needs app.workflow.test-runner.command to be configured.");
        }
        WorkflowDefinition definition = request.definition() == null ? WorkflowDefinition.standard() : request.definition();
        GraphValidationResult validation = validator.validate(definition, agents);
        if (!validation.valid()) {
            throw new InvalidWorkflowDefinitionException(validation.errors().stream()
                    .map(e -> e.code() + ": " + e.message()).toList());
        }
        faults.keySet().forEach(k -> {
            if (definition.tasks().stream().noneMatch(n -> n.key().equals(k))) {
                throw new IllegalArgumentException("demoFaults names unknown task '" + k + "'.");
            }
        });
        String requirement = request.requirement().strip();
        rejectCredentials("requirement", request.title(), requirement);
        List<WorkflowTask> rows = new ArrayList<>();
        for (TaskNode n : definition.tasks()) {
            StageAgent agent = agents.get(n.agent());
            rows.add(WorkflowTask.builder()
                    .taskKey(n.key()).stage(agent.stage()).agentKey(agent.key()).agentVersion(agent.version())
                    .requiresApproval(n.requiresApproval() || properties.policy().approvalRequiredTasks().contains(n.key()))
                    .state(TaskState.PENDING)
                    .build());
        }
        WorkflowRun run = store.createRun(WorkflowRun.builder()
                .title(request.title().strip())
                .requirementText(requirement)
                .requirementHash(Hashes.sha256(requirement))
                .requirementRevision(1)
                .plannedRequirementRevision(1)
                .executionMode(mode)
                .model(mode == ExecutionMode.LLM ? adapters.llmModel() : null)
                .testExecution(testExecution)
                .status(RunStatus.CREATED)
                .definitionName(definition.name())
                .definitionVersion(definition.version())
                .definitionJson(store.json(definition))
                .faultInjectionJson(faults.isEmpty() ? null : store.json(faults))
                .build(), rows);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requirementHash", run.getRequirementHash());
        details.put("requirementLength", requirement.length());
        details.put("definition", definition.name() + " v" + definition.version());
        details.put("testExecution", testExecution);
        details.put("parallelLayers", validation.parallelLayers());
        if (!faults.isEmpty()) {
            details.put("injectedDemoFaults", faults);
        }
        audit.append(AuditEntry.of(run.getId(), WorkflowEvent.Type.RUN_CREATED).actor("api")
                .mode(mode.name(), run.getModel())
                .message("Run created: '" + run.getTitle() + "' (" + mode + ", " + rows.size() + " tasks"
                        + (faults.isEmpty() ? "" : ", injected demo faults " + faults) + ").")
                .details(details).build());
        return view(run.getId());
    }

    public WorkflowViews.Run start(Long runId) {
        WorkflowRun run = store.run(runId);
        if (run.getStatus() != RunStatus.CREATED) {
            throw new WorkflowConflictException("Run " + runId + " is " + run.getStatus() + "; only a CREATED run can be started (use resume).");
        }
        dispatch(runId, WorkflowEngine.DriveMode.START);
        return view(runId);
    }

    public WorkflowViews.Run resume(Long runId) {
        WorkflowRun run = store.run(runId);
        if (run.getStatus() == RunStatus.WAITING_APPROVAL) {
            throw new WorkflowConflictException("Run " + runId + " is waiting for an approval decision; decide it instead of resuming.");
        }
        if (!RESUMABLE.contains(run.getStatus())) {
            throw new WorkflowConflictException("Run " + runId + " is " + run.getStatus() + "; resumable states are " + RESUMABLE + ".");
        }
        dispatch(runId, WorkflowEngine.DriveMode.RESUME);
        return view(runId);
    }

    /** Safe-stop: in-flight tasks finish, nothing new starts, all work is kept, and the run is resumable. */
    public WorkflowViews.Run stop(Long runId, String actor) {
        if (!engine.isActive(runId)) {
            throw new WorkflowConflictException("Run " + runId + " is not executing; nothing to stop.");
        }
        store.updateRun(runId, r -> r.setStopRequested(true));
        audit.append(AuditEntry.of(runId, WorkflowEvent.Type.RUN_STOP_REQUESTED).actor(actor)
                .message("Safe-stop requested by " + actor + ".").build());
        return view(runId);
    }

    /** Cancel: like stop, then cancels every unfinished task and rolls back the workspace. Resumable. */
    public WorkflowViews.Run cancel(Long runId, String actor) {
        WorkflowRun run = store.run(runId);
        if (run.getStatus() == RunStatus.SUCCEEDED || run.getStatus() == RunStatus.CANCELLED) {
            throw new WorkflowConflictException("Run " + runId + " is " + run.getStatus() + "; nothing to cancel.");
        }
        if (engine.isActive(runId)) {
            store.updateRun(runId, r -> r.setCancelRequested(true));
            audit.append(AuditEntry.of(runId, WorkflowEvent.Type.RUN_CANCEL_REQUESTED).actor(actor)
                    .message("Cancel requested by " + actor + " while executing; in-flight tasks finish first.").build());
            return view(runId);
        }
        return withClaim(runId, () -> {
            audit.append(AuditEntry.of(runId, WorkflowEvent.Type.RUN_CANCEL_REQUESTED).actor(actor).message("Cancel requested by " + actor + ".").build());
            engine.cancelTasks(store.run(runId), store.tasks(runId), actor);
            return view(runId);
        });
    }

    // ------------------------------------------------------------------ human checkpoints

    public WorkflowViews.Run decide(Long runId, String taskKey, WorkflowRequests.Decision decision) {
        WorkflowViews.Run result = withClaim(runId, () -> {
            WorkflowTask task = store.task(runId, taskKey);
            if (task.getState() != TaskState.WAITING_APPROVAL) {
                throw new WorkflowConflictException("Task '" + taskKey + "' is " + task.getState() + "; only a WAITING_APPROVAL task can be decided.");
            }
            requireNoPendingReplan(store.run(runId), "deciding on '" + taskKey + "'");
            Map<String, Long> refs = store.outputRefs(task);
            for (Map.Entry<String, Long> e : refs.entrySet()) {
                WorkflowArtifact a = store.artifact(e.getValue());
                Integer seen = decision.artifactVersions().get(e.getKey());
                if (seen == null || seen != a.getVersion()) {
                    throw new WorkflowConflictException("Stale review: '" + e.getKey() + "' is at v" + a.getVersion() + " but the decision names "
                            + (seen == null ? "no version" : "v" + seen) + ". Re-read the artifact and decide on the current version.");
                }
            }
            if (decision.decision() == WorkflowRequests.Decision.Verdict.REJECTED
                    && (decision.comment() == null || decision.comment().isBlank())) {
                throw new IllegalArgumentException("A comment is required when rejecting.");
            }
            Instant now = Instant.now();
            List<String> bound = new ArrayList<>();
            for (Long id : refs.values()) {
                WorkflowArtifact a = store.artifact(id);
                store.recordApproval(WorkflowApproval.builder()
                        .runId(runId).taskKey(taskKey).artifactId(a.getId()).artifactName(a.getName()).artifactVersion(a.getVersion())
                        .artifactHash(a.getContentHash())
                        .decision(decision.decision() == WorkflowRequests.Decision.Verdict.APPROVED
                                ? WorkflowApproval.Decision.APPROVED : WorkflowApproval.Decision.REJECTED)
                        .reviewer(decision.reviewer().strip()).comment(decision.comment()).decidedAt(now)
                        .status(WorkflowApproval.Status.VALID).build());
                bound.add(a.getName() + "@v" + a.getVersion() + "#" + Hashes.shortHash(a.getContentHash()));
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reviewer", decision.reviewer().strip());
            details.put("decidedAt", now.toString());
            details.put("boundTo", bound);
            details.put("reviewReason", task.getReviewReason());
            if (decision.decision() == WorkflowRequests.Decision.Verdict.APPROVED) {
                audit.append(AuditEntry.of(runId, WorkflowEvent.Type.APPROVED).task(taskKey, task.getAttempts()).actor(decision.reviewer().strip())
                        .refs(bound).message("Approved by " + decision.reviewer().strip() + (decision.comment() == null ? "" : ": " + decision.comment()))
                        .details(details).build());
                store.updateTask(task.getId(), decision.reviewer().strip(), t -> {
                    t.setState(TaskState.SUCCEEDED);
                    t.setFinishedAt(now);
                });
            } else {
                audit.append(AuditEntry.of(runId, WorkflowEvent.Type.REJECTED).task(taskKey, task.getAttempts()).actor(decision.reviewer().strip())
                        .refs(bound).message("Rejected by " + decision.reviewer().strip() + ": " + decision.comment()).details(details).build());
                store.updateTask(task.getId(), decision.reviewer().strip(), t -> {
                    t.setState(TaskState.FAILED);
                    t.setErrorMessage("Rejected by " + decision.reviewer().strip() + ": " + decision.comment()
                            + " (not retried automatically; amend the requirement or invalidate, then resume).");
                    t.setFinishedAt(now);
                });
            }
            return null;
        });
        dispatch(runId, WorkflowEngine.DriveMode.CONTINUE);
        return result == null ? view(runId) : result;
    }

    /**
     * Refuses a human decision or promotion while the run's task states no longer reflect its current requirement
     * and inputs (an amendment not yet re-planned, or any completed task whose fingerprint no longer matches).
     * Without this, an approval of output produced for the OLD requirement would complete the run.
     */
    private void requireNoPendingReplan(WorkflowRun run, String action) {
        List<String> reasons = engine.pendingReplanReasons(run);
        if (!reasons.isEmpty()) {
            throw new WorkflowConflictException("Re-planning pending, so " + action + " is refused: " + String.join("; ", reasons)
                    + ". Resume the run to re-plan; it re-produces the affected outputs, which then need a fresh decision.");
        }
    }

    /**
     * Intake guard: a requirement that contains a credential is refused before it is stored, audited, or sent to any
     * model. The message names the kind of credential, never the value.
     */
    static void rejectCredentials(String what, String... texts) {
        List<String> kinds = com.schwab.urlshortener.util.SecretPatterns.kindsFound(texts);
        if (!kinds.isEmpty()) {
            throw new IllegalArgumentException("The " + what + " contains what looks like a " + String.join(", ", kinds)
                    + ". Remove credentials and submit again; nothing was stored.");
        }
    }

    /** Declares a task's current output invalid. It and its dependents re-run on resume (unaffected dependents are reused). */
    public WorkflowViews.Run invalidate(Long runId, String taskKey, WorkflowRequests.Invalidate request) {
        return withClaim(runId, () -> {
            WorkflowRun run = store.run(runId);
            WorkflowTask task = store.task(runId, taskKey);
            if (task.getState() != TaskState.SUCCEEDED && task.getState() != TaskState.WAITING_APPROVAL) {
                throw new WorkflowConflictException("Task '" + taskKey + "' is " + task.getState() + "; only SUCCEEDED or WAITING_APPROVAL output can be invalidated.");
            }
            WorkflowEngine.Graph graph = engine.graph(run);
            engine.invalidateWithDescendants(run, graph, new LinkedHashMap<>(store.tasksByKey(runId)), taskKey,
                    "Invalidated by " + request.actor() + ": " + request.reason(), true);
            store.updateRun(runId, r -> r.setStatus(RunStatus.PAUSED));
            return view(runId);
        });
    }

    /**
     * Amends the requirement. Nothing re-runs yet. On resume, every task whose
     * input fingerprint changed is invalidated with its dependents, and unaffected
     * work (same fingerprint) is preserved. The audit event records hashes and the
     * reason, never the text.
     */
    public WorkflowViews.Run amendRequirement(Long runId, WorkflowRequests.AmendRequirement request) {
        return withClaim(runId, () -> {
            WorkflowRun before = store.run(runId);
            String text = request.requirement().strip();
            rejectCredentials("amended requirement", text);
            String newHash = Hashes.sha256(text);
            if (newHash.equals(before.getRequirementHash())) {
                throw new WorkflowConflictException("The amended requirement is identical to the current one.");
            }
            WorkflowRun after = store.updateRun(runId, r -> {
                r.setRequirementText(text);
                r.setRequirementHash(newHash);
                r.setRequirementRevision(r.getRequirementRevision() + 1);
                if (r.getStatus() != RunStatus.CREATED) {
                    r.setStatus(RunStatus.PAUSED);
                }
            });
            audit.append(AuditEntry.of(runId, WorkflowEvent.Type.REQUIREMENT_UPDATED).actor(request.actor())
                    .message("Requirement amended to revision " + after.getRequirementRevision() + " by " + request.actor() + ": " + request.reason()
                            + ". Resume to re-plan affected stages.")
                    .details(Map.of("fromHash", Hashes.shortHash(before.getRequirementHash()), "toHash", Hashes.shortHash(newHash),
                            "revision", after.getRequirementRevision(), "length", text.length()))
                    .build());
            return view(runId);
        });
    }

    // ------------------------------------------------------------------ workspace: rollback, promotion, compensation

    public WorkflowViews.Run rollbackWorkspace(Long runId, String actor) {
        return withClaim(runId, () -> {
            if (!engine.rollbackWorkspace(store.run(runId), actor, "requested by " + actor)) {
                throw new WorkflowConflictException("Run " + runId + " has no workspace to roll back.");
            }
            return view(runId);
        });
    }

    /**
     * The high-impact action: commit the approved patch to a NEW local branch in
     * the orchestrator's sandbox clone. Preconditions, all enforced: run SUCCEEDED,
     * the release-readiness output has a VALID APPROVED approval, that approved
     * readiness version vouches for the CURRENT change-patch version, the caller
     * names that version, and confirm=true. Never pushes, never touches the source
     * repository, never deploys.
     */
    public WorkflowViews.Run promote(Long runId, WorkflowRequests.Promote request) {
        return withClaim(runId, () -> {
            WorkflowRun run = store.run(runId);
            if (run.getStatus() != RunStatus.SUCCEEDED) {
                throw new WorkflowConflictException("Run " + runId + " is " + run.getStatus() + "; only a SUCCEEDED run can be promoted.");
            }
            requireNoPendingReplan(run, "promotion");
            if (run.getPromotedBranch() != null && run.getPromotionCompensatedAt() == null) {
                throw new WorkflowConflictException("Run " + runId + " was already promoted to " + run.getPromotedBranch() + ".");
            }
            if (run.getWorkspacePath() == null) {
                throw new WorkflowConflictException("Run " + runId + " has no workspace; the patch was never applied, so it cannot be promoted.");
            }
            WorkflowTask patchTask = producerOf(runId, Artifacts.CHANGE_PATCH);
            WorkflowTask readinessTask = producerOf(runId, Artifacts.READINESS_REPORT);
            WorkflowArtifact patch = store.artifact(store.outputRefs(patchTask).get(Artifacts.CHANGE_PATCH));
            WorkflowArtifact readiness = store.artifact(store.outputRefs(readinessTask).get(Artifacts.READINESS_REPORT));
            if (request.patchVersion() != patch.getVersion()) {
                throw new WorkflowConflictException("Stale promotion: change-patch is at v" + patch.getVersion() + ", request names v" + request.patchVersion() + ".");
            }
            WorkflowApproval approval = store.validApprovals(runId, readinessTask.getTaskKey()).stream()
                    .filter(a -> a.getArtifactId().equals(readiness.getId()) && a.getDecision() == WorkflowApproval.Decision.APPROVED)
                    .findFirst()
                    .orElseThrow(() -> new WorkflowConflictException("No valid approval of " + readiness.getName() + "@v" + readiness.getVersion()
                            + "; promotion requires explicit human approval of the current release readiness."));
            ReadinessReport report = store.read(readiness.getContentJson(), ReadinessReport.class);
            String patchLabel = patch.getName() + "@v" + patch.getVersion() + "#" + Hashes.shortHash(patch.getContentHash());
            if (!report.approvalSubject().contains(patchLabel)) {
                throw new WorkflowConflictException("The approved readiness report vouches for " + report.approvalSubject()
                        + ", not the current " + patchLabel + ". Re-run release readiness and approve again.");
            }
            ChangePatch content = store.read(patch.getContentJson(), ChangePatch.class);
            if (!content.applied() || content.unifiedDiff() == null || content.unifiedDiff().isBlank()) {
                throw new WorkflowConflictException("The change-patch has no applied diff to promote.");
            }
            String branch = "workflow/run-" + runId + "-v" + patch.getVersion();
            WorkspaceManager.Workspace ws = new WorkspaceManager.Workspace(runId, Path.of(run.getWorkspacePath()), run.getBaseCommit());
            WorkspaceManager.Promotion promotion = workspaces.promote(ws, content.unifiedDiff(), branch,
                    "workflow run " + runId + ": " + run.getTitle() + "\n\nApproved by " + approval.getReviewer() + " (approval #" + approval.getId()
                            + "); promoted by " + request.reviewer() + ".\nPatch " + patchLabel + ", readiness " + readiness.getName() + "@v" + readiness.getVersion(),
                    request.reviewer());
            store.updateRun(runId, r -> {
                r.setPromotedBranch(promotion.branch());
                r.setPromotedCommit(promotion.commit());
                r.setPromotedBy(request.reviewer());
                r.setPromotedAt(Instant.now());
                r.setPromotionCompensatedAt(null);
            });
            audit.append(AuditEntry.of(runId, WorkflowEvent.Type.PATCH_PROMOTED).actor(request.reviewer())
                    .refs(List.of(patchLabel, readiness.getName() + "@v" + readiness.getVersion()))
                    .message("Promoted " + patchLabel + " to local branch " + promotion.branch() + " (" + Hashes.shortHash(promotion.commit())
                            + ") in the sandbox clone. Not pushed, not merged into the source repository, not deployed.")
                    .details(Map.of("approvalId", approval.getId(), "approvedBy", approval.getReviewer(), "promotedBy", request.reviewer(),
                            "commit", promotion.commit(), "comment", Objects.toString(request.comment(), "")))
                    .build());
            return view(runId);
        });
    }

    /** Compensation for promotion: deletes the local branch it created, if it still points at the promoted commit. */
    public WorkflowViews.Run compensatePromotion(Long runId, String actor) {
        return withClaim(runId, () -> {
            WorkflowRun run = store.run(runId);
            if (run.getPromotedBranch() == null || run.getPromotionCompensatedAt() != null) {
                throw new WorkflowConflictException("Run " + runId + " has no active promotion to compensate.");
            }
            WorkspaceManager.Workspace ws = new WorkspaceManager.Workspace(runId, Path.of(run.getWorkspacePath()), run.getBaseCommit());
            workspaces.compensatePromotion(ws, run.getPromotedBranch(), run.getPromotedCommit());
            store.updateRun(runId, r -> r.setPromotionCompensatedAt(Instant.now()));
            audit.append(AuditEntry.of(runId, WorkflowEvent.Type.PROMOTION_COMPENSATED).actor(actor)
                    .message("Deleted local branch " + run.getPromotedBranch() + " (" + Hashes.shortHash(run.getPromotedCommit())
                            + ") created by the promotion. Nothing had been pushed, so nothing outside the sandbox clone needed undoing.")
                    .build());
            return view(runId);
        });
    }

    private WorkflowTask producerOf(Long runId, String artifact) {
        return store.tasks(runId).stream().filter(t -> agents.get(t.getAgentKey()).produces().contains(artifact)).findFirst()
                .orElseThrow(() -> new WorkflowConflictException("This run's graph has no task producing '" + artifact + "'."));
    }

    // ------------------------------------------------------------------ queries

    public WorkflowViews.Run view(Long runId) {
        WorkflowRun run = store.run(runId);
        WorkflowDefinition definition = store.read(run.getDefinitionJson(), WorkflowDefinition.class);
        Map<String, TaskNode> nodes = new LinkedHashMap<>();
        definition.tasks().forEach(n -> nodes.put(n.key(), n));
        List<WorkflowViews.Task> tasks = store.tasks(runId).stream().map(t -> {
            Map<String, String> outputs = new LinkedHashMap<>();
            store.outputRefs(t).forEach((name, id) -> {
                WorkflowArtifact a = store.artifact(id);
                outputs.put(name, name + "@v" + a.getVersion() + "#" + Hashes.shortHash(a.getContentHash()));
            });
            return new WorkflowViews.Task(t.getTaskKey(), t.getStage(), t.getAgentKey() + "@" + t.getAgentVersion(), t.getState(),
                    nodes.containsKey(t.getTaskKey()) ? nodes.get(t.getTaskKey()).dependsOn() : List.of(),
                    t.getAttempts(), t.getRetries(), t.isReused(), t.isFallbackUsed(), t.isRequiresApproval(), t.getReviewReason(),
                    outputs, t.getErrorMessage(), json(t.getEntryGateJson()), json(t.getExitGateJson()), t.getStartedAt(), t.getFinishedAt());
        }).toList();
        WorkflowViews.Promotion promotion = run.getPromotedBranch() == null ? null
                : new WorkflowViews.Promotion(run.getPromotedBranch(), run.getPromotedCommit(), run.getPromotedBy(), run.getPromotedAt(),
                run.getPromotionCompensatedAt());
        List<List<String>> layers = validator.validate(definition, agents).parallelLayers();
        return new WorkflowViews.Run(run.getId(), run.getTitle(), run.getExecutionMode(),
                run.getExecutionMode() == ExecutionMode.LLM ? WorkflowViews.LLM_NOTICE : WorkflowViews.DEMO_NOTICE, run.getModel(),
                run.getTestExecution(),
                run.getStatus(), run.getRequirementRevision(), Hashes.shortHash(run.getRequirementHash()), run.getDefinitionName(), layers,
                faults(run), run.getBaseCommit(), run.getWorkspacePath(), promotion, run.getFailureReason(), run.getDriveCount(),
                run.getCreatedAt(), run.getStartedAt(), run.getCompletedAt(), tasks, approvals(runId));
    }

    public PageResponse<WorkflowViews.RunSummary> list(Pageable pageable) {
        Pageable effective = pageable.getSort().isSorted() ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(store.runs(effective).map(r -> new WorkflowViews.RunSummary(r.getId(), r.getTitle(), r.getExecutionMode(),
                r.getStatus(), r.getCreatedAt(), r.getCompletedAt())));
    }

    public List<WorkflowViews.Approval> approvals(Long runId) {
        store.run(runId);
        return store.approvals(runId).stream().map(WorkflowService::approvalView).toList();
    }

    public List<WorkflowViews.ArtifactSummary> artifacts(Long runId) {
        store.run(runId);
        Set<Long> current = currentArtifactIds(runId);
        return store.artifacts(runId).stream().map(a -> summary(a, current)).toList();
    }

    public WorkflowViews.Artifact artifact(Long runId, String name, Integer version) {
        WorkflowArtifact a = find(runId, name, version);
        return new WorkflowViews.Artifact(summary(a, currentArtifactIds(runId)), json(a.getContentJson()), a.getContentMarkdown(),
                json(a.getResultJson()), json(a.getGatesJson()), a.getInputTokens(), a.getOutputTokens(), a.getEstimatedCostUsd());
    }

    public WorkflowViews.Lineage lineage(Long runId, String name, Integer version) {
        WorkflowArtifact a = find(runId, name, version);
        List<WorkflowViews.InputRef> inputs = store.lineageOf(a.getId()).stream()
                .map(e -> new WorkflowViews.InputRef(e.getInputName() + "@v" + e.getInputVersion(), e.getInputHash(), e.getInputArtifactId()))
                .toList();
        List<String> consumers = store.consumersOf(a.getId()).stream().map(WorkflowLineageEdge::getOutputArtifactId).distinct()
                .map(store::artifact).map(c -> c.getName() + "@v" + c.getVersion()).toList();
        List<WorkflowViews.Approval> bound = store.approvals(runId).stream().filter(x -> x.getArtifactId().equals(a.getId()))
                .map(WorkflowService::approvalView).toList();
        return new WorkflowViews.Lineage(summary(a, currentArtifactIds(runId)), a.getAgentKey() + "@" + a.getAgentVersion(),
                a.getInputFingerprint(), inputs, consumers, json(a.getResultJson()), json(a.getGatesJson()), bound);
    }

    public List<WorkflowViews.Event> history(Long runId) {
        store.run(runId);
        return audit.events(runId).stream().map(e -> new WorkflowViews.Event(e.getSequence(), e.getCreatedAt(), e.getType().name(),
                e.getTaskKey(), e.getAttempt(), e.getAgentId(), e.getFromState(), e.getToState(), e.getActor(), e.getExecutionMode(),
                e.getModel(), json(e.getRefsJson()), e.getMessage(), json(e.getDetailsJson()), e.getHash())).toList();
    }

    public AuditLog.Verification verifyAudit(Long runId) {
        store.run(runId);
        return audit.verify(runId);
    }

    // ------------------------------------------------------------------ internals

    private WorkflowArtifact find(Long runId, String name, Integer version) {
        store.run(runId);
        if (version != null) {
            return store.artifactVersion(runId, name, version)
                    .orElseThrow(() -> new WorkflowNotFoundException(runId, "artifact", name + "@v" + version));
        }
        List<WorkflowArtifact> versions = store.artifactVersions(runId, name);
        if (versions.isEmpty()) {
            throw new WorkflowNotFoundException(runId, "artifact", name);
        }
        Set<Long> current = currentArtifactIds(runId);
        return versions.stream().filter(a -> current.contains(a.getId())).findFirst().orElse(versions.get(versions.size() - 1));
    }

    private Set<Long> currentArtifactIds(Long runId) {
        Set<Long> ids = new java.util.HashSet<>();
        store.tasks(runId).forEach(t -> ids.addAll(store.outputRefs(t).values()));
        return ids;
    }

    private static WorkflowViews.ArtifactSummary summary(WorkflowArtifact a, Set<Long> current) {
        return new WorkflowViews.ArtifactSummary(a.getId(), a.getName(), a.getVersion(), a.getName() + "@v" + a.getVersion(), a.getTaskKey(),
                a.getTaskAttempt(), a.getOrigin(), a.getAdapter(), a.getModel(), a.isFallback(), a.isAccepted(), a.isInvalidated(),
                current.contains(a.getId()), a.getContentHash(), a.getInputFingerprint(), a.getCreatedAt());
    }

    private static WorkflowViews.Approval approvalView(WorkflowApproval a) {
        return new WorkflowViews.Approval(a.getId(), a.getTaskKey(), a.getArtifactName() + "@v" + a.getArtifactVersion() + "#"
                + Hashes.shortHash(a.getArtifactHash()), a.getDecision().name(), a.getReviewer(), a.getComment(), a.getDecidedAt(),
                a.getStatus().name(), a.getStaleReason(), a.getStaledAt());
    }

    private Map<String, Integer> faults(WorkflowRun run) {
        if (run.getFaultInjectionJson() == null) {
            return Map.of();
        }
        try {
            return store.objectMapper().readValue(run.getFaultInjectionJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Integer>>() {
                    });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private JsonNode json(String value) {
        if (value == null) {
            return null;
        }
        try {
            return store.objectMapper().readTree(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** Runs a mutating human action with the run claimed, so it can't interleave with a driver. */
    private <T> T withClaim(Long runId, Supplier<T> action) {
        store.run(runId);
        engine.claim(runId);
        try {
            return action.get();
        } finally {
            engine.release(runId);
        }
    }

    /** Claims the run on the calling thread, then drives it asynchronously (or inline when async execution is off). */
    private void dispatch(Long runId, WorkflowEngine.DriveMode mode) {
        engine.claim(runId);
        if (!properties.asyncExecution()) {
            try {
                engine.driveClaimed(runId, mode);
            } finally {
                engine.release(runId);
            }
            return;
        }
        try {
            driverPool.execute(() -> {
                try {
                    engine.driveClaimed(runId, mode);
                } finally {
                    engine.release(runId);
                }
            });
        } catch (TaskRejectedException e) {
            engine.release(runId);
            throw new WorkflowCapacityException();
        }
    }
}
