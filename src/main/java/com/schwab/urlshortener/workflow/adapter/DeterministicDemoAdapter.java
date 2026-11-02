package com.schwab.urlshortener.workflow.adapter;

import com.schwab.urlshortener.workflow.WorkflowProperties;
import com.schwab.urlshortener.workflow.engine.AgentFailures;
import com.schwab.urlshortener.workflow.engine.AgentRequest;
import com.schwab.urlshortener.workflow.engine.AgentResult;
import com.schwab.urlshortener.workflow.engine.ArtifactOrigin;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Reproducible local execution: runs the step's rule-based supplier. Same
 * inputs give the same output, with no network, no credentials, and no cost.
 *
 * {@code app.workflow.demo.simulated-latency} optionally sleeps before
 * returning, so parallel execution is visible in a demo timeline (deterministic
 * steps otherwise finish in microseconds). That delay is simulated. It's off by
 * default and named in the run's audit trail when used.
 */
@Component
public class DeterministicDemoAdapter implements ExecutionAdapter {

    public static final String NAME = "deterministic-demo";

    private final Duration simulatedLatency;

    public DeterministicDemoAdapter(WorkflowProperties properties) {
        this.simulatedLatency = properties.demo().simulatedLatency();
    }

    @Override
    public String name() {
        return NAME;
    }

    public Duration simulatedLatency() {
        return simulatedLatency;
    }

    @Override
    public <T> Generation<T> generate(GenerationRequest<T> generation, AgentRequest request) {
        if (!simulatedLatency.isZero()) {
            try {
                Thread.sleep(simulatedLatency.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentFailures.Transient("interrupted during simulated latency");
            }
        }
        T output = generation.deterministic().get();
        if (output == null) {
            throw new AgentFailures.AgentFailure(generation.purpose() + ": deterministic path produced nothing");
        }
        return new Generation<>(output, ArtifactOrigin.DETERMINISTIC_DEMO, NAME, null, AgentResult.Usage.NONE);
    }
}
