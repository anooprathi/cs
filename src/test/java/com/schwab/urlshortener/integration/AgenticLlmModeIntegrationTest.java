package com.schwab.urlshortener.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.agentic.AgenticFixtures;
import com.schwab.urlshortener.agentic.SdlcArtifactRepository;
import com.schwab.urlshortener.agentic.SdlcRunRepository;
import com.schwab.urlshortener.agentic.agent.AgentContext;
import com.schwab.urlshortener.agentic.api.CreateSdlcRunRequest;
import com.schwab.urlshortener.agentic.llm.LlmClient;
import com.schwab.urlshortener.agentic.llm.LlmResponse;
import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis;
import com.schwab.urlshortener.agentic.model.ImplementationPlan;
import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.RiskAssessment;
import com.schwab.urlshortener.agentic.model.TestPlan;
import com.schwab.urlshortener.security.AdminAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * LLM mode through the full HTTP stack, with a STUB LlmClient bean standing
 * in for Claude. It proves the wiring: an LlmClient bean makes LLM mode
 * available, provenance (model, tokens, source) flows to the API and report,
 * and a model that hallucinates a component gets caught by the
 * deterministic gate, which then blocks approval. It does not exercise a
 * real model. See AnthropicLlmClientTest (SDK boundary, mocked) and
 * AnthropicLiveSmokeTest (opt-in live call).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgenticLlmModeIntegrationTest {

    private static final String ADMIN_KEY = "admin-test-key";

    /** Plays back the demo artifacts, plus one hallucinated component, as if a model had produced them. */
    @TestConfiguration
    static class StubLlmConfig {
        @Bean
        LlmClient stubLlmClient() {
            AgentContext ctx = AgenticFixtures.runDeterministicPipeline(AgenticFixtures.TITLE, AgenticFixtures.REQUIREMENT);
            ImpactAnalysis real = ctx.impactAnalysis();
            List<ImpactAnalysis.ImpactedComponent> withHallucination = new java.util.ArrayList<>(real.impactedComponents());
            withHallucination.add(new ImpactAnalysis.ImpactedComponent("ClickQuotaEnforcer",
                    ImpactAnalysis.ChangeType.MODIFY, "plausible but nonexistent", List.of("AC-2")));
            ImpactAnalysis hallucinated = new ImpactAnalysis(withHallucination, real.newComponents(), real.unaffectedAreas());
            return new LlmClient() {
                @Override
                public String modelId() {
                    return "stub-model";
                }

                @Override
                public <T> LlmResponse<T> generate(String systemPrompt, String userPrompt, Class<T> outputType) {
                    Object out = outputType == RequirementSpec.class ? ctx.requirementSpec()
                            : outputType == ImpactAnalysis.class ? hallucinated
                            : outputType == ImplementationPlan.class ? ctx.implementationPlan()
                            : outputType == TestPlan.class ? ctx.testPlan()
                            : outputType == RiskAssessment.class ? ctx.riskAssessment() : null;
                    return new LlmResponse<>(outputType.cast(out), 10, 20);
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SdlcRunRepository runRepository;

    @Autowired
    private SdlcArtifactRepository artifactRepository;

    @BeforeEach
    void setUp() {
        artifactRepository.deleteAll();
        runRepository.deleteAll();
    }

    @Test
    void capabilities_listLlm_whenAnLlmClientBeanExists() throws Exception {
        mockMvc.perform(get("/api/v1/agentic/capabilities").header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY))
                .andExpect(jsonPath("$.availableModes", contains("DETERMINISTIC_DEMO", "LLM")))
                .andExpect(jsonPath("$.llmModel").value("stub-model"))
                .andExpect(jsonPath("$.defaultMode").value("DETERMINISTIC_DEMO"));
    }

    @Test
    void llmRun_provenanceIsVisible_andHallucinatedComponentBlocksApproval() throws Exception {
        String body = mockMvc.perform(post("/api/v1/agentic/runs").header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateSdlcRunRequest(AgenticFixtures.TITLE,
                                AgenticFixtures.REQUIREMENT, ExecutionMode.LLM))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.executionMode").value("LLM"))
                .andExpect(jsonPath("$.model").value("stub-model"))
                .andExpect(jsonPath("$.executionNotice", startsWith("LLM:")))
                .andExpect(jsonPath("$.stages[0].source").value("LLM"))
                .andExpect(jsonPath("$.stages[0].inputTokens").value(10))
                .andExpect(jsonPath("$.stages[5].source").value("DETERMINISTIC_GUARDRAIL"))
                .andExpect(jsonPath("$.qualityGatePassed").value(false))
                .andExpect(jsonPath("$.blockerCount", greaterThanOrEqualTo(1)))
                .andReturn().getResponse().getContentAsString();
        long runId = objectMapper.readTree(body).get("id").asLong();

        String md = mockMvc.perform(get("/api/v1/agentic/runs/" + runId + "/report.md")
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY))
                .andReturn().getResponse().getContentAsString();
        assertThat(md).contains("**Execution mode: LLM (`stub-model`).**")
                .contains("_Source: generated by `stub-model` (LLM)._")
                .contains("**FAILED**")
                .contains("'ClickQuotaEnforcer' does not exist in this codebase's component catalog");

        mockMvc.perform(post("/api/v1/agentic/runs/" + runId + "/review").header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\",\"reviewer\":\"eng-lead\"}"))
                .andExpect(status().isConflict());
    }
}
