package com.schwab.urlshortener.agentic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.agentic.agent.AgentContext;
import com.schwab.urlshortener.agentic.agent.SdlcAgent;
import com.schwab.urlshortener.agentic.llm.LlmClient;
import com.schwab.urlshortener.agentic.llm.LlmException;
import com.schwab.urlshortener.agentic.llm.LlmResponse;
import com.schwab.urlshortener.agentic.model.ArtifactSource;
import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.agentic.model.QualityGateReport;
import com.schwab.urlshortener.agentic.model.SdlcStage;
import com.schwab.urlshortener.agentic.review.MarkdownRenderer;
import com.schwab.urlshortener.agentic.review.QualityGateReviewer;
import com.schwab.urlshortener.exception.LlmUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the agentic SDLC pipeline: the generative agents in dependency
 * order, then the deterministic quality gate, persisting every stage's
 * artifact with its provenance as it goes.
 *
 * Responsibilities deliberately concentrated here (agents do none of them):
 *  - Mode selection. LLM mode without a configured {@link LlmClient} is
 *    refused up front with 503 — before anything is persisted — and there
 *    is no code path that substitutes demo output for a model call.
 *  - Ordering. Agents declare dependencies; {@link #order} topologically
 *    sorts them once, at construction, so a mis-wired pipeline fails at
 *    application startup rather than halfway through someone's run.
 *  - Failure handling. The first failing stage is recorded FAILED with a
 *    safe reason, every later stage SKIPPED, and the run FAILED. No partial
 *    "best effort" artifacts are passed downstream.
 *  - Provenance. Source, model, duration, and token usage are stamped on
 *    each artifact by the orchestrator, not self-reported by an agent.
 *
 * Not transactional as a whole, on purpose: an LLM-mode run is several
 * sequential model calls and could take minutes; holding one DB
 * transaction open across them would be worse than per-stage saves. Each
 * artifact row is its own repository save, and the run row is updated at
 * the end.
 */
@Service
@Slf4j
public class SdlcOrchestrator {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final List<SdlcAgent<?>> orderedAgents;
    private final QualityGateReviewer qualityGate;
    private final MarkdownRenderer renderer;
    private final ComponentCatalog catalog;
    private final LlmClient llmClient;
    private final SdlcRunRepository runRepository;
    private final SdlcArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;
    private final ExecutionMode defaultMode;

    public SdlcOrchestrator(List<SdlcAgent<?>> agents, QualityGateReviewer qualityGate, MarkdownRenderer renderer,
                            ComponentCatalog catalog, ObjectProvider<LlmClient> llmClient,
                            SdlcRunRepository runRepository, SdlcArtifactRepository artifactRepository,
                            ObjectMapper objectMapper, AgenticProperties properties) {
        this.orderedAgents = order(agents);
        this.qualityGate = qualityGate;
        this.renderer = renderer;
        this.catalog = catalog;
        this.llmClient = llmClient.getIfAvailable();
        this.runRepository = runRepository;
        this.artifactRepository = artifactRepository;
        this.objectMapper = objectMapper;
        this.defaultMode = properties.defaultMode();
        if (defaultMode == ExecutionMode.LLM && this.llmClient == null) {
            throw new IllegalStateException("app.agentic.default-mode=LLM requires app.agentic.llm.enabled=true");
        }
    }

    public boolean llmAvailable() {
        return llmClient != null;
    }

    public String llmModel() {
        return llmClient == null ? null : llmClient.modelId();
    }

    public ExecutionMode defaultMode() {
        return defaultMode;
    }

    /** Stages in execution order, including the final REVIEW gate. */
    public List<SdlcStage> stages() {
        List<SdlcStage> stages = new ArrayList<>(orderedAgents.stream().map(SdlcAgent::stage).toList());
        stages.add(SdlcStage.REVIEW);
        return stages;
    }

    public SdlcRun execute(String title, String requirement, ExecutionMode requestedMode) {
        ExecutionMode mode = requestedMode != null ? requestedMode : defaultMode;
        if (mode == ExecutionMode.LLM && llmClient == null) {
            throw new LlmUnavailableException();
        }
        ArtifactSource generativeSource = mode == ExecutionMode.LLM ? ArtifactSource.LLM : ArtifactSource.DETERMINISTIC_DEMO;
        String model = mode == ExecutionMode.LLM ? llmClient.modelId() : null;

        SdlcRun run = runRepository.save(SdlcRun.builder()
                .title(title)
                .requirementText(requirement)
                .executionMode(mode)
                .model(model)
                .status(SdlcRun.Status.RUNNING)
                .build());
        log.info("SDLC run {} started: mode={}, model={}", run.getId(), mode, model);

        AgentContext context = new AgentContext(title, requirement, mode, catalog);
        for (int i = 0; i < orderedAgents.size(); i++) {
            SdlcAgent<?> agent = orderedAgents.get(i);
            String failure = runStage(run.getId(), agent, context, generativeSource, model);
            if (failure != null) {
                List<SdlcStage> remaining = new ArrayList<>(orderedAgents.subList(i + 1, orderedAgents.size())
                        .stream().map(SdlcAgent::stage).toList());
                for (SdlcStage skipped : remaining) {
                    saveSkipped(run.getId(), skipped, generativeSource);
                }
                saveSkipped(run.getId(), SdlcStage.REVIEW, ArtifactSource.DETERMINISTIC_GUARDRAIL);
                run.setStatus(SdlcRun.Status.FAILED);
                run.setFailureReason(agent.stage() + " stage failed: " + failure);
                run.setCompletedAt(Instant.now());
                log.warn("SDLC run {} failed at {}: {}", run.getId(), agent.stage(), failure);
                return runRepository.save(run);
            }
        }

        long start = System.nanoTime();
        QualityGateReport report = qualityGate.review(context);
        saveCompleted(run.getId(), SdlcStage.REVIEW, ArtifactSource.DETERMINISTIC_GUARDRAIL, null, report,
                elapsedMs(start), null, null);

        run.setQualityGatePassed(report.passed());
        run.setBlockerCount(report.blockerCount());
        run.setWarningCount(report.warningCount());
        run.setStatus(SdlcRun.Status.AWAITING_REVIEW);
        run.setCompletedAt(Instant.now());
        log.info("SDLC run {} completed: gatePassed={}, blockers={}, warnings={}",
                run.getId(), report.passed(), report.blockerCount(), report.warningCount());
        return runRepository.save(run);
    }

    /** @return null on success, otherwise a safe failure reason. */
    private <T> String runStage(Long runId, SdlcAgent<T> agent, AgentContext context, ArtifactSource source, String model) {
        long start = System.nanoTime();
        try {
            T output;
            Long inputTokens = null;
            Long outputTokens = null;
            if (source == ArtifactSource.LLM) {
                LlmResponse<T> response = llmClient.generate(agent.systemPrompt(), agent.userPrompt(context), agent.outputType());
                output = response.output();
                inputTokens = response.inputTokens();
                outputTokens = response.outputTokens();
            } else {
                output = agent.runDeterministic(context);
            }
            if (output == null) {
                throw new IllegalStateException(agent.stage() + " produced no artifact");
            }
            context.put(agent.stage(), output);
            saveCompleted(runId, agent.stage(), source, model, output, elapsedMs(start), inputTokens, outputTokens);
            return null;
        } catch (LlmException e) {
            return saveFailed(runId, agent.stage(), source, model, elapsedMs(start), e.getMessage());
        } catch (RuntimeException e) {
            log.error("Unexpected failure in SDLC stage {} of run {}", agent.stage(), runId, e);
            return saveFailed(runId, agent.stage(), source, model, elapsedMs(start),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void saveCompleted(Long runId, SdlcStage stage, ArtifactSource source, String model, Object output,
                               long durationMs, Long inputTokens, Long outputTokens) {
        String markdown = renderer.provenance(source, model) + renderer.render(output);
        artifactRepository.save(SdlcArtifact.builder()
                .runId(runId).stage(stage).source(source).status(SdlcArtifact.Status.COMPLETED).model(model)
                .contentJson(toJson(output)).contentMarkdown(markdown)
                .durationMs(durationMs).inputTokens(inputTokens).outputTokens(outputTokens)
                .build());
    }

    private String saveFailed(Long runId, SdlcStage stage, ArtifactSource source, String model, long durationMs, String reason) {
        String safeReason = truncate(reason == null ? "unknown error" : reason);
        artifactRepository.save(SdlcArtifact.builder()
                .runId(runId).stage(stage).source(source).status(SdlcArtifact.Status.FAILED).model(model)
                .durationMs(durationMs).errorMessage(safeReason)
                .build());
        return safeReason;
    }

    private void saveSkipped(Long runId, SdlcStage stage, ArtifactSource source) {
        artifactRepository.save(SdlcArtifact.builder()
                .runId(runId).stage(stage).source(source).status(SdlcArtifact.Status.SKIPPED)
                .errorMessage("Not run: an earlier stage failed.")
                .build());
    }

    private String toJson(Object output) {
        try {
            return objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize artifact", e);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String truncate(String s) {
        return s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH - 3) + "...";
    }

    /**
     * Kahn's algorithm over the agents' declared dependencies, ties broken by
     * {@link SdlcStage} declaration order so the result is stable. Fails fast
     * on a duplicate stage, a dependency on a stage no agent provides (REVIEW
     * is not allowed as a dependency — it always runs last), or a cycle.
     */
    static List<SdlcAgent<?>> order(List<SdlcAgent<?>> agents) {
        Map<SdlcStage, SdlcAgent<?>> byStage = new EnumMap<>(SdlcStage.class);
        for (SdlcAgent<?> agent : agents) {
            if (agent.stage() == SdlcStage.REVIEW) {
                throw new IllegalStateException("REVIEW is the orchestrator's deterministic gate, not an agent stage");
            }
            if (byStage.put(agent.stage(), agent) != null) {
                throw new IllegalStateException("Two agents registered for stage " + agent.stage());
            }
        }
        for (SdlcAgent<?> agent : agents) {
            for (SdlcStage dep : agent.dependsOn()) {
                if (!byStage.containsKey(dep)) {
                    throw new IllegalStateException(agent.stage() + " depends on " + dep + ", which no agent provides");
                }
            }
        }
        List<SdlcAgent<?>> ordered = new ArrayList<>();
        Set<SdlcStage> done = EnumSet.noneOf(SdlcStage.class);
        while (ordered.size() < byStage.size()) {
            SdlcAgent<?> next = byStage.values().stream()
                    .filter(a -> !done.contains(a.stage()))
                    .filter(a -> done.containsAll(a.dependsOn()))
                    .min(Comparator.comparing(SdlcAgent::stage))
                    .orElseThrow(() -> new IllegalStateException("Agent dependencies form a cycle among: "
                            + byStage.keySet().stream().filter(s -> !done.contains(s)).toList()));
            ordered.add(next);
            done.add(next.stage());
        }
        return List.copyOf(ordered);
    }
}
