package com.schwab.urlshortener.workflow.agents.recipes;

import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.workflow.artifact.InterfaceContract;
import com.schwab.urlshortener.workflow.workspace.PatchProposal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Greenfield demo recipe: optional per-link click limits.
 *
 * Here is what changes and why each edit is shaped the way it is:
 *  - the entity gains a nullable {@code maxClicks} column. Null means
 *    unlimited, so existing rows keep their behavior.
 *  - the redirect's atomic increment query gains
 *    {@code AND (maxClicks IS NULL OR clickCount < maxClicks)}. The limit check
 *    and the increment are therefore one UPDATE, and two concurrent last clicks
 *    can't both get through (no read-then-write race).
 *  - an increment that updated 0 rows on a limited link means the limit was
 *    reached, and the redirect returns 410 Gone.
 *  - the stats DTO gains {@code remainingClicks}: null when unlimited, and
 *    never negative.
 *  - all existing test call sites of the three changed record constructors get
 *    {@code null} appended for the new component, by codemod, not by hand.
 */
public class ClickLimitRecipe implements CodeRecipe {

    static final String ENTITY = "src/main/java/com/schwab/urlshortener/entity/UrlMapping.java";
    static final String REQUEST = "src/main/java/com/schwab/urlshortener/dto/ShortenUrlRequest.java";
    static final String STATS = "src/main/java/com/schwab/urlshortener/dto/UrlStatsResponse.java";
    static final String MAPPER = "src/main/java/com/schwab/urlshortener/service/impl/UrlMappingMapper.java";
    static final String REPOSITORY = "src/main/java/com/schwab/urlshortener/repository/UrlMappingRepository.java";
    static final String LOOKUP = "src/main/java/com/schwab/urlshortener/service/impl/CachedShortCodeLookup.java";
    static final String SERVICE = "src/main/java/com/schwab/urlshortener/service/impl/UrlShortenerServiceImpl.java";
    static final String EXCEPTION = "src/main/java/com/schwab/urlshortener/exception/ClickLimitReachedException.java";
    static final String TEST = "src/test/java/com/schwab/urlshortener/integration/ClickLimitIntegrationTest.java";

    @Override
    public String id() {
        return "click-limit";
    }

    @Override
    public String description() {
        return "Optional maxClicks on link creation; 410 Gone once reached; remainingClicks on stats";
    }

    @Override
    public boolean matches(RequirementSpec spec) {
        String text = CodeRecipe.statements(spec);
        return text.contains("click limit") || text.contains("maximum click") || text.contains("max clicks");
    }

    @Override
    public PatchProposal apply(Path root) {
        SourceEdits edits = new SourceEdits(root);

        edits.insertAfter(ENTITY, "    private Instant lastAccessedAt;\n", """

                    /** Optional cap on successful redirects; null means unlimited. Enforced atomically in
                     *  UrlMappingRepository.incrementClickCount, not by a read-then-compare. */
                    private Integer maxClicks;
                """);

        edits.replaceOnce(REQUEST, "import jakarta.validation.constraints.Future;\n",
                "import jakarta.validation.constraints.Future;\nimport jakarta.validation.constraints.Min;\n");
        edits.replaceOnce(REQUEST, " * customAlias and expiresAt are optional.", " * customAlias, expiresAt and maxClicks are optional.");
        edits.replaceOnce(REQUEST, "        Instant expiresAt\n) {", """
                        Instant expiresAt,

                        @Min(value = 1, message = "maxClicks must be at least 1")
                        Integer maxClicks
                ) {""");

        edits.replaceOnce(STATS, "        boolean active\n) {", """
                        boolean active,
                        /** Redirects left before the click limit is reached; null when the link has no limit. */
                        Long remainingClicks
                ) {""");

        edits.replaceOnce(MAPPER, "                mapping.isActive()\n        );", """
                                mapping.isActive(),
                                mapping.getMaxClicks() == null ? null : Math.max(0L, mapping.getMaxClicks() - mapping.getClickCount())
                        );""");

        edits.replaceOnce(REPOSITORY, "            \"WHERE u.shortCode = :shortCode\")\n    int incrementClickCount(",
                "            \"WHERE u.shortCode = :shortCode AND (u.maxClicks IS NULL OR u.clickCount < u.maxClicks)\")\n"
                        + "    int incrementClickCount(");

        edits.replaceOnce(LOOKUP, "new RedirectTarget(m.getTenantId(), m.getOriginalUrl(), m.getExpiresAt())",
                "new RedirectTarget(m.getTenantId(), m.getOriginalUrl(), m.getExpiresAt(), m.getMaxClicks())");
        edits.replaceOnce(LOOKUP, "public record RedirectTarget(Long tenantId, String originalUrl, Instant expiresAt) {",
                "public record RedirectTarget(Long tenantId, String originalUrl, Instant expiresAt, Integer maxClicks) {");

        edits.replaceOnce(SERVICE, "import com.schwab.urlshortener.exception.DuplicateAliasException;\n",
                "import com.schwab.urlshortener.exception.ClickLimitReachedException;\n"
                        + "import com.schwab.urlshortener.exception.DuplicateAliasException;\n");
        edits.replaceOnce(SERVICE, "                .expiresAt(request.expiresAt())\n",
                "                .expiresAt(request.expiresAt())\n                .maxClicks(request.maxClicks())\n");
        edits.replaceOnce(SERVICE, "        repository.incrementClickCount(shortCode, Instant.now());\n", """
                        // The limit check and the increment are one atomic UPDATE (see the repository query), so
                        // 0 rows updated on a limited link means the limit was already reached: no redirect.
                        int counted = repository.incrementClickCount(shortCode, Instant.now());
                        if (counted == 0 && target.maxClicks() != null) {
                            log.info("Click limit reached for short code: {}", shortCode);
                            throw new ClickLimitReachedException(shortCode);
                        }
                """);

        edits.create(EXCEPTION, """
                package com.schwab.urlshortener.exception;

                import org.springframework.http.HttpStatus;

                /** Thrown when a short code's optional click limit has been reached. */
                public class ClickLimitReachedException extends ApiException {
                    public ClickLimitReachedException(String shortCode) {
                        super(HttpStatus.GONE, "Short URL has reached its click limit: " + shortCode);
                    }
                }
                """);
        edits.create(TEST, TEST_SOURCE);

        int sites = edits.appendConstructorArgument("src/test/java", "ShortenUrlRequest", "null", List.of(TEST))
                + edits.appendConstructorArgument("src/test/java", "UrlStatsResponse", "null", List.of(TEST))
                + edits.appendConstructorArgument("src/test/java", "CachedShortCodeLookup.RedirectTarget", "null", List.of(TEST));

        Map<String, String> rationale = Map.of(
                ENTITY, "AC: optional click limit stored per link (nullable column, null = unlimited)",
                REQUEST, "AC: create accepts maxClicks; below 1 rejected with 400 by Bean Validation",
                STATS, "AC: stats show remaining clicks",
                MAPPER, "AC: remainingClicks = max(0, maxClicks - clickCount), null when unlimited",
                REPOSITORY, "AC: limit enforced atomically inside the click-count UPDATE (race-free)",
                LOOKUP, "Cached redirect projection carries maxClicks so the service can tell 'limit reached' from a no-op",
                SERVICE, "AC: redirect returns 410 Gone once the limit is reached; create persists maxClicks",
                EXCEPTION, "AC: 410 Gone through the existing ApiException handler",
                TEST, "Integration test for every acceptance criterion of this change");
        List<PatchProposal.FileChange> files = new ArrayList<>();
        edits.edits().forEach((path, content) -> files.add(new PatchProposal.FileChange(path, content,
                rationale.getOrDefault(path, "Codemod: append null for the new record component at existing call sites"))));

        return new PatchProposal(
                "Add optional per-link click limits (maxClicks): 410 Gone once reached, remainingClicks on stats; "
                        + sites + " existing test call sites updated by codemod",
                files,
                List.of(new InterfaceContract.ApiChange("POST", "/api/v1/urls", "REQUEST_FIELD_ADDED",
                                "maxClicks: integer, optional, minimum 1 (400 Bad Request below 1); omitted = unlimited"),
                        new InterfaceContract.ApiChange("GET", "/{shortCode}", "RESPONSE_STATUS_ADDED",
                                "410 Gone once the link's click limit has been reached (same error envelope as expiry)"),
                        new InterfaceContract.ApiChange("GET", "/api/v1/urls/{shortCode}/stats", "RESPONSE_FIELD_ADDED",
                                "remainingClicks: integer or null (null = no limit); also on list, PATCH and reactivate responses")),
                List.of(new InterfaceContract.SchemaChange("url_mapping", "max_clicks", "COLUMN_ADDED",
                        "ALTER TABLE url_mapping ADD COLUMN max_clicks INTEGER NULL")));
    }

    private static final String TEST_SOURCE = """
            package com.schwab.urlshortener.integration;

            import com.fasterxml.jackson.databind.ObjectMapper;
            import com.schwab.urlshortener.dto.ShortenUrlRequest;
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

            import static org.hamcrest.Matchers.is;
            import static org.hamcrest.Matchers.nullValue;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

            /**
             * Click-limited short links, end to end: create with maxClicks, redirect until the
             * limit, 410 Gone after it, remainingClicks on stats, and validation of the limit.
             * Generated by the workflow's deterministic demo recipe 'click-limit'.
             */
            @SpringBootTest
            @AutoConfigureMockMvc
            @ActiveProfiles("test")
            class ClickLimitIntegrationTest {

                @Autowired
                private MockMvc mockMvc;

                @Autowired
                private ObjectMapper objectMapper;

                private String apiKey;

                @BeforeEach
                void setUp() throws Exception {
                    String body = mockMvc.perform(post("/api/v1/tenants")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(new TenantRegistrationRequest("click-limit-" + System.nanoTime()))))
                            .andExpect(status().isCreated())
                            .andReturn().getResponse().getContentAsString();
                    apiKey = objectMapper.readTree(body).get("apiKey").asText();
                }

                private String create(Integer maxClicks) throws Exception {
                    String body = mockMvc.perform(post("/api/v1/urls")
                                    .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(
                                            new ShortenUrlRequest("https://www.schwab.com/limited", null, null, maxClicks))))
                            .andExpect(status().isCreated())
                            .andReturn().getResponse().getContentAsString();
                    return objectMapper.readTree(body).get("shortCode").asText();
                }

                @Test
                void redirectsUntilTheLimitThenReturns410() throws Exception {
                    String code = create(2);

                    mockMvc.perform(get("/{shortCode}", code)).andExpect(status().isFound());
                    mockMvc.perform(get("/{shortCode}", code)).andExpect(status().isFound());
                    mockMvc.perform(get("/{shortCode}", code)).andExpect(status().isGone());

                    mockMvc.perform(get("/api/v1/urls/{shortCode}/stats", code).header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.clickCount", is(2)))
                            .andExpect(jsonPath("$.remainingClicks", is(0)));
                }

                @Test
                void statsShowRemainingClicks() throws Exception {
                    String code = create(5);
                    mockMvc.perform(get("/{shortCode}", code)).andExpect(status().isFound());

                    mockMvc.perform(get("/api/v1/urls/{shortCode}/stats", code).header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                            .andExpect(jsonPath("$.remainingClicks", is(4)));
                }

                @Test
                void linkWithoutALimitIsUnlimited() throws Exception {
                    String code = create(null);
                    for (int i = 0; i < 3; i++) {
                        mockMvc.perform(get("/{shortCode}", code)).andExpect(status().isFound());
                    }
                    mockMvc.perform(get("/api/v1/urls/{shortCode}/stats", code).header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey))
                            .andExpect(jsonPath("$.remainingClicks", nullValue()));
                }

                @Test
                void clickLimitBelowOneIsRejected() throws Exception {
                    mockMvc.perform(post("/api/v1/urls")
                                    .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(
                                            new ShortenUrlRequest("https://www.schwab.com/limited", null, null, 0))))
                            .andExpect(status().isBadRequest());
                }
            }
            """;
}
