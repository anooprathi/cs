package com.schwab.urlshortener.service.impl;

import com.schwab.urlshortener.dto.ShortenUrlResponse;
import com.schwab.urlshortener.dto.UrlStatsResponse;
import com.schwab.urlshortener.entity.UrlMapping;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UrlMappingMapperTest {

    private final UrlMappingMapper mapper = new UrlMappingMapper();

    @Test
    void toShortenResponse_buildsFullShortUrlFromBaseUrlAndCode() {
        UrlMapping mapping = UrlMapping.builder()
                .shortCode("abc1234").originalUrl("https://example.com")
                .createdAt(Instant.parse("2026-08-31T12:00:00Z")).build();

        ShortenUrlResponse response = mapper.toShortenResponse(mapping, "http://localhost:8080");

        assertThat(response.shortCode()).isEqualTo("abc1234");
        assertThat(response.shortUrl()).isEqualTo("http://localhost:8080/abc1234");
        assertThat(response.originalUrl()).isEqualTo("https://example.com");
    }

    @Test
    void toStatsResponse_carriesAllFieldsThrough() {
        Instant created = Instant.parse("2026-08-01T00:00:00Z");
        Instant lastAccessed = Instant.parse("2026-08-31T00:00:00Z");
        UrlMapping mapping = UrlMapping.builder()
                .shortCode("abc1234").originalUrl("https://example.com")
                .clickCount(7L).createdAt(created).lastAccessedAt(lastAccessed).active(true).build();

        UrlStatsResponse response = mapper.toStatsResponse(mapping);

        assertThat(response.clickCount()).isEqualTo(7L);
        assertThat(response.createdAt()).isEqualTo(created);
        assertThat(response.lastAccessedAt()).isEqualTo(lastAccessed);
        assertThat(response.active()).isTrue();
    }
}
