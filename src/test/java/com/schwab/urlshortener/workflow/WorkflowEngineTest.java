package com.schwab.urlshortener.workflow;

import com.schwab.urlshortener.agentic.llm.LlmClient;
import com.schwab.urlshortener.agentic.llm.LlmException;
import com.schwab.urlshortener.agentic.llm.LlmResponse;
import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.exception.WorkflowConflictException;
import com.schwab.urlshortener.workflow.api.WorkflowRequests;
import com.schwab.urlshortener.workflow.api.WorkflowViews;
import com.schwab.urlshortener.workflow.audit.AuditLog;
import com.schwab.urlshortener.workflow.engine.AgentFailures;
import com.schwab.urlshortener.workflow.engine.RunStatus;
import com.schwab.urlshortener.workflow.engine.TaskState;
import com.schwab.urlshortener.workflow.engine.ToolPolicy;
import com.schwab.urlshortener.workflow.engine.WorkflowEngine;
import com.schwab.urlshortener.workflow.engine.WorkflowStore;
import com.schwab.urlshortener.workflow.metrics.WorkflowMetricsService;
import com.schwab.urlshortener.workflow.persistence.WorkflowEvent;
import com.schwab.urlshortener.workflow.persistence.WorkflowTask;
import com.schwab.urlshortener.workflow.persistence.WorkflowEvent.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static com.schwab.urlshortener.workflow.FakeAgents.SCRIPT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Engine behavior on a diamond graph of scripted fake agents
 * (root → left ∥ right → join), driven through the real WorkflowService,
 * persistence, and audit log. Assertions check what actually executed (the
 * fakes count invocations and record start/end order), not only what the
 * engine reports.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({FakeAgents.Config.class, WorkflowEngineTest.StubLlm.class})
@TestPropertySource(properties = {"app.workflow.workspace.enabled=false", "app.workflow.max-parallelism=3"})
class WorkflowEngineTest {

    /** Makes LLM mode available so the fallback path can be exercised; it always fails, which is the point. */
    @TestConfiguration
    static class StubLlm {
        @Bean
        LlmClient failingLlm() {
            return new LlmClient() {
                @Override
                public String modelId() {
                    return "stub-model";
                }

                @Override
                public <T> LlmResponse<T> generate(String s, String u, Class<T> t) {
                    throw new LlmException("stub refusal", null, false);
                }
            };
        }
    }

    @Autowired
    WorkflowService service;
    @Autowired
    WorkflowStore store;
    @Autowired
    WorkflowEngine engine;
    @Autowired
    WorkflowMetricsService metrics;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        SCRIPT.reset();
    }

    private WorkflowViews.Run create(boolean approvalOnJoin) {
        return create(approvalOnJoin, ExecutionMode.DETERMINISTIC_DEMO);
    }

    private WorkflowViews.Run create(boolean approvalOnJoin, ExecutionMode mode) {
        return service.create(new WorkflowRequests.CreateRun("engine test", "Tenants must get a joined result.", mode,
                FakeAgents.diamond(approvalOnJoin), null, null));
    }

    private static WorkflowViews.Task task(WorkflowViews.Run run, String key) {
        return run.tasks().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow();
    }

    private List<Type> types(Long runId, String task) {
        return store.history(runId).stream().filter(e -> task.equals(e.getTaskKey())).map(WorkflowEvent::getType).toList();
    }

    // ---------------------------------------------------------------- ordering and parallelism

    @Test
    void dependencyOrder_rootFirst_joinOnlyAfterBothBranchesFinished() {
        WorkflowViews.Run run = service.start(create(false).id());

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(SCRIPT.starts.get(0)).isEqualTo("root");
        assertThat(SCRIPT.starts.get(SCRIPT.starts.size() - 1)).isEqualTo("join");
        int joinStart = SCRIPT.starts.indexOf("join");
        assertThat(SCRIPT.ends.subList(0, SCRIPT.ends.indexOf("join"))).contains("left", "right");
        assertThat(joinStart).isEqualTo(3);
        assertThat(run.parallelLayers()).containsExactly(List.of("root"), List.of("left", "right"), List.of("join"));
    }

    @Test
    void independentBranches_runInParallel_andJoinWaitsForBoth() {
        // Each branch waits at a 2-party barrier: if the engine ran them one after the other, the first
        // would time out and the run would fail. Success therefore proves real concurrency.
        CyclicBarrier barrier = new CyclicBarrier(2);
        java.util.function.Function<com.schwab.urlshortener.workflow.engine.AgentRequest, com.schwab.urlshortener.workflow.engine.AgentResult> meet =
                req -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException("branches did not run concurrently", e);
                    }
                    return FakeAgents.ok(req.taskKey(), req.taskKey() + "-out");
                };
        SCRIPT.behavior.put("left", meet);
        SCRIPT.behavior.put("right", meet);

        WorkflowViews.Run run = service.start(create(false).id());

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(SCRIPT.maxConcurrent.get()).isGreaterThanOrEqualTo(2);
        assertThat(WorkflowMetricsService.tasksThatRanConcurrently(store.history(run.id()))).contains("left", "right");
        int joinStart = SCRIPT.starts.indexOf("join");
        assertThat(SCRIPT.ends.indexOf("left")).isLessThan(SCRIPT.ends.indexOf("join"));
        assertThat(SCRIPT.ends.indexOf("right")).isLessThan(SCRIPT.ends.indexOf("join"));
        assertThat(joinStart).isEqualTo(3);
    }

    // ---------------------------------------------------------------- gates

    @Test
    void entryGateFailure_failsTask_skipsDownstream_independentBranchStillSucceeds_neverRetried() {
        SCRIPT.failEntryGate = true;

        WorkflowViews.Run run = service.start(create(false).id());

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(task(run, "left").state()).isEqualTo(TaskState.FAILED);
        assertThat(task(run, "left").error()).contains("Entry gate failed: [FAKE-ENTRY]");
        assertThat(task(run, "right").state()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(task(run, "join").state()).isEqualTo(TaskState.SKIPPED);
        assertThat(SCRIPT.count("fake-left")).isZero();
        assertThat(types(run.id(), "left")).contains(Type.ENTRY_GATE_FAILED).doesNotContain(Type.TASK_RETRY_SCHEDULED);
    }

    @Test
    void exitGateFailure_keepsTheProducedVersionButNeverAcceptsIt() {
        SCRIPT.failExitGate = true;

        WorkflowViews.Run run = service.start(create(false).id());

        assertThat(task(run, "left").state()).isEqualTo(TaskState.FAILED);
        assertThat(task(run, "left").error()).contains("Exit gate failed: [FAKE-EXIT]");
        assertThat(SCRIPT.count("fake-left")).isEqualTo(1);
        WorkflowViews.ArtifactSummary left = service.artifacts(run.id()).stream().filter(a -> a.name().equals("left")).findFirst().orElseThrow();
        assertThat(left.accepted()).isFalse();
        assertThat(left.current()).isFalse();
        assertThat(types(run.id(), "left")).doesNotContain(Type.TASK_RETRY_SCHEDULED);
    }

    // ---------------------------------------------------------------- retries

    @Test
    void transientFailures_areRetriedWithBackoff_thenSucceed() {
        SCRIPT.behavior.put("left", FakeAgents.transientFailures(2, "left"));

        WorkflowViews.Run run = service.start(create(false).id());

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(task(run, "left").attempts()).isEqualTo(3);
        assertThat(task(run, "left").retries()).isEqualTo(2);
        assertThat(types(run.id(), "left").stream().filter(t -> t == Type.TASK_RETRY_SCHEDULED)).hasSize(2);
        WorkflowMetricsService.Metrics m = metrics.forRun(run.id());
        assertThat(m.automaticRetries()).isEqualTo(2);
        assertThat(m.recoveredIncidents()).isEqualTo(1);
        assertThat(m.mttrMs()).isNotNull();
    }

    @Test
    void retryExhaustion_failsAfterMaxAttempts_withoutFallbackInDemoMode() {
        SCRIPT.behavior.put("left", FakeAgents.transientFailures(99, "left"));

        WorkflowViews.Run run = service.start(create(false).id());

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(task(run, "left").attempts()).isEqualTo(3);
        assertThat(task(run, "left").error()).contains("retries exhausted").contains("no fallback in DETERMINISTIC_DEMO mode");
        assertThat(types(run.id(), "left")).contains(Type.TASK_RETRIES_EXHAUSTED).doesNotContain(Type.TASK_FALLBACK);
        assertThat(task(run, "join").state()).isEqualTo(TaskState.SKIPPED);
        assertThat(metrics.forRun(run.id()).unrecoveredIncidents()).isEqualTo(1);
    }

    @Test
    void agentFailure_andToolViolation_areNeverRetried() {
        SCRIPT.behavior.put("left", req -> {
            throw new AgentFailures.AgentFailure("cannot complete");
        });
        SCRIPT.behavior.put("right", req -> {
            req.requireTool(ToolPolicy.Tool.WORKSPACE_WRITE);
            return FakeAgents.ok("right", "x");
        });

        WorkflowViews.Run run = service.start(create(false).id());

        assertThat(task(run, "left").attempts()).isEqualTo(1);
        assertThat(task(run, "right").attempts()).isEqualTo(1);
        assertThat(task(run, "right").error()).contains("Tool policy violation").contains("WORKSPACE_WRITE");
        assertThat(store.history(run.id()).stream().filter(e -> e.getType() == Type.TASK_RETRY_SCHEDULED)).isEmpty();
    }

    // ---------------------------------------------------------------- fallback

    @Test
    void llmFailure_takesTheDefinedFallback_labeledAndGatedOnApproval() {
        SCRIPT.behavior.put("left", req -> {
            if (!req.fallback()) {
                throw new AgentFailures.AgentFailure("model refused");
            }
            return FakeAgents.ok("left", "fallback-out");
        });

        WorkflowViews.Run run = service.start(create(false, ExecutionMode.LLM).id());

        assertThat(run.status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        WorkflowViews.Task left = task(run, "left");
        assertThat(left.state()).isEqualTo(TaskState.WAITING_APPROVAL);
        assertThat(left.fallbackUsed()).isTrue();
        assertThat(left.reviewReason()).contains("FALLBACK");
        assertThat(types(run.id(), "left")).contains(Type.TASK_FALLBACK, Type.APPROVAL_REQUESTED);
        assertThat(service.artifact(run.id(), "left", null).summary().fallback()).isTrue();
        assertThat(task(run, "join").state()).isEqualTo(TaskState.PENDING);
    }

    // ---------------------------------------------------------------- restart / resume

    @Test
    void resume_afterFailure_reRunsOnlyFailedAndSkippedWork() {
        SCRIPT.behavior.put("left", req -> {
            throw new AgentFailures.AgentFailure("first drive fails");
        });
        Long id = service.start(create(false).id()).id();
        assertThat(service.view(id).status()).isEqualTo(RunStatus.FAILED);
        assertThat(SCRIPT.count("fake-root")).isEqualTo(1);
        assertThat(SCRIPT.count("fake-right")).isEqualTo(1);

        SCRIPT.behavior.remove("left");
        WorkflowViews.Run run = service.resume(id);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(SCRIPT.count("fake-root")).as("completed valid work is not repeated").isEqualTo(1);
        assertThat(SCRIPT.count("fake-right")).isEqualTo(1);
        assertThat(SCRIPT.count("fake-left")).isEqualTo(2);
        assertThat(SCRIPT.count("fake-join")).isEqualTo(1);
        assertThat(types(id, "left")).contains(Type.TASK_RESET);
        assertThat(run.driveCount()).isEqualTo(2);
    }

    @Test
    void crashRecovery_runningRunBecomesInterrupted_andResumeFinishesWithoutRepeatingCompletedTasks() {
        Long id = service.start(create(false).id()).id();
        // Simulate a process that died mid-run: the run and one task left RUNNING, its output gone.
        store.updateRun(id, r -> r.setStatus(RunStatus.RUNNING));
        Long joinId = store.task(id, "join").getId();
        store.updateTask(joinId, t -> {
            t.setState(TaskState.RUNNING);
            t.setOutputRefsJson(null);
        });
        int rootRuns = SCRIPT.count("fake-root");

        engine.recoverInterruptedRuns();
        assertThat(service.view(id).status()).isEqualTo(RunStatus.INTERRUPTED);
        WorkflowViews.Run run = service.resume(id);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(SCRIPT.count("fake-root")).isEqualTo(rootRuns);
        assertThat(types(id, "join")).contains(Type.TASK_INTERRUPTED);
        assertThat(store.history(id).stream().map(WorkflowEvent::getType)).contains(Type.RUN_INTERRUPTED);
    }

    // ---------------------------------------------------------------- invalidation and re-planning

    @Test
    void invalidation_withUnchangedUpstreamOutput_preservesDownstreamWork() {
        Long id = service.start(create(false).id()).id();

        service.invalidate(id, "left", new WorkflowRequests.Invalidate("re-check", "operator"));
        assertThat(task(service.view(id), "join").state()).isEqualTo(TaskState.INVALIDATED);
        WorkflowViews.Run run = service.resume(id);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(SCRIPT.count("fake-left")).isEqualTo(2);
        assertThat(SCRIPT.count("fake-join")).as("join's inputs are byte-identical, so its output is reused").isEqualTo(1);
        assertThat(task(run, "join").reused()).isTrue();
        assertThat(task(run, "right").reused()).isFalse();
        assertThat(SCRIPT.count("fake-right")).isEqualTo(1);
    }

    @Test
    void invalidation_withChangedUpstreamOutput_reRunsAffectedDownstream_only() {
        Long id = service.start(create(false).id()).id();
        SCRIPT.behavior.put("left", req -> FakeAgents.ok("left", "left-v2"));

        service.invalidate(id, "left", new WorkflowRequests.Invalidate("new data", "operator"));
        WorkflowViews.Run run = service.resume(id);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(SCRIPT.count("fake-join")).isEqualTo(2);
        assertThat(SCRIPT.count("fake-right")).isEqualTo(1);
        assertThat(task(run, "left").outputs().get("left")).startsWith("left@v2");
        assertThat(task(run, "join").outputs().get("joined")).startsWith("joined@v2");
    }

    @Test
    void requirementAmendment_invalidatesOnlyTasksThatReadIt_andRecordsTheReasonWithoutTheText() {
        Long id = service.start(create(false).id()).id();

        service.amendRequirement(id, new WorkflowRequests.AmendRequirement("Tenants must get a joined result, reworded.",
                "wording", "product-owner"));
        WorkflowViews.Run run = service.resume(id);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.requirementRevision()).isEqualTo(2);
        assertThat(SCRIPT.count("fake-root")).as("root reads the requirement").isEqualTo(2);
        assertThat(SCRIPT.count("fake-left")).as("root's output is unchanged, so downstream is reused").isEqualTo(1);
        assertThat(SCRIPT.count("fake-join")).isEqualTo(1);
        WorkflowEvent amended = store.history(id).stream().filter(e -> e.getType() == Type.REQUIREMENT_UPDATED).findFirst().orElseThrow();
        assertThat(amended.getActor()).isEqualTo("product-owner");
        assertThat(amended.getMessage()).doesNotContain("reworded");
        assertThat(amended.getDetailsJson()).contains("fromHash").contains("toHash");
    }

    // ---------------------------------------------------------------- approvals

    @Test
    void approval_isBoundToTheExactVersion_staleViewRefused_thenApproveCompletes() {
        Long id = service.start(create(true).id()).id();
        assertThat(service.view(id).status()).isEqualTo(RunStatus.WAITING_APPROVAL);

        assertThatThrownBy(() -> service.decide(id, "join", new WorkflowRequests.Decision(
                WorkflowRequests.Decision.Verdict.APPROVED, "lead", null, Map.of("joined", 7))))
                .isInstanceOf(WorkflowConflictException.class).hasMessageContaining("Stale review");

        WorkflowViews.Run run = service.decide(id, "join", new WorkflowRequests.Decision(
                WorkflowRequests.Decision.Verdict.APPROVED, "lead", "ok", Map.of("joined", 1)));

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.approvals()).singleElement().satisfies(a -> {
            assertThat(a.artifact()).startsWith("joined@v1#");
            assertThat(a.reviewer()).isEqualTo("lead");
            assertThat(a.status()).isEqualTo("VALID");
        });
        WorkflowEvent approved = store.history(id).stream().filter(e -> e.getType() == Type.APPROVED).findFirst().orElseThrow();
        assertThat(approved.getActor()).isEqualTo("lead");
        assertThat(approved.getDetailsJson()).contains("decidedAt").contains("joined@v1");
    }

    /** Regression: amending while a checkpoint waits must not let the old output be approved and the run complete un-re-planned. */
    @Test
    void amendmentWhileWaitingForApproval_blocksTheDecisionUntilTheRunIsReplanned() {
        Long id = service.start(create(true).id()).id();
        assertThat(service.view(id).status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        service.amendRequirement(id, new WorkflowRequests.AmendRequirement("Tenants must get a joined result, amended.",
                "scope change", "product-owner"));

        assertThatThrownBy(() -> service.decide(id, "join", new WorkflowRequests.Decision(
                WorkflowRequests.Decision.Verdict.APPROVED, "lead", "ok", Map.of("joined", 1))))
                .isInstanceOf(WorkflowConflictException.class)
                .hasMessageContaining("Re-planning pending")
                .hasMessageContaining("revision 2");
        assertThat(service.view(id).approvals()).isEmpty();
        assertThat(task(service.view(id), "join").state()).isEqualTo(TaskState.WAITING_APPROVAL);

        WorkflowViews.Run replanned = service.resume(id);
        assertThat(SCRIPT.count("fake-root")).as("the requirement reader re-ran").isEqualTo(2);
        assertThat(replanned.status()).isEqualTo(RunStatus.WAITING_APPROVAL);

        String label = task(replanned, "join").outputs().get("joined");
        int version = Integer.parseInt(label.substring(label.indexOf("@v") + 2, label.indexOf('#')));
        WorkflowViews.Run run = service.decide(id, "join", new WorkflowRequests.Decision(
                WorkflowRequests.Decision.Verdict.APPROVED, "lead", "ok after re-plan", Map.of("joined", version)));
        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.approvals()).singleElement().satisfies(a -> assertThat(a.artifact()).startsWith("joined@v" + version));
    }

    @Test
    void decisionRefused_whenAnyCompletedTasksInputsNoLongerMatchItsFingerprint() {
        Long id = service.start(create(true).id()).id();
        // Simulate an upstream change the run hasn't re-validated yet: root's accepted output is invalidated in place.
        WorkflowTask root = store.task(id, "root");
        store.updateArtifacts(List.copyOf(store.outputRefs(root).values()), a -> a.setInvalidated(true));

        assertThatThrownBy(() -> service.decide(id, "join", new WorkflowRequests.Decision(
                WorkflowRequests.Decision.Verdict.APPROVED, "lead", "ok", Map.of("joined", 1))))
                .isInstanceOf(WorkflowConflictException.class)
                .hasMessageContaining("Re-planning pending")
                .hasMessageContaining("root");
    }

    @Test
    void continueDrive_afterAnAmendment_replansInsteadOfSkippingValidation() {
        Long id = service.start(create(true).id()).id();
        service.amendRequirement(id, new WorkflowRequests.AmendRequirement("Tenants must get a joined result, amended.",
                "scope change", "product-owner"));

        engine.drive(id, WorkflowEngine.DriveMode.CONTINUE);

        assertThat(SCRIPT.count("fake-root")).as("a CONTINUE drive must not bypass re-planning").isEqualTo(2);
        assertThat(store.run(id).getPlannedRequirementRevision()).isEqualTo(2);
    }

    @Test
    void credentialInARequirement_isRefusedAtIntake_andAtAmendment_andNothingIsStored() {
        int before = store.runs(org.springframework.data.domain.PageRequest.of(0, 1000)).getContent().size();
        assertThatThrownBy(() -> service.create(new WorkflowRequests.CreateRun("leaky", "Tenants must log in. Repro key: usk_SYNTHETICprobeKEY0000000000",
                ExecutionMode.DETERMINISTIC_DEMO, FakeAgents.diamond(false), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant API key")
                .hasMessageNotContaining("usk_SYNTHETIC");
        assertThat(store.runs(org.springframework.data.domain.PageRequest.of(0, 1000)).getContent()).hasSize(before);

        Long id = service.start(create(false).id()).id();
        assertThatThrownBy(() -> service.amendRequirement(id, new WorkflowRequests.AmendRequirement(
                "Tenants must get a joined result. Key: usk_SYNTHETICprobeKEY0000000000", "add repro", "operator")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenant API key");
        assertThat(store.run(id).getRequirementRevision()).isEqualTo(1);
    }

    @Test
    void policyViolation_failsTheTask_withoutRetryOrFallback_evenInLlmMode() {
        SCRIPT.behavior.put("left", req -> {
            throw new AgentFailures.PolicyViolation("outgoing prompt contains what looks like a tenant API key");
        });

        WorkflowViews.Run run = service.start(create(false, ExecutionMode.LLM).id());

        assertThat(task(run, "left").state()).isEqualTo(TaskState.FAILED);
        assertThat(task(run, "left").attempts()).isEqualTo(1);
        assertThat(task(run, "left").fallbackUsed()).isFalse();
        assertThat(task(run, "left").error()).contains("Policy violation").contains("no fallback");
        assertThat(store.history(run.id()).stream().map(WorkflowEvent::getType)).doesNotContain(Type.TASK_RETRY_SCHEDULED, Type.TASK_FALLBACK);
    }

    @Test
    void rejection_failsTheTask_andIsNeverRetriedAutomatically() {
        Long id = service.start(create(true).id()).id();

        WorkflowViews.Run run = service.decide(id, "join", new WorkflowRequests.Decision(
                WorkflowRequests.Decision.Verdict.REJECTED, "lead", "wrong approach", Map.of("joined", 1)));

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(task(run, "join").state()).isEqualTo(TaskState.FAILED);
        assertThat(task(run, "join").error()).contains("Rejected by lead");
        assertThat(SCRIPT.count("fake-join")).isEqualTo(1);
        assertThatThrownBy(() -> service.decide(id, "join", new WorkflowRequests.Decision(
                WorkflowRequests.Decision.Verdict.REJECTED, "lead", null, Map.of("joined", 1))))
                .isInstanceOf(WorkflowConflictException.class);
    }

    @Test
    void approvalGoesStale_whenTheApprovedArtifactChanges_andANewDecisionIsRequired() {
        Long id = service.start(create(true).id()).id();
        service.decide(id, "join", new WorkflowRequests.Decision(WorkflowRequests.Decision.Verdict.APPROVED, "lead", null, Map.of("joined", 1)));
        SCRIPT.behavior.put("right", req -> FakeAgents.ok("right", "right-v2"));

        service.invalidate(id, "right", new WorkflowRequests.Invalidate("upstream changed", "operator"));
        WorkflowViews.Run run = service.resume(id);

        assertThat(run.status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(task(run, "join").outputs().get("joined")).startsWith("joined@v2");
        assertThat(run.approvals()).singleElement().satisfies(a -> {
            assertThat(a.status()).isEqualTo("STALE");
            assertThat(a.staleReason()).isNotBlank();
        });
        assertThat(store.history(id).stream().map(WorkflowEvent::getType)).contains(Type.APPROVAL_STALE);
    }

    @Test
    void approvalSurvivesReplanning_whenTheApprovedVersionIsReused() {
        Long id = service.start(create(true).id()).id();
        service.decide(id, "join", new WorkflowRequests.Decision(WorkflowRequests.Decision.Verdict.APPROVED, "lead", null, Map.of("joined", 1)));

        service.invalidate(id, "left", new WorkflowRequests.Invalidate("re-check", "operator"));
        WorkflowViews.Run run = service.resume(id);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(task(run, "join").reused()).isTrue();
        assertThat(run.approvals()).singleElement().satisfies(a -> assertThat(a.status()).isEqualTo("VALID"));
    }

    // ---------------------------------------------------------------- safe-stop and cancellation

    @Test
    void safeStop_finishesInFlightWork_startsNothingNew_andResumeCompletes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SCRIPT.behavior.put("root", req -> {
            entered.countDown();
            await(release);
            return FakeAgents.ok("r", "root-out");
        });
        Long id = create(false).id();
        Thread driver = new Thread(() -> service.start(id));
        driver.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        service.stop(id, "operator");
        release.countDown();
        driver.join(10_000);

        WorkflowViews.Run stopped = service.view(id);
        assertThat(stopped.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(task(stopped, "root").state()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(SCRIPT.count("fake-left")).isZero();
        SCRIPT.behavior.remove("root");

        assertThat(service.resume(id).status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(SCRIPT.count("fake-root")).isEqualTo(1);
    }

    @Test
    void cancel_cancelsUnfinishedTasks_keepsFinishedOutputs_andIsResumable() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SCRIPT.behavior.put("root", req -> {
            entered.countDown();
            await(release);
            return FakeAgents.ok("r", "root-out");
        });
        Long id = create(false).id();
        Thread driver = new Thread(() -> service.start(id));
        driver.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        service.cancel(id, "operator");
        release.countDown();
        driver.join(10_000);

        WorkflowViews.Run cancelled = service.view(id);
        assertThat(cancelled.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(task(cancelled, "root").state()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(task(cancelled, "left").state()).isEqualTo(TaskState.CANCELLED);
        assertThat(task(cancelled, "join").state()).isEqualTo(TaskState.CANCELLED);
        assertThat(store.history(id).stream().map(WorkflowEvent::getType)).contains(Type.RUN_CANCEL_REQUESTED, Type.RUN_CANCELLED);
        SCRIPT.behavior.remove("root");
        assertThat(service.resume(id).status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(SCRIPT.count("fake-root")).isEqualTo(1);
    }

    @Test
    void lifecycleGuards_rejectInvalidTransitions() {
        Long id = service.start(create(false).id()).id();

        assertThatThrownBy(() -> service.start(id)).isInstanceOf(WorkflowConflictException.class);
        assertThatThrownBy(() -> service.resume(id)).isInstanceOf(WorkflowConflictException.class);
        assertThatThrownBy(() -> service.cancel(id, "x")).isInstanceOf(WorkflowConflictException.class);
        assertThatThrownBy(() -> service.stop(id, "x")).isInstanceOf(WorkflowConflictException.class);
    }

    // ---------------------------------------------------------------- audit

    @Test
    void auditChain_isIntact_andDetectsTampering() {
        Long id = service.start(create(false).id()).id();
        assertThat(store.auditLog().verify(id).valid()).isTrue();

        jdbc.update("update workflow_event set message = 'rewritten history' where run_id = ? and sequence = 3", id);

        AuditLog.Verification v = store.auditLog().verify(id);
        assertThat(v.valid()).isFalse();
        assertThat(v.firstBrokenSequence()).isEqualTo(3L);
        assertThat(v.reason()).contains("does not match its hash");
    }

    @Test
    void auditLog_redactsSecrets_andEveryTaskTransitionIsRecorded() {
        Long id = service.start(create(false).id()).id();
        service.amendRequirement(id, new WorkflowRequests.AmendRequirement("new text", "key usk_abcdefghijklmnopqrstuvwxyz0123 leaked", "operator"));

        WorkflowEvent amended = store.history(id).stream().filter(e -> e.getType() == Type.REQUIREMENT_UPDATED).findFirst().orElseThrow();
        assertThat(amended.getMessage()).doesNotContain("usk_abcdefghij").contains("[REDACTED:tenant API key]");
        List<String> rootTransitions = store.history(id).stream()
                .filter(e -> e.getType() == Type.TASK_TRANSITION && "root".equals(e.getTaskKey()))
                .map(e -> e.getFromState() + "->" + e.getToState()).toList();
        assertThat(rootTransitions).containsExactly("PENDING->READY", "READY->RUNNING", "RUNNING->SUCCEEDED");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
