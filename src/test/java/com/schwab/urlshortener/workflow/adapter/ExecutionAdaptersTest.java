package com.schwab.urlshortener.workflow.adapter;

import com.schwab.urlshortener.agentic.llm.LlmClient;
import com.schwab.urlshortener.agentic.llm.LlmException;
import com.schwab.urlshortener.agentic.llm.LlmResponse;
import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.workflow.WorkflowProperties;
import com.schwab.urlshortener.workflow.engine.AgentFailures;
import com.schwab.urlshortener.workflow.engine.AgentRequest;
import com.schwab.urlshortener.workflow.engine.TestExecution;
import com.schwab.urlshortener.workflow.engine.ArtifactOrigin;
import com.schwab.urlshortener.workflow.engine.ToolPolicy;
import com.schwab.urlshortener.workflow.persistence.WorkflowArtifactRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The two execution adapters and the router, at the boundary: which adapter a
 * request reaches, tool enforcement, budget enforcement, per-call token cap,
 * and how SDK failures map onto the engine's recovery taxonomy. The LLM
 * adapter is exercised against a scripted LlmClient. It makes no network call
 * and proves nothing about a live model.
 */
class ExecutionAdaptersTest {

    record Out(String v) {
    }

    private static final GenerationRequest<Out> GEN = new GenerationRequest<>("test step", Out.class, "sys", "user", () -> new Out("demo"));

    private static AgentRequest request(ExecutionMode mode, boolean fallback, ToolPolicy tools) {
        AgentRequest r = new AgentRequest(1L, "t", 1, "a@1", "title", null, mode, TestExecution.PLAN_VERIFICATION, false, "intent", List.of(), List.of(),
                new AgentRequest.RepositoryContext(null, null, 0), tools, new AgentRequest.Limits(Duration.ofMinutes(1), 1000, 1.0, 500),
                Map.of(), (ref, type) -> null);
        return fallback ? r.asFallback() : r;
    }

    private static WorkflowProperties props(long maxTokensPerRun) {
        return new WorkflowProperties(0, null, null, null, null, null, null,
                new WorkflowProperties.Limits(null, maxTokensPerRun, 10.0, 5.0, 25.0, 300), null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<LlmClient> provider(LlmClient client) {
        ObjectProvider<LlmClient> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(client);
        return p;
    }

    private static BudgetLedger ledger(WorkflowProperties props) {
        WorkflowArtifactRepository repo = mock(WorkflowArtifactRepository.class);
        when(repo.findByRunIdOrderByIdAsc(1L)).thenReturn(List.of());
        return new BudgetLedger(repo, props);
    }

    static class ScriptedLlm implements LlmClient {
        final AtomicLong lastCap = new AtomicLong();
        RuntimeException failure;
        long tokens = 100;

        @Override
        public String modelId() {
            return "scripted";
        }

        @Override
        public <T> LlmResponse<T> generate(String s, String u, Class<T> t) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> LlmResponse<T> generate(String s, String u, Class<T> t, long max) {
            lastCap.set(max);
            if (failure != null) {
                throw failure;
            }
            return new LlmResponse<>(t.cast(new Out("model")), tokens, tokens);
        }
    }

    @Test
    void router_demoModeAndFallback_useTheDeterministicAdapter_llmModeUsesTheModel() {
        WorkflowProperties props = props(10_000);
        ScriptedLlm llm = new ScriptedLlm();
        AdapterRouter router = new AdapterRouter(new DeterministicDemoAdapter(props), new LlmExecutionAdapter(provider(llm), ledger(props), props));
        ToolPolicy tools = ToolPolicy.of(ToolPolicy.Tool.LLM_GENERATE);

        Generation<Out> demo = router.generate(GEN, request(ExecutionMode.DETERMINISTIC_DEMO, false, tools));
        Generation<Out> real = router.generate(GEN, request(ExecutionMode.LLM, false, tools));
        Generation<Out> fallback = router.generate(GEN, request(ExecutionMode.LLM, true, tools));

        assertThat(demo.output().v()).isEqualTo("demo");
        assertThat(demo.origin()).isEqualTo(ArtifactOrigin.DETERMINISTIC_DEMO);
        assertThat(real.output().v()).isEqualTo("model");
        assertThat(real.origin()).isEqualTo(ArtifactOrigin.LLM);
        assertThat(real.model()).isEqualTo("scripted");
        assertThat(real.usage().estimatedCostUsd()).isGreaterThan(0);
        assertThat(fallback.origin()).as("fallback is labeled demo, never LLM").isEqualTo(ArtifactOrigin.DETERMINISTIC_DEMO);
        assertThat(llm.lastCap.get()).as("per-call output cap = min(config 300, request 500)").isEqualTo(300);
    }

    @Test
    void llmAdapter_requiresTheLlmTool() {
        WorkflowProperties props = props(10_000);
        LlmExecutionAdapter adapter = new LlmExecutionAdapter(provider(new ScriptedLlm()), ledger(props), props);

        assertThatThrownBy(() -> adapter.generate(GEN, request(ExecutionMode.LLM, false, ToolPolicy.of(ToolPolicy.Tool.READ_ARTIFACTS))))
                .isInstanceOf(AgentFailures.ToolNotPermitted.class);
    }

    @Test
    void llmAdapter_mapsRetryableErrorsToTransient_andOthersToAgentFailure() {
        WorkflowProperties props = props(10_000);
        ScriptedLlm llm = new ScriptedLlm();
        LlmExecutionAdapter adapter = new LlmExecutionAdapter(provider(llm), ledger(props), props);
        AgentRequest req = request(ExecutionMode.LLM, false, ToolPolicy.of(ToolPolicy.Tool.LLM_GENERATE));

        llm.failure = new LlmException("HTTP 529", null, true);
        assertThatThrownBy(() -> adapter.generate(GEN, req)).isInstanceOf(AgentFailures.Transient.class);
        llm.failure = new LlmException("refusal", null, false);
        assertThatThrownBy(() -> adapter.generate(GEN, req)).isInstanceOf(AgentFailures.AgentFailure.class)
                .isNotInstanceOf(AgentFailures.Transient.class);
    }

    @Test
    void llmAdapter_refusesCallsOnceTheRunBudgetIsSpent() {
        WorkflowProperties props = props(250);
        ScriptedLlm llm = new ScriptedLlm();
        LlmExecutionAdapter adapter = new LlmExecutionAdapter(provider(llm), ledger(props), props);
        AgentRequest req = request(ExecutionMode.LLM, false, ToolPolicy.of(ToolPolicy.Tool.LLM_GENERATE));

        adapter.generate(GEN, req); // spends 200 of 250 tokens
        adapter.generate(GEN, req); // allowed (check-then-call), overshoots to 400

        assertThatThrownBy(() -> adapter.generate(GEN, req)).isInstanceOf(AgentFailures.BudgetExceeded.class)
                .hasMessageContaining("token budget exhausted");
    }

    @Test
    void llmAdapter_withoutClient_failsLoudly() {
        WorkflowProperties props = props(10_000);
        LlmExecutionAdapter adapter = new LlmExecutionAdapter(provider(null), ledger(props), props);

        assertThat(adapter.available()).isFalse();
        assertThatThrownBy(() -> adapter.generate(GEN, request(ExecutionMode.LLM, false, ToolPolicy.of(ToolPolicy.Tool.LLM_GENERATE))))
                .isInstanceOf(AgentFailures.AgentFailure.class).hasMessageContaining("APP_AGENTIC_LLM_ENABLED");
    }

    @Test
    void retryBackoff_growsExponentially_andIsCapped() {
        WorkflowProperties.Retry retry = new WorkflowProperties.Retry(5, Duration.ofMillis(100), 2.0, Duration.ofMillis(350));

        assertThat(retry.backoffBefore(2)).isEqualTo(Duration.ofMillis(100));
        assertThat(retry.backoffBefore(3)).isEqualTo(Duration.ofMillis(200));
        assertThat(retry.backoffBefore(4)).isEqualTo(Duration.ofMillis(350));
    }

    @Test
    void properties_rejectAnUnknownFallbackPolicy() {
        assertThatThrownBy(() -> new WorkflowProperties(0, null, null, null, null, null, "maybe", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
