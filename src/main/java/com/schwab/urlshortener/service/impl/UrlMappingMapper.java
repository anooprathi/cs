package com.schwab.urlshortener.service.impl;

import com.schwab.urlshortener.dto.ShortenUrlResponse;
import com.schwab.urlshortener.dto.UrlStatsResponse;
import com.schwab.urlshortener.entity.UrlMapping;
import org.springframework.stereotype.Component;

/**
 * Entity -> response-DTO mapping, pulled out of UrlShortenerServiceImpl so
 * that class's job stays "business rules" rather than also "shape the
 * wire representation." Trivial today; kept as its own seam so it can
 * grow (e.g. HATEOAS links, a v2 response shape) without touching the
 * service.
 */
@Component
public class UrlMappingMapper {

    public ShortenUrlResponse toShortenResponse(UrlMapping mapping, String baseUrl) {
        return new ShortenUrlResponse(
                mapping.getShortCode(),
                baseUrl + "/" + mapping.getShortCode(),
                mapping.getOriginalUrl(),
                mapping.getCreatedAt(),
                mapping.getExpiresAt()
        );
    }

    public UrlStatsResponse toStatsResponse(UrlMapping mapping) {
        return new UrlStatsResponse(
                mapping.getShortCode(),
                mapping.getOriginalUrl(),
                mapping.getClickCount(),
                mapping.getCreatedAt(),
                mapping.getLastAccessedAt(),
                mapping.getExpiresAt(),
                mapping.isActive()
        );
    }
}
