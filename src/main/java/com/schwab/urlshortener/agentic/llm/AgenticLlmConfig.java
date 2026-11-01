package com.schwab.urlshortener.agentic.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.schwab.urlshortener.agentic.AgenticProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the Anthropic client and {@link LlmClient} ONLY when
 * app.agentic.llm.enabled=true. With the default (false) neither bean
 * exists, nothing reads ANTHROPIC_API_KEY, and no outbound connection to
 * the Anthropic API is possible from this app.
 *
 * {@code fromEnv()} resolves credentials the SDK's standard way
 * (ANTHROPIC_API_KEY, or the other SDK-supported sources), so the key never
 * passes through a Spring property and can't leak via /actuator/configprops.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.agentic.llm", name = "enabled", havingValue = "true")
@Slf4j
public class AgenticLlmConfig {

    @Bean(destroyMethod = "close")
    public AnthropicClient anthropicClient(AgenticProperties properties) {
        AgenticProperties.Llm llm = properties.llm();
        return AnthropicOkHttpClient.builder()
                .fromEnv()
                .timeout(llm.timeout())
                .maxRetries(llm.maxRetries())
                .build();
    }

    @Bean
    public LlmClient llmClient(AnthropicClient anthropicClient, AgenticProperties properties) {
        AgenticProperties.Llm llm = properties.llm();
        log.warn("Agentic LLM mode ENABLED: model={}, maxTokens={}. Requirement text of LLM-mode runs "
                + "will be sent to the Anthropic API.", llm.model(), llm.maxTokens());
        return new AnthropicLlmClient(anthropicClient, llm.model(), llm.maxTokens());
    }
}
