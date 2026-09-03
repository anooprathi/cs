package com.schwab.urlshortener.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.billing.InvoiceRepository;
import com.schwab.urlshortener.billing.TenantUsageRecordRepository;
import com.schwab.urlshortener.dto.ShortenUrlRequest;
import com.schwab.urlshortener.repository.UrlMappingRepository;
import com.schwab.urlshortener.security.AdminAuthenticationFilter;
import com.schwab.urlshortener.security.ApiKeyAuthenticationFilter;
import com.schwab.urlshortener.tenant.RateLimitPlan;
import com.schwab.urlshortener.tenant.TenantRegistrationRequest;
import com.schwab.urlshortener.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end tests for the admin API surface — a genuinely distinct
 * identity from tenant API keys (see AdminAuthenticationFilter), so these
 * are kept in their own test class rather than folded into
 * UrlShortenerIntegrationTest.
 *
 * The admin key used here ("admin-test-key") matches the hash configured
 * in application-test.properties — see that file's comment.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminIntegrationTest {

    private static final String ADMIN_KEY = "admin-test-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UrlMappingRepository urlMappingRepository;

    @Autowired
    private TenantUsageRecordRepository tenantUsageRecordRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    private String tenantApiKey;
    private long tenantId;

    @BeforeEach
    void setUp() throws Exception {
        invoiceRepository.deleteAll();
        tenantUsageRecordRepository.deleteAll();
        urlMappingRepository.deleteAll();
        tenantRepository.deleteAll();

        TenantRegistrationRequest request = new TenantRegistrationRequest("acme-" + System.nanoTime(), RateLimitPlan.STANDARD);
        String body = mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        tenantApiKey = objectMapper.readTree(body).get("apiKey").asText();
        tenantId = objectMapper.readTree(body).get("tenantId").asLong();
    }

    // ---------- Authentication boundary ----------

    @Test
    void adminEndpoint_withoutAdminKey_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenants"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpoint_withWrongAdminKey_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenants")
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, "not-the-real-admin-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpoint_withTenantApiKeyInsteadOfAdminKey_returns403() throws Exception {
        // A tenant's own key legitimately authenticates them (as ROLE_TENANT,
        // via ApiKeyAuthenticationFilter) — they're just missing ROLE_ADMIN.
        // That's "authenticated but insufficient privileges" (403), distinct
        // from presenting no credential at all (401, covered above).
        mockMvc.perform(get("/api/v1/admin/tenants")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, tenantApiKey))
                .andExpect(status().isForbidden());
    }

    // ---------- Tenant listing / detail ----------

    @Test
    void listTenants_returnsRegisteredTenant() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenants")
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.tenantId == " + tenantId + ")].plan", hasItem("STANDARD")))
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(1)));
    }

    @Test
    void getTenantDetail_returnsUsageSnapshot() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenants/{id}", tenantId)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId", is((int) tenantId)))
                .andExpect(jsonPath("$.plan", is("STANDARD")))
                .andExpect(jsonPath("$.totalLinkCount", is(0)))
                .andExpect(jsonPath("$.apiCallsThisPeriod", is(0)));
    }

    @Test
    void getTenantDetail_unknownTenant_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenants/{id}", 999999)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY))
                .andExpect(status().isNotFound());
    }

    // ---------- Plan / status management ----------

    @Test
    void updatePlan_upgradesTenant() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tenants/{id}/plan", tenantId)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\": \"PREMIUM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan", is("PREMIUM")));

        // Confirm it actually persisted, not just echoed back.
        mockMvc.perform(get("/api/v1/admin/tenants/{id}", tenantId)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY))
                .andExpect(jsonPath("$.plan", is("PREMIUM")));
    }

    @Test
    void updateStatus_deactivatingTenant_blocksTheirApiKey() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tenants/{id}/status", tenantId)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(false)));

        // The tenant's own key must now be rejected — active=false is a
        // real suspension, not just a cosmetic flag.
        mockMvc.perform(get("/api/v1/tenants/me")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, tenantApiKey))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updatePlan_unknownTenant_returns404() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tenants/{id}/plan", 999999)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\": \"PREMIUM\"}"))
                .andExpect(status().isNotFound());
    }

    // ---------- Cross-tenant link visibility ----------

    @Test
    void listTenantUrls_returnsLinksCreatedByThatTenant() throws Exception {
        ShortenUrlRequest urlRequest = new ShortenUrlRequest("https://www.schwab.com/admin-visible", null, null);
        mockMvc.perform(post("/api/v1/urls")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, tenantApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(urlRequest)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/admin/tenants/{id}/urls", tenantId)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].originalUrl", is("https://www.schwab.com/admin-visible")))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    void listTenantUrls_unknownTenant_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenants/{id}/urls", 999999)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY))
                .andExpect(status().isNotFound());
    }

    // ---------- Platform usage summary ----------

    @Test
    void usageSummary_reflectsActivityAcrossTenants() throws Exception {
        ShortenUrlRequest urlRequest = new ShortenUrlRequest("https://www.schwab.com/usage-check", null, null);
        mockMvc.perform(post("/api/v1/urls")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, tenantApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(urlRequest)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/admin/usage")
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalApiCalls", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.activeTenantCount", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.byTenant[?(@.tenantId == " + tenantId + ")].apiCalls", hasItem(greaterThanOrEqualTo(1))));
    }
}
