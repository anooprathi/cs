package com.schwab.urlshortener.agentic.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.schwab.urlshortener.agentic.AgenticFixtures;
import com.schwab.urlshortener.agentic.agent.AgentContext;
import com.schwab.urlshortener.agentic.agent.RequirementAnalystAgent;
import com.schwab.urlshortener.agentic.model.RequirementSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPT-IN live call to the real Anthropic API. It spends real API credit, so it
 * never runs in a normal build: it is skipped unless AGENTIC_LIVE_SMOKE=true
 * is set (and the SDK can find credentials, normally ANTHROPIC_API_KEY).
 *
 *   AGENTIC_LIVE_SMOKE=true ANTHROPIC_API_KEY=... mvn test -Dtest=AnthropicLiveSmokeTest -Djacoco.skip=true
 *
 * It sends the REQUIREMENTS-stage prompts for the fixture requirement and
 * checks that a schema-valid RequirementSpec comes back. Passing means the
 * real request shape is accepted and structured outputs parse. It says
 * nothing about artifact quality.
 */
@EnabledIfEnvironmentVariable(named = "AGENTIC_LIVE_SMOKE", matches = "true")
class AnthropicLiveSmokeTest {

    @Test
    void realModel_returnsSchemaValidRequirementSpec() {
        String model = System.getenv().getOrDefault("AGENTIC_LIVE_MODEL", "claude-opus-5");
        // AnthropicClient has close() but is not AutoCloseable, hence try/finally.
        AnthropicClient client = AnthropicOkHttpClient.builder().fromEnv().timeout(Duration.ofMinutes(10)).build();
        try {
            AnthropicLlmClient llm = new AnthropicLlmClient(client, model, 16_000);
            RequirementAnalystAgent agent = new RequirementAnalystAgent(AgenticFixtures.prompts());
            AgentContext ctx = AgenticFixtures.context(AgenticFixtures.TITLE, AgenticFixtures.REQUIREMENT);

            LlmResponse<RequirementSpec> response = llm.generate(agent.systemPrompt(), agent.userPrompt(ctx), RequirementSpec.class);

            System.out.printf("Live smoke: model=%s inputTokens=%d outputTokens=%d criteria=%d ambiguities=%d%n",
                    model, response.inputTokens(), response.outputTokens(),
                    response.output().acceptanceCriteria().size(), response.output().ambiguities().size());
            assertThat(response.output().acceptanceCriteria()).isNotEmpty();
            assertThat(response.output().functionalRequirements()).isNotEmpty();
            assertThat(response.inputTokens()).isPositive();
        } finally {
            client.close();
        }
    }
}
