package com.schwab.urlshortener.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.security.AdminAuthenticationFilter;
import com.schwab.urlshortener.security.ApiKeyAuthenticationFilter;
import com.schwab.urlshortener.tenant.TenantRegistrationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The workflow API through the real security chain and the real stage agents
 * (DETERMINISTIC_DEMO, workspaces disabled so it needs no git): submit, start,
 * inspect status, artifacts, lineage, history, timeline, and metrics, then decide
 * the release checkpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.workflow.workspace.enabled=false")
class WorkflowApiIntegrationTest {

    private static final String BASE = "/api/v1/workflows";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;

    private ResultActions admin(MockHttpServletRequestBuilder b) throws Exception {
        return mockMvc.perform(b.header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, "admin-test-key"));
    }

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    @Test
    void operatorOnly_401WithoutKey_403WithTenantKey() throws Exception {
        mockMvc.perform(get(BASE + "/capabilities")).andExpect(status().isUnauthorized());
        String body = mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                .content(json(new TenantRegistrationRequest("wf-" + System.nanoTime())))).andReturn().getResponse().getContentAsString();
        String key = objectMapper.readTree(body).get("apiKey").asText();
        mockMvc.perform(get(BASE + "/capabilities").header(ApiKeyAuthenticationFilter.API_KEY_HEADER, key)).andExpect(status().isForbidden());
    }

    @Test
    void capabilities_and_definitionValidation() throws Exception {
        admin(get(BASE + "/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableModes", contains("DETERMINISTIC_DEMO")))
                .andExpect(jsonPath("$.agents", hasSize(7)))
                .andExpect(jsonPath("$.agents[*].stage", hasItems("REQUIREMENT_ANALYSIS", "ARCHITECTURE_DESIGN", "IMPLEMENTATION",
                        "TEST_GENERATION_AND_EXECUTION", "SECURITY_POLICY_VALIDATION", "DOCUMENTATION", "RELEASE_READINESS")))
                .andExpect(jsonPath("$.testRunner", startsWith("default plan-verification; COMMAND runs [mvn")));
        admin(post(BASE + "/definitions/validate").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"cyclic","version":1,"tasks":[
                  {"key":"a","agent":"requirement-analysis","dependsOn":["b"]},
                  {"key":"b","agent":"architecture-design","dependsOn":["a"]}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[*].code", hasItem("CYCLE")));
        admin(post(BASE + "/runs").contentType(MediaType.APPLICATION_JSON).content("""
                {"title":"t","requirement":"r r r","definition":{"name":"x","version":1,"tasks":[
                  {"key":"d","agent":"architecture-design","dependsOn":[]}]}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("MISSING_INPUT")));
    }

    @Test
    void fullRun_throughTheApi() throws Exception {
        String created = admin(post(BASE + "/runs").contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                "title", "API run",
                "requirement", "Tenants must be able to add a note to a short link. An empty note must be rejected with 400 Bad Request."))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.executionMode").value("DETERMINISTIC_DEMO"))
                .andExpect(jsonPath("$.executionNotice", containsString("no language model is called")))
                .andExpect(jsonPath("$.tasks", hasSize(7)))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(created).get("id").asLong();

        admin(post(BASE + "/runs/" + id + "/start")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.tasks[?(@.key=='release')].state", contains("WAITING_APPROVAL")))
                .andExpect(jsonPath("$.tasks[?(@.key=='implementation')].outputs['change-patch']", hasSize(1)));

        admin(get(BASE + "/runs/" + id + "/artifacts")).andExpect(status().isOk()).andExpect(jsonPath("$[*].name", hasItems(
                "requirement-spec", "architecture-design", "implementation-plan", "change-patch", "interface-contract", "test-plan",
                "test-report", "risk-assessment", "policy-report", "documentation", "readiness-report")));
        // no demo recipe recognizes "add a note", so the patch is a change record with a derived (not exact) contract
        admin(get(BASE + "/runs/" + id + "/artifacts/change-patch")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.kind").value("DOCUMENT"))
                .andExpect(jsonPath("$.content.author").value("change-record"));
        admin(get(BASE + "/runs/" + id + "/artifacts/interface-contract")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.source").value("DERIVED_PROPOSAL"))
                .andExpect(jsonPath("$.markdown", containsString("not an exact contract")));
        admin(get(BASE + "/runs/" + id + "/artifacts/test-report")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.productTestsExecuted").value(false))
                .andExpect(jsonPath("$.markdown", containsString("_Origin:")));
        String lineage = admin(get(BASE + "/runs/" + id + "/artifacts/readiness-report/lineage")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode l = objectMapper.readTree(lineage);
        assertThat(l.get("inputs").findValuesAsText("label")).anyMatch(s -> s.startsWith("change-patch@v1"));
        assertThat(l.get("agentResult").get("decisions").size()).isGreaterThan(0);

        admin(get(BASE + "/runs/" + id + "/history")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("RUN_CREATED"))
                .andExpect(jsonPath("$[*].type", hasItems("TASK_TRANSITION", "ENTRY_GATE_PASSED", "EXIT_GATE_PASSED", "APPROVAL_REQUESTED")));
        admin(get(BASE + "/runs/" + id + "/timeline")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(content().string(containsString("Execution mode: DETERMINISTIC_DEMO")));

        admin(post(BASE + "/runs/" + id + "/tasks/release/decision").contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                "decision", "APPROVED", "reviewer", "eng-lead", "artifactVersions", Map.of("readiness-report", 2)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Stale review")));
        admin(post(BASE + "/runs/" + id + "/tasks/release/decision").contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                "decision", "APPROVED", "reviewer", "eng-lead", "artifactVersions", Map.of("readiness-report", 1)))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.approvals[0].reviewer").value("eng-lead"));

        admin(get(BASE + "/runs/" + id + "/audit/verify")).andExpect(jsonPath("$.valid").value(true));
        admin(get(BASE + "/runs/" + id + "/metrics")).andExpect(status().isOk())
                .andExpect(jsonPath("$.runSuccessRate").value(1.0))
                .andExpect(jsonPath("$.definitions.mttr", containsString("mean")));
        admin(get(BASE + "/metrics")).andExpect(status().isOk()).andExpect(jsonPath("$.runs", greaterThanOrEqualTo(1)));
        admin(get(BASE + "/runs/" + id + "/report.md")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Final engineering summary")))
                .andExpect(content().string(containsString("### What was built")))
                .andExpect(content().string(containsString("DOCUMENT by `change-record`")))
                .andExpect(content().string(containsString("### Plan and rationale")))
                .andExpect(content().string(containsString("| T-1 |")))
                .andExpect(content().string(containsString("_Why:_")))
                .andExpect(content().string(containsString("### Risks and trade-offs")))
                .andExpect(content().string(containsString("### Assumptions")))
                .andExpect(content().string(containsString("### Validation")))
                .andExpect(content().string(containsString("| implementation | SUCCEEDED | passed 7/7 |")))
                .andExpect(content().string(containsString("Release readiness (**ready**)")))
                .andExpect(content().string(containsString("### Limitations (what this run did NOT do)")))
                .andExpect(content().string(containsString("the patch is a change-record document")));
        admin(post(BASE + "/runs/" + id + "/promotion").contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                "reviewer", "eng-lead", "patchVersion", 1, "confirm", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("no workspace")));
        admin(get(BASE + "/runs")).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(id));
    }

    @Test
    void credentialInTheRequirement_is400_andNothingIsStoredOrSent() throws Exception {
        admin(post(BASE + "/runs").contentType(MediaType.APPLICATION_JSON).content(json(Map.of("title", "probe",
                "requirement", "Tenants must log in. Repro: X-API-Key: usk_SYNTHETICprobeKEY0000000000"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("tenant API key")))
                .andExpect(jsonPath("$.message", not(containsString("usk_SYNTHETIC"))));
        admin(post("/api/v1/agentic/runs").contentType(MediaType.APPLICATION_JSON).content(json(Map.of("title", "probe",
                "requirement", "Tenants must log in. Repro: X-API-Key: usk_SYNTHETICprobeKEY0000000000"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("tenant API key")));
    }

    @Test
    void validationAndModeErrors() throws Exception {
        admin(post(BASE + "/runs").contentType(MediaType.APPLICATION_JSON).content(json(Map.of("title", "t", "requirement", " "))))
                .andExpect(status().isBadRequest());
        admin(post(BASE + "/runs").contentType(MediaType.APPLICATION_JSON).content(json(Map.of("title", "t", "requirement", "r r r", "mode", "LLM"))))
                .andExpect(status().isServiceUnavailable());
        admin(get(BASE + "/runs/999999")).andExpect(status().isNotFound());
        admin(post(BASE + "/runs/1/promotion").contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                "reviewer", "x", "patchVersion", 1, "confirm", false)))).andExpect(status().isBadRequest());
    }
}
