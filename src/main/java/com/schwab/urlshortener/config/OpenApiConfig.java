package com.schwab.urlshortener.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documents the X-API-Key header as a security scheme so "Try it out" in
 * Swagger UI (http://localhost:8080/swagger-ui.html) actually works end
 * to end: register a tenant via POST /api/v1/tenants, click "Authorize",
 * paste the returned key, and call any endpoint from the browser.
 */
@Configuration
public class OpenApiConfig {

    private static final String API_KEY_SCHEME = "ApiKeyAuth";

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
                                .name("X-API-Key")))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
