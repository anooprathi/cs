package com.schwab.urlshortener.workflow.agents.recipes;

import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.workflow.artifact.InterfaceContract;
import com.schwab.urlshortener.workflow.workspace.PatchProposal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Brownfield demo recipe: refuse to reactivate a link whose expiry is in the past.
 *
 * Today {@code reactivate} flips {@code active=true} unconditionally, so an
 * expired link "comes back" and its very next redirect returns 410 Gone. This
 * recipe adds a 409 Conflict guard in the service. Extending the expiry
 * through the existing PATCH endpoint and then reactivating still works,
 * and unexpired links reactivate exactly as before. No API shape or schema
 * change, only a new error status on one endpoint.
 */
public class ExpiredReactivationRecipe implements CodeRecipe {

    static final String SERVICE = "src/main/java/com/schwab/urlshortener/service/impl/UrlShortenerServiceImpl.java";
    static final String EXCEPTION = "src/main/java/com/schwab/urlshortener/exception/ExpiredLinkReactivationException.java";
    static final String TEST = "src/test/java/com/schwab/urlshortener/integration/ExpiredLinkReactivationIntegrationTest.java";

    @Override
    public String id() {
        return "expired-reactivation";
    }

    @Override
    public String description() {
        return "Reactivating a link whose expiry has passed returns 409 Conflict";
    }

    @Override
    public boolean matches(RequirementSpec spec) {
        String text = CodeRecipe.statements(spec);
        return text.contains("reactivat") && text.contains("expir") && text.contains("409");
    }

    @Override
    public PatchProposal apply(Path root) {
        SourceEdits edits = new SourceEdits(root);

        edits.replaceOnce(SERVICE, "import com.schwab.urlshortener.exception.DuplicateAliasException;\n",
                "import com.schwab.urlshortener.exception.DuplicateAliasException;\n"
                        + "import com.schwab.urlshortener.exception.ExpiredLinkReactivationException;\n");
        edits.replaceOnce(SERVICE, """
                        UrlMapping mapping = repository.findByShortCodeAndTenantId(shortCode, tenantId)
                                .orElseThrow(() -> UrlNotFoundException.forTenant(shortCode));
                        mapping.setActive(true);
                """, """
                        UrlMapping mapping = repository.findByShortCodeAndTenantId(shortCode, tenantId)
                                .orElseThrow(() -> UrlNotFoundException.forTenant(shortCode));
                        // Reactivating an expired link would "succeed" and then 410 on its very next
                        // redirect. Refuse instead; the tenant extends expiresAt (PATCH) first.
                        if (mapping.isExpired()) {
                            throw new ExpiredLinkReactivationException(shortCode);
                        }
                        mapping.setActive(true);
                """);

        edits.create(EXCEPTION, """
                package com.schwab.urlshortener.exception;

                import org.springframework.http.HttpStatus;

                /** Thrown when a tenant tries to reactivate a link whose expiry has already passed. */
                public class ExpiredLinkReactivationException extends ApiException {
                    public ExpiredLinkReactivationException(String shortCode) {
                        super(HttpStatus.CONFLICT, "Short URL has expired and cannot be reactivated: " + shortCode
                                + ". Extend expiresAt first, then reactivate.");
                    }
                }
                """);
        edits.create(TEST, TEST_SOURCE);

        Map<String, String> rationale = Map.of(
                SERVICE, "AC: reactivation of an expired link refused with 409; unexpired links unchanged",
                EXCEPTION, "AC: 409 Conflict through the existing ApiException handler",
                TEST, "Integration test: expired -> 409, extend then reactivate -> 200, unexpired -> 200");
        List<PatchProposal.FileChange> files = new ArrayList<>();
        edits.edits().forEach((path, content) -> files.add(new PatchProposal.FileChange(path, content, rationale.get(path))));

        return new PatchProposal(
                "Refuse reactivation of expired links with 409 Conflict; extend-then-reactivate still works",
                files,
                List.of(new InterfaceContract.ApiChange("PATCH", "/api/v1/urls/{shortCode}/reactivate", "RESPONSE_STATUS_ADDED",
                        "409 Conflict when the link's expiresAt is in the past (previously 200 with a link that then 410s)")),
                List.of());
    }

    private static final String TEST_SOURCE = """
            package com.schwab.urlshortener.integration;

            import com.fasterxml.jackson.databind.ObjectMapper;
            import com.schwab.urlshortener.entity.UrlMapping;
            import com.schwab.urlshortener.repository.UrlMappingRepository;
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

            import java.time.Instant;
            import java.time.temporal.ChronoUnit;

            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

            /**
             * Reactivation of expired links is refused with 409; extending the expiry first
             * still works; unexpired links reactivate as before.
             * Generated by the workflow's deterministic demo recipe 'expired-reactivation'.
             * Requests are sent as raw JSON so the test doesn't depend on DTO constructor arity.
             */
            @SpringBootTest
            @AutoConfigureMockMvc
            @ActiveProfiles("test")
            class ExpiredLinkReactivationIntegrationTest {

                @Autowired
                private MockMvc mockMvc;

                @Autowired
                private ObjectMapper objectMapper;

                @Autowired
                private UrlMappingRepository repository;

                private String apiKey;

                @BeforeEach
                void setUp() throws Exception {
                    String body = mockMvc.perform(post("/api/v1/tenants")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(new TenantRegistrationRequest("reactivation-" + System.nanoTime()))))
                            .andExpect(status().isCreated())
                            .andReturn().getResponse().getContentAsString();
                    apiKey = objectMapper.readTree(body).get("apiKey").asText();
                }

                /** Creates a link, optionally moves its expiry into the past, then deactivates it. */
                private String deactivatedLink(boolean expired) throws Exception {
                    String body = mockMvc.perform(post("/api/v1/urls")
                                    .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"originalUrl\\": \\"https://www.schwab.com/reactivation\\"}"))
                            .andExpect(status().isCreated())
                            .andReturn().getResponse().getContentAsString();
                    String code = objectMapper.readTree(body).get("shortCode").asText();
                    if (expired) {
                        UrlMapping mapping = repository.findByShortCodeAndActiveTrue(code).orElseThrow();
                        mapping.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
                        repository.save(mapping);
                    }
                    mockMvc.perform(delete("/api/v1/urls/{shortCode}", code).header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                            .andExpect(status().is2xxSuccessful());
                    return code;
                }

                @Test
                void reactivatingAnExpiredLinkReturns409() throws Exception {
                    String code = deactivatedLink(true);
                    mockMvc.perform(patch("/api/v1/urls/{shortCode}/reactivate", code).header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                            .andExpect(status().isConflict());
                    mockMvc.perform(get("/{shortCode}", code)).andExpect(status().isNotFound());
                }

                @Test
                void extendingTheExpiryThenReactivatingWorks() throws Exception {
                    String code = deactivatedLink(true);
                    String future = Instant.now().plus(1, ChronoUnit.DAYS).toString();
                    mockMvc.perform(patch("/api/v1/urls/{shortCode}", code)
                                    .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"expiresAt\\": \\"" + future + "\\"}"))
                            .andExpect(status().isOk());
                    mockMvc.perform(patch("/api/v1/urls/{shortCode}/reactivate", code).header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                            .andExpect(status().isOk());
                    mockMvc.perform(get("/{shortCode}", code)).andExpect(status().isFound());
                }

                @Test
                void unexpiredLinkStillReactivates() throws Exception {
                    String code = deactivatedLink(false);
                    mockMvc.perform(patch("/api/v1/urls/{shortCode}/reactivate", code).header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                            .andExpect(status().isOk());
                    mockMvc.perform(get("/{shortCode}", code)).andExpect(status().isFound());
                }
            }
            """;
}
