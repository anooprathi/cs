package com.schwab.urlshortener.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.agentic.AgenticFixtures;
import com.schwab.urlshortener.agentic.SdlcArtifactRepository;
import com.schwab.urlshortener.agentic.SdlcRun;
import com.schwab.urlshortener.agentic.SdlcRunRepository;
import com.schwab.urlshortener.agentic.api.CreateSdlcRunRequest;
import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.security.AdminAuthenticationFilter;
import com.schwab.urlshortener.security.ApiKeyAuthenticationFilter;
import com.schwab.urlshortener.tenant.TenantRegistrationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The agentic SDLC API end to end, through the real security filter chain,
 * JPA on H2, and the default (LLM disabled) configuration, which is the
 * configuration the app ships with. LLM-mode behavior through HTTP is
 * covered separately by AgenticLlmModeIntegrationTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgenticIntegrationTest {

    private static final String ADMIN_KEY = "admin-test-key";
    private static final String RUNS = "/api/v1/agentic/runs";

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

    // ---------- security boundary ----------

    @Test
    void noCredential_is401() throws Exception {
        mockMvc.perform(get("/api/v1/agentic/capabilities")).andExpect(status().isUnauthorized());
    }

    @Test
    void tenantKey_is403_theOrchestratorIsAnOperatorCapability() throws Exception {
        String body = mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TenantRegistrationRequest("agentic-" + System.nanoTime()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String tenantKey = objectMapper.readTree(body).get("apiKey").asText();

        mockMvc.perform(post(RUNS).header(ApiKeyAuthenticationFilter.API_KEY_HEADER, tenantKey)
                        .contentType(MediaType.APPLICATION_JSON).content(json(request(null))))
                .andExpect(status().isForbidden());
        assertThat(runRepository.count()).isZero();
    }

    @Test
    void wrongAdminKey_is401() throws Exception {
        mockMvc.perform(get("/api/v1/agentic/capabilities").header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, "nope"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- capabilities ----------

    @Test
    void capabilities_reportOnlyDemoMode_whenLlmIsNotConfigured() throws Exception {
        admin(get("/api/v1/agentic/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultMode").value("DETERMINISTIC_DEMO"))
                .andExpect(jsonPath("$.availableModes", contains("DETERMINISTIC_DEMO")))
                .andExpect(jsonPath("$.llmEnabled").value(false))
                .andExpect(jsonPath("$.llmModel").doesNotExist())
                .andExpect(jsonPath("$.stages", contains("REQUIREMENTS", "IMPACT", "PLAN", "TESTS", "RISKS", "REVIEW")))
                .andExpect(jsonPath("$.catalogComponentCount", greaterThan(20)))
                .andExpect(jsonPath("$.notice", containsString("disabled")));
    }

    // ---------- run lifecycle ----------

    @Test
    void demoRun_returns201_withSixStages_andPlainEnglishExecutionNotice() throws Exception {
        admin(post(RUNS).contentType(MediaType.APPLICATION_JSON).content(json(request(null))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/agentic/runs/")))
                .andExpect(jsonPath("$.executionMode").value("DETERMINISTIC_DEMO"))
                .andExpect(jsonPath("$.model").doesNotExist())
                .andExpect(jsonPath("$.executionNotice", containsString("no language model was called")))
                .andExpect(jsonPath("$.status").value("AWAITING_REVIEW"))
                .andExpect(jsonPath("$.qualityGatePassed").value(true))
                .andExpect(jsonPath("$.stages", hasSize(6)))
                .andExpect(jsonPath("$.stages[0].stage").value("REQUIREMENTS"))
                .andExpect(jsonPath("$.stages[0].source").value("DETERMINISTIC_DEMO"))
                .andExpect(jsonPath("$.stages[5].stage").value("REVIEW"))
                .andExpect(jsonPath("$.stages[5].source").value("DETERMINISTIC_GUARDRAIL"))
                .andExpect(jsonPath("$.stages[*].status", everyItem(is("COMPLETED"))));
    }

    @Test
    void llmMode_whileDisabled_is503_inTheSharedErrorEnvelope_andNothingIsStored() throws Exception {
        admin(post(RUNS).contentType(MediaType.APPLICATION_JSON).content(json(request(ExecutionMode.LLM))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message", containsString("app.agentic.llm.enabled=false")))
                .andExpect(jsonPath("$.path").value(RUNS));
        assertThat(runRepository.count()).isZero();
    }

    @Test
    void invalidRequests_are400() throws Exception {
        admin(post(RUNS).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("title", "t", "requirement", " "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(startsWith("requirement:"))));
        admin(post(RUNS).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "t", "requirement", "x".repeat(10_001)))))
                .andExpect(status().isBadRequest());
        admin(post(RUNS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"requirement\":\"r\",\"mode\":\"MAGIC\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void artifact_returnsTypedJsonAndMarkdown() throws Exception {
        long runId = startRun();

        admin(get(RUNS + "/" + runId + "/artifacts/IMPACT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("IMPACT"))
                .andExpect(jsonPath("$.source").value("DETERMINISTIC_DEMO"))
                .andExpect(jsonPath("$.content.impactedComponents[*].component", hasItem("UrlShortenerServiceImpl")))
                .andExpect(jsonPath("$.markdown", startsWith("_Source: deterministic rule-based demo logic")));
        admin(get(RUNS + "/" + runId + "/artifacts/REVIEW"))
                .andExpect(jsonPath("$.content.passed").value(true))
                .andExpect(jsonPath("$.source").value("DETERMINISTIC_GUARDRAIL"));
    }

    @Test
    void unknownRun_is404_andUnknownStage_is400() throws Exception {
        admin(get(RUNS + "/999999")).andExpect(status().isNotFound());
        admin(get(RUNS + "/999999/artifacts/PLAN")).andExpect(status().isNotFound());
        admin(get(RUNS + "/999999/report.md")).andExpect(status().isNotFound());
        long runId = startRun();
        admin(get(RUNS + "/" + runId + "/artifacts/DEPLOY")).andExpect(status().isBadRequest());
    }

    @Test
    void report_isMarkdown_bannerFirst_withEveryStage() throws Exception {
        long runId = startRun();

        String md = admin(get(RUNS + "/" + runId + "/report.md"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andReturn().getResponse().getContentAsString();

        assertThat(md).startsWith("# SDLC run " + runId + ": " + AgenticFixtures.TITLE);
        assertThat(md).contains("**Execution mode: DETERMINISTIC_DEMO. No language model was called.**");
        assertThat(md).contains("## Original requirement", "## 1. Requirements", "## 2. Impact analysis",
                "## 3. Implementation plan", "## 4. Test plan", "## 5. Risk assessment", "## 6. Quality gate",
                "_Awaiting review. Nothing in this document has been applied to the codebase._");
        assertThat(md.indexOf("DETERMINISTIC_DEMO")).isLessThan(md.indexOf("## 1. Requirements"));
    }

    @Test
    void list_isPaged_newestFirst() throws Exception {
        long first = startRun();
        long second = startRun();

        admin(get(RUNS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(second))
                .andExpect(jsonPath("$.content[1].id").value(first))
                .andExpect(jsonPath("$.content[0].executionMode").value("DETERMINISTIC_DEMO"));
    }

    // ---------- human review ----------

    @Test
    void approve_recordsDecision_onceOnly() throws Exception {
        long runId = startRun();

        admin(post(RUNS + "/" + runId + "/review").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("decision", "APPROVED", "reviewer", "eng-lead", "comment", "Plan looks right."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.review.reviewer").value("eng-lead"))
                .andExpect(jsonPath("$.review.reviewedAt").exists());

        admin(post(RUNS + "/" + runId + "/review").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("decision", "CHANGES_REQUESTED", "reviewer", "someone-else", "comment", "late"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("APPROVED")));

        String md = admin(get(RUNS + "/" + runId + "/report.md")).andReturn().getResponse().getContentAsString();
        assertThat(md).contains("**APPROVED** by eng-lead").contains("> Plan looks right.");
    }

    @Test
    void changesRequested_requiresComment() throws Exception {
        long runId = startRun();

        admin(post(RUNS + "/" + runId + "/review").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("decision", "CHANGES_REQUESTED", "reviewer", "eng-lead"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A comment is required when requesting changes."));

        admin(post(RUNS + "/" + runId + "/review").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("decision", "CHANGES_REQUESTED", "reviewer", "eng-lead",
                                "comment", "Confirm AMB-1 latency target first."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHANGES_REQUESTED"));
    }

    @Test
    void approve_isRefused_whenQualityGateHasBlockers() throws Exception {
        long runId = startRun();
        // Demo-mode artifacts pass the gate by construction, so a gate failure (the LLM-mode case)
        // is simulated at the row level. The gate's own blocker logic is covered by QualityGateReviewerTest.
        SdlcRun run = runRepository.findById(runId).orElseThrow();
        run.setQualityGatePassed(false);
        run.setBlockerCount(2);
        runRepository.save(run);

        admin(post(RUNS + "/" + runId + "/review").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("decision", "APPROVED", "reviewer", "eng-lead"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("2 blocker(s)")));
    }

    @Test
    void review_validatesBody() throws Exception {
        long runId = startRun();

        admin(post(RUNS + "/" + runId + "/review").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("decision", "APPROVED"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(startsWith("reviewer:"))));
    }

    // ---------- helpers ----------

    private long startRun() throws Exception {
        String body = admin(post(RUNS).contentType(MediaType.APPLICATION_JSON).content(json(request(null))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        return node.get("id").asLong();
    }

    private static CreateSdlcRunRequest request(ExecutionMode mode) {
        return new CreateSdlcRunRequest(AgenticFixtures.TITLE, AgenticFixtures.REQUIREMENT, mode);
    }

    private org.springframework.test.web.servlet.ResultActions admin(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
