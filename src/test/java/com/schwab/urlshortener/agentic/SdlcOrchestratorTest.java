package com.schwab.urlshortener.agentic;

import com.schwab.urlshortener.agentic.agent.AgentContext;
import com.schwab.urlshortener.agentic.agent.SdlcAgent;
import com.schwab.urlshortener.agentic.llm.LlmClient;
import com.schwab.urlshortener.agentic.llm.LlmException;
import com.schwab.urlshortener.agentic.llm.LlmResponse;
import com.schwab.urlshortener.agentic.model.ArtifactSource;
import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis;
import com.schwab.urlshortener.agentic.model.ImplementationPlan;
import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.RiskAssessment;
import com.schwab.urlshortener.agentic.model.SdlcStage;
import com.schwab.urlshortener.agentic.model.TestPlan;
import com.schwab.urlshortener.agentic.review.MarkdownRenderer;
import com.schwab.urlshortener.agentic.review.QualityGateReviewer;
import com.schwab.urlshortener.exception.LlmUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestration mechanics in isolation from Spring and the database:
 * ordering, per-mode provenance stamping, failure propagation, and the
 * "LLM requested but not configured" refusal.
 *
 * LLM mode here uses a scripted {@link LlmClient}, not Claude. It verifies
 * what the orchestrator does with model output and model failures, not
 * what any real model would produce. The SDK boundary itself is covered by
 * AnthropicLlmClientTest.
 */
class SdlcOrchestratorTest {

    private SdlcRunRepository runRepository;
    private SdlcArtifactRepository artifactRepository;
    private final List<SdlcArtifact> savedArtifacts = new ArrayList<>();

    /** Scripted model: returns the deterministic pipeline's artifacts, keyed by requested type. */
    static class ScriptedLlmClient implements LlmClient {
        final Map<Class<?>, Function<Class<?>, Object>> script = new HashMap<>();
        final List<String> userPrompts = new ArrayList<>();

        ScriptedLlmClient() {
            AgentContext ctx = AgenticFixtures.runDeterministicPipeline(AgenticFixtures.TITLE, AgenticFixtures.REQUIREMENT);
            script.put(RequirementSpec.class, t -> ctx.requirementSpec());
            script.put(ImpactAnalysis.class, t -> ctx.impactAnalysis());
            script.put(ImplementationPlan.class, t -> ctx.implementationPlan());
            script.put(TestPlan.class, t -> ctx.testPlan());
            script.put(RiskAssessment.class, t -> ctx.riskAssessment());
        }

        @Override
        public String modelId() {
            return "scripted-test-model";
        }

        @Override
        public <T> LlmResponse<T> generate(String systemPrompt, String userPrompt, Class<T> outputType) {
            userPrompts.add(userPrompt);
            return new LlmResponse<>(outputType.cast(script.get(outputType).apply(outputType)), 100, 50);
        }
    }

    @BeforeEach
    void setUp() {
        runRepository = mock(SdlcRunRepository.class);
        artifactRepository = mock(SdlcArtifactRepository.class);
        when(runRepository.save(any(SdlcRun.class))).thenAnswer(inv -> {
            SdlcRun r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(7L);
            }
            return r;
        });
        when(artifactRepository.save(any(SdlcArtifact.class))).thenAnswer(inv -> {
            savedArtifacts.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
    }

    private SdlcOrchestrator orchestrator(LlmClient llm, ExecutionMode defaultMode) {
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(llm);
        return new SdlcOrchestrator(AgenticFixtures.agents(), new QualityGateReviewer(), new MarkdownRenderer(),
                AgenticFixtures.catalog(), provider, runRepository, artifactRepository, AgenticFixtures.MAPPER,
                new AgenticProperties(defaultMode, null));
    }

    @Test
    void demoRun_producesSixArtifactsInOrder_stampedDeterministic_andAwaitsReview() {
        SdlcRun run = orchestrator(null, ExecutionMode.DETERMINISTIC_DEMO).execute(AgenticFixtures.TITLE, AgenticFixtures.REQUIREMENT, null);

        assertThat(run.getStatus()).isEqualTo(SdlcRun.Status.AWAITING_REVIEW);
        assertThat(run.getExecutionMode()).isEqualTo(ExecutionMode.DETERMINISTIC_DEMO);
        assertThat(run.getModel()).isNull();
        assertThat(run.getQualityGatePassed()).isTrue();
        assertThat(run.getBlockerCount()).isZero();
        assertThat(run.getCompletedAt()).isNotNull();

        assertThat(savedArtifacts).extracting(SdlcArtifact::getStage).containsExactly(
                SdlcStage.REQUIREMENTS, SdlcStage.IMPACT, SdlcStage.PLAN, SdlcStage.TESTS, SdlcStage.RISKS, SdlcStage.REVIEW);
        assertThat(savedArtifacts).allMatch(a -> a.getStatus() == SdlcArtifact.Status.COMPLETED);
        assertThat(savedArtifacts.subList(0, 5)).allMatch(a -> a.getSource() == ArtifactSource.DETERMINISTIC_DEMO && a.getModel() == null);
        assertThat(savedArtifacts.get(5).getSource()).isEqualTo(ArtifactSource.DETERMINISTIC_GUARDRAIL);
        assertThat(savedArtifacts).allMatch(a -> a.getInputTokens() == null && a.getDurationMs() != null);
        assertThat(savedArtifacts.get(0).getContentJson()).contains("\"acceptanceCriteria\"");
        assertThat(savedArtifacts.get(0).getContentMarkdown()).startsWith("_Source: deterministic rule-based demo logic");
    }

    @Test
    void llmRun_stampsModelAndTokensOnGenerativeStages_butGateStaysDeterministic() {
        ScriptedLlmClient llm = new ScriptedLlmClient();

        SdlcRun run = orchestrator(llm, ExecutionMode.DETERMINISTIC_DEMO)
                .execute(AgenticFixtures.TITLE, AgenticFixtures.REQUIREMENT, ExecutionMode.LLM);

        assertThat(run.getExecutionMode()).isEqualTo(ExecutionMode.LLM);
        assertThat(run.getModel()).isEqualTo("scripted-test-model");
        assertThat(run.getStatus()).isEqualTo(SdlcRun.Status.AWAITING_REVIEW);
        assertThat(savedArtifacts.subList(0, 5)).allMatch(a -> a.getSource() == ArtifactSource.LLM
                && "scripted-test-model".equals(a.getModel()) && a.getInputTokens() == 100 && a.getOutputTokens() == 50);
        assertThat(savedArtifacts.get(5).getSource()).isEqualTo(ArtifactSource.DETERMINISTIC_GUARDRAIL);
        assertThat(savedArtifacts.get(5).getModel()).isNull();
        assertThat(savedArtifacts.get(1).getContentMarkdown()).startsWith("_Source: generated by `scripted-test-model` (LLM)");
        // each stage's prompt carried the upstream artifacts
        assertThat(llm.userPrompts).hasSize(5);
        assertThat(llm.userPrompts.get(4)).contains("Implementation plan (JSON)");
    }

    @Test
    void llmRequested_withoutConfiguredClient_isRefusedBeforeAnythingIsPersisted() {
        SdlcOrchestrator orchestrator = orchestrator(null, ExecutionMode.DETERMINISTIC_DEMO);

        assertThat(orchestrator.llmAvailable()).isFalse();
        assertThatThrownBy(() -> orchestrator.execute("t", "r", ExecutionMode.LLM)).isInstanceOf(LlmUnavailableException.class);
        verify(runRepository, never()).save(any());
        verify(artifactRepository, never()).save(any());
    }

    @Test
    void llmStageFailure_failsRun_skipsLaterStages_andRunsNoGate() {
        ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.script.put(ImplementationPlan.class, t -> {
            throw new LlmException("Model declined to produce ImplementationPlan (stop_reason=refusal, category=unspecified)");
        });

        SdlcRun run = orchestrator(llm, ExecutionMode.DETERMINISTIC_DEMO).execute("t", AgenticFixtures.REQUIREMENT, ExecutionMode.LLM);

        assertThat(run.getStatus()).isEqualTo(SdlcRun.Status.FAILED);
        assertThat(run.getFailureReason()).startsWith("PLAN stage failed: Model declined");
        assertThat(run.getQualityGatePassed()).isNull();
        assertThat(savedArtifacts).extracting(SdlcArtifact::getStatus).containsExactly(
                SdlcArtifact.Status.COMPLETED, SdlcArtifact.Status.COMPLETED, SdlcArtifact.Status.FAILED,
                SdlcArtifact.Status.SKIPPED, SdlcArtifact.Status.SKIPPED, SdlcArtifact.Status.SKIPPED);
        assertThat(savedArtifacts.get(2).getContentJson()).isNull();
        assertThat(savedArtifacts.get(5).getSource()).isEqualTo(ArtifactSource.DETERMINISTIC_GUARDRAIL);
    }

    @Test
    void unexpectedException_isRecordedWithTypeAndTruncated() {
        ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.script.put(RequirementSpec.class, t -> {
            throw new IllegalStateException("x".repeat(5000));
        });

        SdlcRun run = orchestrator(llm, ExecutionMode.DETERMINISTIC_DEMO).execute("t", "r r r", ExecutionMode.LLM);

        assertThat(run.getStatus()).isEqualTo(SdlcRun.Status.FAILED);
        assertThat(savedArtifacts.get(0).getErrorMessage()).startsWith("IllegalStateException: xxx").hasSize(1000).endsWith("...");
    }

    @Test
    void nullModelOutput_isAFailure_notAnEmptyArtifact() {
        ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.script.put(RequirementSpec.class, t -> null);

        SdlcRun run = orchestrator(llm, ExecutionMode.DETERMINISTIC_DEMO).execute("t", "r r r", ExecutionMode.LLM);

        assertThat(run.getFailureReason()).contains("REQUIREMENTS produced no artifact");
    }

    @Test
    void defaultModeLlm_withoutClient_failsAtStartup() {
        assertThatThrownBy(() -> orchestrator(null, ExecutionMode.LLM))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.agentic.llm.enabled=true");
    }

    @Test
    void defaultModeLlm_withClient_isUsedWhenRequestOmitsMode() {
        SdlcOrchestrator orchestrator = orchestrator(new ScriptedLlmClient(), ExecutionMode.LLM);

        assertThat(orchestrator.defaultMode()).isEqualTo(ExecutionMode.LLM);
        assertThat(orchestrator.llmModel()).isEqualTo("scripted-test-model");
        assertThat(orchestrator.execute("t", AgenticFixtures.REQUIREMENT, null).getExecutionMode()).isEqualTo(ExecutionMode.LLM);
    }

    // ---------- ordering ----------

    @Test
    void order_sortsByDeclaredDependencies_regardlessOfRegistrationOrder() {
        assertThat(SdlcOrchestrator.order(AgenticFixtures.agents())).extracting(SdlcAgent::stage)
                .containsExactly(SdlcStage.REQUIREMENTS, SdlcStage.IMPACT, SdlcStage.PLAN, SdlcStage.TESTS, SdlcStage.RISKS);
        assertThat(orchestrator(null, ExecutionMode.DETERMINISTIC_DEMO).stages()).endsWith(SdlcStage.REVIEW).hasSize(6);
    }

    @Test
    void order_rejectsDuplicateStage_missingDependency_cycle_andReviewAgent() {
        assertThatThrownBy(() -> SdlcOrchestrator.order(List.of(fake(SdlcStage.IMPACT), fake(SdlcStage.IMPACT))))
                .hasMessageContaining("Two agents registered for stage IMPACT");
        assertThatThrownBy(() -> SdlcOrchestrator.order(List.of(fake(SdlcStage.PLAN, SdlcStage.IMPACT))))
                .hasMessageContaining("PLAN depends on IMPACT, which no agent provides");
        assertThatThrownBy(() -> SdlcOrchestrator.order(List.of(fake(SdlcStage.PLAN, SdlcStage.TESTS), fake(SdlcStage.TESTS, SdlcStage.PLAN))))
                .hasMessageContaining("cycle");
        assertThatThrownBy(() -> SdlcOrchestrator.order(List.of(fake(SdlcStage.REVIEW))))
                .hasMessageContaining("REVIEW is the orchestrator's deterministic gate");
    }

    private static SdlcAgent<Object> fake(SdlcStage stage, SdlcStage... deps) {
        return new SdlcAgent<>() {
            public SdlcStage stage() {
                return stage;
            }

            public List<SdlcStage> dependsOn() {
                return List.of(deps);
            }

            public Class<Object> outputType() {
                return Object.class;
            }

            public Object runDeterministic(AgentContext context) {
                return new Object();
            }

            public String systemPrompt() {
                return "";
            }

            public String userPrompt(AgentContext context) {
                return "";
            }
        };
    }
}
