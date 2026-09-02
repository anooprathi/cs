package com.schwab.urlshortener.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Declarative (Spring Cloud OpenFeign) client for an external URL-reputation
 * check service. This is an extension point, not part of the default
 * request path: see {@link com.schwab.urlshortener.service.impl.FeignUrlSafetyChecker}
 * for how it is feature-flagged and how failures degrade safely.
 *
 * name/url are externalized so the target can be swapped for a real
 * provider (or a Eureka-registered internal service) without code changes.
 */
@FeignClient(name = "url-safety-service", url = "${app.url-safety-check.base-url:http://localhost:9999}")
public interface UrlSafetyClient {

    @GetMapping("/v1/check")
    UrlSafetyResult check(@RequestParam("url") String url);

    record UrlSafetyResult(boolean safe, String category) {
    }
}
