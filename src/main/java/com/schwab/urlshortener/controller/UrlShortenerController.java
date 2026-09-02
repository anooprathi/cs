package com.schwab.urlshortener.controller;

import com.schwab.urlshortener.dto.ShortenUrlRequest;
import com.schwab.urlshortener.dto.ShortenUrlResponse;
import com.schwab.urlshortener.dto.UrlStatsResponse;
import com.schwab.urlshortener.service.UrlShortenerService;
import com.schwab.urlshortener.tenant.TenantPrincipal;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * Management API for short URLs: create, inspect, view stats, deactivate.
 * Every operation here is tenant-scoped — the caller's identity comes
 * from Spring Security (populated by ApiKeyAuthenticationFilter from the
 * X-API-Key header) and is injected via @AuthenticationPrincipal, never
 * taken from a request parameter, so a tenant cannot act on another
 * tenant's data by passing a different id in the body/query.
 *
 * The actual redirect endpoint lives in {@link RedirectController} at the
 * root path — public, unauthenticated, kept separate from this
 * tenant-scoped management surface.
 */
@RestController
@RequestMapping("/api/v1/urls")
@Slf4j
public class UrlShortenerController {

    private final UrlShortenerService service;

    public UrlShortenerController(UrlShortenerService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ShortenUrlResponse> createShortUrl(@Valid @RequestBody ShortenUrlRequest request,
                                                               @AuthenticationPrincipal TenantPrincipal tenant) {
        ShortenUrlResponse response = service.createShortUrl(request, tenant.tenantId());
        return ResponseEntity
                .created(URI.create("/api/v1/urls/" + response.shortCode()))
                .body(response);
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<UrlStatsResponse> getUrlDetails(@PathVariable String shortCode,
                                                            @AuthenticationPrincipal TenantPrincipal tenant) {
        return ResponseEntity.ok(service.getStats(shortCode, tenant.tenantId()));
    }

    @GetMapping("/{shortCode}/stats")
    public ResponseEntity<UrlStatsResponse> getStats(@PathVariable String shortCode,
                                                       @AuthenticationPrincipal TenantPrincipal tenant) {
        return ResponseEntity.ok(service.getStats(shortCode, tenant.tenantId()));
    }

    @DeleteMapping("/{shortCode}")
    public ResponseEntity<Void> deactivate(@PathVariable String shortCode,
                                            @AuthenticationPrincipal TenantPrincipal tenant) {
        service.deactivate(shortCode, tenant.tenantId());
        return ResponseEntity.noContent().build();
    }
}
