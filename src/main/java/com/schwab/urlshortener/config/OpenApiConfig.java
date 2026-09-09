package com.schwab.urlshortener.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documents both the X-API-Key (tenant) and X-Admin-Key (admin) headers as
 * separate security schemes so "Try it out" in Swagger UI
 * (http://localhost:8080/swagger-ui.html) actually works end to end for
 * both identities: register a tenant via POST /api/v1/tenants, click
 * "Authorize", paste the returned key into ApiKeyAuth for tenant routes,
 * or into AdminKeyAuth for /api/v1/admin/** routes.
 *
 * Only ApiKeyAuth is applied globally (matching the fact that most of the
 * API is tenant-scoped); AdminController overrides this per-class with
 * @SecurityRequirement("AdminKeyAuth") so its endpoints document the
 * correct header instead of inheriting the tenant one — previously every
 * admin endpoint's "Try it out" sent X-API-Key instead of X-Admin-Key,
 * since that was the only scheme Swagger knew about.
 */
@Configuration
public class OpenApiConfig {

    public static final String API_KEY_SCHEME = "ApiKeyAuth";
    public static final String ADMIN_KEY_SCHEME = "AdminKeyAuth";

    @Bean
    public OpenAPI urlShortenerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("URL Shortener API")
                        .description("Multi-tenant URL shortener — AI-Assisted Engineering Assignment (Charles Schwab)")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key"))
                        .addSecuritySchemes(ADMIN_KEY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Admin-Key")))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
