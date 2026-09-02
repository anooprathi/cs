package com.schwab.urlshortener.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.Writer;

/**
 * A small, self-contained Jackson ObjectMapper dedicated to serializing
 * {@link ErrorResponse} from servlet filters (ApiKeyAuthenticationFilter,
 * RateLimitFilter, SecurityConfig's exception handlers) that run as part
 * of the security filter chain — before/outside normal Spring MVC
 * message-converter dispatch — and therefore can't rely on the app's
 * Spring-managed, {@code @Autowired}-injected JSON mapper bean the way a
 * regular {@code @RestController} response can.
 *
 * Deliberately NOT the app's Spring-managed ObjectMapper bean: these are
 * low-level, security-critical classes that only ever need to serialize
 * one small, fixed DTO, so they own a private, minimal mapper instead of
 * depending on bean resolution for it — one less thing that could ever go
 * wrong in the security filter chain because of an unrelated change to
 * the app's JSON configuration elsewhere.
 */
public final class ErrorResponseWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ErrorResponseWriter() {
    }

    public static void write(Writer writer, ErrorResponse body) throws IOException {
        MAPPER.writeValue(writer, body);
    }
}
