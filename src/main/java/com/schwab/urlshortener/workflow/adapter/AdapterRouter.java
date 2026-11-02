package com.schwab.urlshortener.workflow.adapter;

import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.workflow.engine.AgentRequest;
import org.springframework.stereotype.Component;

/**
 * Chooses the adapter for one generative step. DETERMINISTIC_DEMO runs always
 * use the demo adapter. LLM runs use the LLM adapter, except during the
 * engine's explicit fallback execution ({@link AgentRequest#fallback()}). That
 * output is labeled DETERMINISTIC_DEMO and the engine requires human approval
 * of it.
 */
@Component
public class AdapterRouter {

    private final DeterministicDemoAdapter demo;
    private final LlmExecutionAdapter llm;

    public AdapterRouter(DeterministicDemoAdapter demo, LlmExecutionAdapter llm) {
        this.demo = demo;
        this.llm = llm;
    }

    public <T> Generation<T> generate(GenerationRequest<T> generation, AgentRequest request) {
        ExecutionAdapter adapter = request.mode() == ExecutionMode.LLM && !request.fallback() ? llm : demo;
        return adapter.generate(generation, request);
    }

    public boolean llmAvailable() {
        return llm.available();
    }

    public String llmModel() {
        return llm.model();
    }

    public DeterministicDemoAdapter demo() {
        return demo;
    }
}
