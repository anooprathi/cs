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

import static org.assertj.core.api.Assertions.assertThat;
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

        TenantRegistrationRequest request = new TenantRegistrationRequest("acme-" + System.nanoTime());
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
    void actuatorMetrics_withoutAdminKey_isProtected_notPublic() throws Exception {
        // Regression test for a real production-review finding: this
        // endpoint was reachable completely unauthenticated purely because
        // nothing in SecurityConfig explicitly claimed it and the old
        // catchall defaulted to permitAll(). Internal operational detail
        // (redirect volume, rate-limit rejections by plan, JVM internals)
        // has no business being world-readable.
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorPrometheus_withoutAdminKey_isProtected_notPublic() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorMetrics_withAdminKey_isReachable() throws Exception {
        mockMvc.perform(get("/actuator/metrics")
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY))
                .andExpect(status().isOk());
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
    void listTenants_withInvalidSortProperty_returns400NotA500() throws Exception {
        // Regression test: Swagger UI's unedited default for the array-type
        // ?sort= param on a Pageable endpoint is literally ["string"] --
        // hitting Execute without changing it used to crash with an
        // unhandled PropertyReferenceException (opaque 500) instead of a
        // clean 400 naming the bad property.
        mockMvc.perform(get("/api/v1/admin/tenants?page=0&size=10&sort=%5B%22string%22%5D")
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("string")));
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
    void rotateApiKey_oldKeyStopsWorking_newKeyWorksImmediately() throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/tenants/{id}/rotate-key", tenantId)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId", is((int) tenantId)))
                .andExpect(jsonPath("$.apiKey", not(emptyOrNullString())))
                .andReturn().getResponse().getContentAsString();
        String newApiKey = objectMapper.readTree(body).get("apiKey").asText();

        assertThat(newApiKey).isNotEqualTo(tenantApiKey);

        // Old key is dead immediately — no overlap window.
        mockMvc.perform(get("/api/v1/tenants/me")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, tenantApiKey))
                .andExpect(status().isUnauthorized());

        // New key authenticates as the same tenant.
        mockMvc.perform(get("/api/v1/tenants/me")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, newApiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId", is((int) tenantId)));
    }

    @Test
    void updateCustomDomain_setsDomain_andBrandsSubsequentlyCreatedLinks() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tenants/{id}/domain", tenantId)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customDomain\": \"go.acme-test.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customDomain", is("go.acme-test.com")));

        // Confirm it persisted and shows up in the detail view too.
        mockMvc.perform(get("/api/v1/admin/tenants/{id}", tenantId)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY))
                .andExpect(jsonPath("$.customDomain", is("go.acme-test.com")));

        // A link created after the domain is set is branded with it.
        ShortenUrlRequest urlRequest = new ShortenUrlRequest("https://www.schwab.com/branded", null, null);
        mockMvc.perform(post("/api/v1/urls")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, tenantApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(urlRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortUrl", startsWith("https://go.acme-test.com/")));
    }

    @Test
    void updateCustomDomain_clearingToNull_revertsToDefaultHost() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tenants/{id}/domain", tenantId)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customDomain\": \"go.acme-test.com\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/tenants/{id}/domain", tenantId)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customDomain\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customDomain").doesNotExist());
    }

    @Test
    void updateCustomDomain_alreadyClaimedByAnotherTenant_returns409() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tenants/{id}/domain", tenantId)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customDomain\": \"go.taken.com\"}"))
                .andExpect(status().isOk());

        // A second tenant, registered via this test's own flow.
        TenantRegistrationRequest secondRequest = new TenantRegistrationRequest("second-" + System.nanoTime());
        String secondBody = mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long secondTenantId = objectMapper.readTree(secondBody).get("tenantId").asLong();

        mockMvc.perform(patch("/api/v1/admin/tenants/{id}/domain", secondTenantId)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customDomain\": \"go.taken.com\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void updateCustomDomain_invalidFormat_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tenants/{id}/domain", tenantId)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customDomain\": \"https://go.acme.com/path\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateCustomDomain_unknownTenant_returns404() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tenants/{id}/domain", 999999)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customDomain\": \"go.acme.com\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rotateApiKey_unknownTenant_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tenants/{id}/rotate-key", 999999)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY))
                .andExpect(status().isNotFound());
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
    void listTenantUrls_explicitSortIsHonored_notOverriddenToCreatedAtDesc() throws Exception {
        // Regression test: this endpoint used to unconditionally override
        // any caller-supplied ?sort=, making it inoperable end-to-end, not
        // just at the service-layer unit-test level.
        mockMvc.perform(post("/api/v1/urls")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, tenantApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ShortenUrlRequest("https://www.schwab.com/z", "z-sort-link", null))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/urls")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, tenantApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ShortenUrlRequest("https://www.schwab.com/a", "a-sort-link", null))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/admin/tenants/{id}/urls?sort=shortCode,asc", tenantId)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].shortCode", is("a-sort-link")))
                .andExpect(jsonPath("$.content[1].shortCode", is("z-sort-link")));
    }

    @Test
    void listTenantUrls_invalidSortProperty_returns400NotA500() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenants/{id}/urls?sort=%5B%22string%22%5D", tenantId)
                        .header(AdminAuthenticationFilter.ADMIN_KEY_HEADER, ADMIN_KEY))
                .andExpect(status().isBadRequest());
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
