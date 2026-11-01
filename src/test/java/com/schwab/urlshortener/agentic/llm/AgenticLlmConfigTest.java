package com.schwab.urlshortener.agentic.llm;

import com.schwab.urlshortener.agentic.AgenticProperties;
import com.schwab.urlshortener.agentic.model.ExecutionMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The feature flag is the only thing standing between "no outbound calls
 * possible" and "requirement text goes to a third party", so its wiring is
 * tested directly rather than assumed from the @ConditionalOnProperty.
 */
class AgenticLlmConfigTest {

    @Configuration
    @EnableConfigurationProperties(AgenticProperties.class)
    static class PropsConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropsConfig.class, AgenticLlmConfig.class);

    @Test
    void disabledByDefault_noClientBeansExist() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(LlmClient.class);
            assertThat(ctx).doesNotHaveBean(com.anthropic.client.AnthropicClient.class);
        });
    }

    @Test
    void explicitlyDisabled_noClientBeansExist() {
        runner.withPropertyValues("app.agentic.llm.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(LlmClient.class));
    }

    @Test
    void enabled_createsAnthropicBackedClient_withConfiguredModel() {
        // Building the client makes no network call. A dummy key is passed via the
        // SDK's system-property source so the test never depends on the developer's env.
        String previous = System.getProperty("anthropic.apiKey");
        System.setProperty("anthropic.apiKey", "test-key-not-real");
        try {
            runner.withPropertyValues("app.agentic.llm.enabled=true", "app.agentic.llm.model=claude-opus-5",
                            "app.agentic.llm.max-tokens=8000")
                    .run(ctx -> {
                        assertThat(ctx).hasNotFailed();
                        assertThat(ctx.getBean(LlmClient.class)).isInstanceOf(AnthropicLlmClient.class);
                        assertThat(ctx.getBean(LlmClient.class).modelId()).isEqualTo("claude-opus-5");
                    });
        } finally {
            if (previous == null) {
                System.clearProperty("anthropic.apiKey");
            } else {
                System.setProperty("anthropic.apiKey", previous);
            }
        }
    }

    @Test
    void properties_defaultsNeverMeanLlmOn() {
        AgenticProperties props = new AgenticProperties(null, null);

        assertThat(props.defaultMode()).isEqualTo(ExecutionMode.DETERMINISTIC_DEMO);
        assertThat(props.llm().enabled()).isFalse();
        assertThat(props.llm().model()).isEqualTo(AgenticProperties.Llm.DEFAULT_MODEL);
        assertThat(props.llm().maxTokens()).isEqualTo(16_000);
        assertThat(props.llm().timeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(props.llm().maxRetries()).isEqualTo(2);
    }
}
