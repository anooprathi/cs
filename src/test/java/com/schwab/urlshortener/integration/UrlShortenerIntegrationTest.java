package com.schwab.urlshortener.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.billing.InvoiceRepository;
import com.schwab.urlshortener.billing.TenantUsageRecordRepository;
import com.schwab.urlshortener.dto.ShortenUrlRequest;
import com.schwab.urlshortener.entity.UrlMapping;
import com.schwab.urlshortener.repository.UrlMappingRepository;
import com.schwab.urlshortener.security.ApiKeyAuthenticationFilter;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end tests exercising the full stack: Spring Security (API-key
 * auth) -> controller -> service -> Spring Data JPA -> H2, including
 * validation, tenancy isolation, and the global exception handler.
 *
 * Rate limiting is exercised separately and directly against
 * {@code TenantRateLimiterService} (see TenantRateLimiterServiceTest) —
 * the "test" profile intentionally sets very high limits so ordinary
 * functional tests here never trip the limiter incidentally.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UrlShortenerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UrlMappingRepository repository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TenantUsageRecordRepository tenantUsageRecordRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    private String apiKey;

    @BeforeEach
    void setUp() throws Exception {
        // Deleted in FK-safe order: usage records and invoices reference
        // tenantId (no formal FK constraint, but logically dependent),
        // url mappings reference tenantId too — clear the "leaf" tables
        // before the tenants they point at. Previously only url mappings
        // and tenants were cleared here; usage/invoice rows from earlier
        // test methods within the same test class's shared H2 instance
        // quietly accumulated across the run (harmless today since tenant
        // ids never repeat, but untidy and worth cleaning up properly).
        invoiceRepository.deleteAll();
        tenantUsageRecordRepository.deleteAll();
        repository.deleteAll();
        tenantRepository.deleteAll();
        apiKey = registerTenant("acme-" + System.nanoTime());
    }

    private String registerTenant(String name) throws Exception {
        // No plan parameter: registration is always STANDARD now (see
        // TenantRegistrationRequest's Javadoc) -- every call site here
        // already only ever passed STANDARD, so this simplification loses
        // no test coverage.
        TenantRegistrationRequest request = new TenantRegistrationRequest(name);
        String body = mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey", not(emptyString())))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("apiKey").asText();
    }

    @Test
    void tenantRegistration_thenMe_returnsProfile() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/me").header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan", is("STANDARD")));
    }

    @Test
    void fullLifecycle_create_redirect_stats_deactivate() throws Exception {
        ShortenUrlRequest request = new ShortenUrlRequest("https://www.schwab.com/some/long/path", null, null);

        String body = mockMvc.perform(post("/api/v1/urls")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode", not(emptyString())))
                .andExpect(jsonPath("$.originalUrl", is(request.originalUrl())))
                .andReturn().getResponse().getContentAsString();

        String shortCode = objectMapper.readTree(body).get("shortCode").asText();

        // Redirect is public — no API key needed — and follows to the original URL.
        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", request.originalUrl()));

        mockMvc.perform(get("/api/v1/urls/{shortCode}/stats", shortCode)
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clickCount", is(1)));

        // Second hit increments again
        mockMvc.perform(get("/{shortCode}", shortCode)).andExpect(status().isFound());
        mockMvc.perform(get("/api/v1/urls/{shortCode}/stats", shortCode)
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(jsonPath("$.clickCount", is(2)));

        // Deactivate then confirm 404 on further access
        mockMvc.perform(delete("/api/v1/urls/{shortCode}", shortCode)
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    void createShortUrl_withCustomAlias_isHonored() throws Exception {
        ShortenUrlRequest request = new ShortenUrlRequest("https://www.schwab.com/research", "schwab-research", null);

        mockMvc.perform(post("/api/v1/urls")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode", is("schwab-research")));
    }

    @Test
    void createShortUrl_withDuplicateAlias_returns409() throws Exception {
        ShortenUrlRequest first = new ShortenUrlRequest("https://www.schwab.com/a", "dupe-alias", null);
        ShortenUrlRequest second = new ShortenUrlRequest("https://www.schwab.com/b", "dupe-alias", null);

        mockMvc.perform(post("/api/v1/urls")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/urls")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.message", containsString("dupe-alias")));
    }

    @Test
    void createShortUrl_withInvalidUrl_returns400WithFieldDetails() throws Exception {
        String invalidJson = """
                {"originalUrl": "not-a-url"}
                """;

        mockMvc.perform(post("/api/v1/urls")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.details", hasItem(containsString("originalUrl"))));
    }

    @Test
    void createShortUrl_withBlankBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createShortUrl_withMalformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void redirect_unknownShortCode_returns404() throws Exception {
        mockMvc.perform(get("/{shortCode}", "doesNotExist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("Not Found")));
    }

    @Test
    void rootPath_matchesNoRoute_isRejectedCleanly_notAnUnhandled500() throws Exception {
        // GET / matches no controller mapping (RedirectController's short-code
        // pattern requires 4-20 characters, so an empty path doesn't qualify).
        //
        // This assertion changed from 404 to 401 when SecurityConfig's
        // catchall changed from permitAll() to denyAll() (a production
        // review correctly flagged the old permitAll fallback as a real
        // security gap — see SecurityConfig's Javadoc). With denyAll(),
        // Spring Security's AuthorizationFilter now rejects an unmatched
        // route BEFORE the request ever reaches DispatcherServlet, so the
        // NoResourceFoundException -> 404 path (still correctly handled in
        // GlobalExceptionHandler, and still exercised by
        // redirect_unknownShortCode_returns404 above for an unmatched
        // SHORT CODE specifically) never gets the chance to fire for a
        // route this app has no matcher for at all. The status is 401, not
        // 403: Spring Security's ExceptionTranslationFilter routes an
        // AccessDeniedException to the AuthenticationEntryPoint (401)
        // rather than the AccessDeniedHandler (403) whenever the current
        // principal is anonymous — this request carries no credential at
        // all, so it never gets far enough to be "authenticated but
        // forbidden". A request presenting a real-but-insufficient
        // credential against a denyAll/hasRole route does get 403 (see
        // AdminIntegrationTest's wrong-key cases). The actual thing this
        // test protects against — a genuinely unmatched route surfacing as
        // an unhandled 500 — is still what's being verified; only the
        // specific status code differs from the original (wrong)
        // assumption, and 401 is still the conservative, intentional
        // outcome of the security fix, not a regression.
        mockMvc.perform(get("/"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)));
    }

    @Test
    void unmappedSubPathWithinAuthenticatedNamespace_stillReturns404NotUnhandled500() throws Exception {
        // Preserves the actual regression coverage the test above lost when
        // its own status code changed: an AUTHENTICATED request that passes
        // SecurityConfig's ".../api/v1/**".authenticated() rule but matches
        // no actual controller mapping still needs to hit
        // NoResourceFoundException -> GlobalExceptionHandler -> a clean 404,
        // not fall through to the generic Exception.class handler as an
        // unhandled 500 (the original bug this whole handler exists to fix).
        mockMvc.perform(get("/api/v1/this-endpoint-does-not-exist")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    void redirect_expiredShortCode_returns410() throws Exception {
        String shortCode = createMappingDirectly("expired1", "https://www.schwab.com/old",
                Instant.now().minus(1, ChronoUnit.HOURS));

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isGone());
    }

    @Test
    void getStats_unknownShortCode_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/urls/{shortCode}/stats", "nope1234")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(status().isNotFound());
    }

    @Test
    void deactivate_unknownShortCode_returns404() throws Exception {
        mockMvc.perform(delete("/api/v1/urls/{shortCode}", "nope1234")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(status().isNotFound());
    }

    // ---------- Security & multi-tenancy ----------

    @Test
    void managementApi_withoutApiKey_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/urls/anything/stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)));
    }

    @Test
    void managementApi_withInvalidApiKey_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/urls/anything/stats")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, "usk_not-a-real-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantCannotReadAnotherTenantsLinkStats_returns404NotLeakingExistence() throws Exception {
        // Tenant A creates a link.
        ShortenUrlRequest request = new ShortenUrlRequest("https://www.schwab.com/private", "tenant-a-link", null);
        mockMvc.perform(post("/api/v1/urls")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Tenant B, a completely different tenant, tries to read tenant A's stats.
        String otherApiKey = registerTenant("other-tenant-" + System.nanoTime());
        mockMvc.perform(get("/api/v1/urls/{shortCode}/stats", "tenant-a-link")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, otherApiKey))
                .andExpect(status().isNotFound());

        // The redirect itself remains public regardless of tenant.
        mockMvc.perform(get("/{shortCode}", "tenant-a-link"))
                .andExpect(status().isFound());
    }

    @Test
    void tenantCannotDeactivateAnotherTenantsLink() throws Exception {
        ShortenUrlRequest request = new ShortenUrlRequest("https://www.schwab.com/other", "tenant-a-link-2", null);
        mockMvc.perform(post("/api/v1/urls")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        String otherApiKey = registerTenant("other-tenant-b-" + System.nanoTime());
        mockMvc.perform(delete("/api/v1/urls/{shortCode}", "tenant-a-link-2")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, otherApiKey))
                .andExpect(status().isNotFound());

        // Still active for the real owner.
        mockMvc.perform(get("/api/v1/urls/{shortCode}/stats", "tenant-a-link-2")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(true)));
    }

    @Test
    void rateLimitHeaders_presentOnAuthenticatedManagementRequests() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/me").header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-RateLimit-Limit"))
                .andExpect(header().exists("X-RateLimit-Remaining"));
    }

    @Test
    void billingStatement_reflectsCreateAndRedirectUsage() throws Exception {
        ShortenUrlRequest request = new ShortenUrlRequest("https://www.schwab.com/billing-check", "billing-check-link", null);

        mockMvc.perform(post("/api/v1/urls")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/{shortCode}", "billing-check-link")).andExpect(status().isFound());
        mockMvc.perform(get("/{shortCode}", "billing-check-link")).andExpect(status().isFound());

        mockMvc.perform(get("/api/v1/tenants/me/billing").header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiCallsUsed", is(1)))
                .andExpect(jsonPath("$.redirectsUsed", is(2)))
                .andExpect(jsonPath("$.plan", is("STANDARD")));
    }

    @Test
    void billingStatement_withoutApiKey_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/me/billing"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- Invoices ----------

    @Test
    void generateInvoice_forCurrentPeriod_returnsSnapshotMatchingUsage() throws Exception {
        ShortenUrlRequest request = new ShortenUrlRequest("https://www.schwab.com/invoice-check", "invoice-check-link", null);
        mockMvc.perform(post("/api/v1/urls")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/{shortCode}", "invoice-check-link")).andExpect(status().isFound());

        mockMvc.perform(post("/api/v1/tenants/me/invoices")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invoiceNumber", not(emptyString())))
                .andExpect(jsonPath("$.apiCallsUsed", is(1)))
                .andExpect(jsonPath("$.redirectsUsed", is(1)))
                .andExpect(jsonPath("$.status", is("ISSUED")));
    }

    @Test
    void generateInvoice_calledTwiceForSamePeriod_returns409OnSecondCall() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/me/invoices")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/tenants/me/invoices")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)));
    }

    @Test
    void generateInvoice_futurePeriod_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/me/invoices")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"billingPeriod\": \"2099-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listInvoices_thenGetById_returnsGeneratedInvoice() throws Exception {
        String body = mockMvc.perform(post("/api/v1/tenants/me/invoices")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long invoiceId = objectMapper.readTree(body).get("invoiceId").asLong();

        mockMvc.perform(get("/api/v1/tenants/me/invoices").header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(get("/api/v1/tenants/me/invoices/{id}", invoiceId)
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceId", is((int) invoiceId)));
    }

    @Test
    void getInvoice_belongingToAnotherTenant_returns404() throws Exception {
        String body = mockMvc.perform(post("/api/v1/tenants/me/invoices")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long invoiceId = objectMapper.readTree(body).get("invoiceId").asLong();

        String otherApiKey = registerTenant("invoice-other-tenant-" + System.nanoTime());
        mockMvc.perform(get("/api/v1/tenants/me/invoices/{id}", invoiceId)
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, otherApiKey))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadInvoicePdf_returnsPdfContentType() throws Exception {
        String body = mockMvc.perform(post("/api/v1/tenants/me/invoices")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long invoiceId = objectMapper.readTree(body).get("invoiceId").asLong();

        byte[] pdfBytes = mockMvc.perform(get("/api/v1/tenants/me/invoices/{id}/pdf", invoiceId)
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString(".pdf")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(pdfBytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    void invoiceEndpoints_withoutApiKey_return401() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/me/invoices")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/tenants/me/invoices")).andExpect(status().isUnauthorized());
    }

    private String createMappingDirectly(String shortCode, String originalUrl, Instant expiresAt) {
        Long tenantId = tenantRepository.findAll().get(0).getId();
        UrlMapping mapping = UrlMapping.builder()
                .tenantId(tenantId)
                .shortCode(shortCode)
                .originalUrl(originalUrl)
                .active(true)
                .expiresAt(expiresAt)
                .clickCount(0)
                .build();
        repository.save(mapping);
        return shortCode;
    }
}
