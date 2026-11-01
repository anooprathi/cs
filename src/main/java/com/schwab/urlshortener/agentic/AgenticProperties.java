package com.schwab.urlshortener.agentic;

import com.schwab.urlshortener.agentic.model.ExecutionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds {@code app.agentic.*}. Picked up via {@code @ConfigurationPropertiesScan},
 * same as BillingProperties/RateLimitProperties.
 *
 * Defaults are applied in the compact constructors rather than relying on
 * every profile to set every value, so a missing property can never mean
 * "LLM accidentally on" — {@code llm.enabled} defaults to false, and an
 * absent {@code default-mode} means DETERMINISTIC_DEMO.
 */
@ConfigurationProperties(prefix = "app.agentic")
public record AgenticProperties(ExecutionMode defaultMode, Llm llm) {

    public AgenticProperties {
        if (defaultMode == null) {
            defaultMode = ExecutionMode.DETERMINISTIC_DEMO;
        }
        if (llm == null) {
            llm = new Llm(false, null, 0, null, -1);
        }
    }

    /**
     * Real-LLM settings. The API key is deliberately NOT a property here:
     * it's read by the SDK from the ANTHROPIC_API_KEY environment variable
     * only, so it can't end up in a committed properties file or in
     * /actuator/configprops output.
     */
    public record Llm(boolean enabled, String model, long maxTokens, Duration timeout, int maxRetries) {

        public static final String DEFAULT_MODEL = "claude-opus-5";

        public Llm {
            if (model == null || model.isBlank()) {
                model = DEFAULT_MODEL;
            }
            if (maxTokens <= 0) {
                maxTokens = 16_000;
            }
            if (timeout == null) {
                timeout = Duration.ofMinutes(10);
            }
            if (maxRetries < 0) {
                maxRetries = 2;
            }
        }
    }
}
