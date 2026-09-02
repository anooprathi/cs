package com.schwab.urlshortener.controller;

import com.schwab.urlshortener.service.UrlShortenerService;
import com.schwab.urlshortener.util.ShortCodeFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Public redirect surface. Kept at the root path (e.g. GET /abc1234) so
 * generated short URLs are as compact as possible, matching real-world
 * URL shorteners.
 */
@RestController
@Slf4j
public class RedirectController {

    private final UrlShortenerService service;

    public RedirectController(UrlShortenerService service) {
        this.service = service;
    }

    @GetMapping("/{shortCode:" + ShortCodeFormat.CHARSET_AND_LENGTH + "}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String originalUrl = service.resolveAndRecordHit(shortCode);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(originalUrl))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .build();
    }
}
