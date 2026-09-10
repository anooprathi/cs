# CLAUDE.md

Guidance for Claude Code when working in `src/main/java/com/schwab/urlshortener/controller/`.

Both controllers are thin — they delegate to `UrlShortenerService` and own no business logic themselves.
What they DO own directly:
- **`RedirectController`**: the `302 Found` status choice and building the `Location` header from the
  service's returned `originalUrl`, plus `Cache-Control: no-cache` on the redirect response (so a browser/CDN
  doesn't cache a redirect that could become stale the moment the link is deactivated, updated, or expires).
  Its `@GetMapping` path pattern embeds `ShortCodeFormat.CHARSET_AND_LENGTH` directly rather than a bare
  `{shortCode}` — this is what keeps this route from swallowing paths that were never valid short codes in the
  first place (see `util/CLAUDE.md`).
- **`UrlShortenerController`**: pulls the caller's identity exclusively from `@AuthenticationPrincipal
  TenantPrincipal` (populated by `ApiKeyAuthenticationFilter`), never from a request parameter/body field —
  this is *why* a tenant can't act on another tenant's data by passing a different id, not just a documented
  convention. `createShortUrl` sets the `Location` header to `/api/v1/urls/{shortCode}` on `201`. Two GET
  routes (`/{shortCode}` and `/{shortCode}/stats`) intentionally return the identical `UrlStatsResponse` via
  the same service call — kept as two mappings for API discoverability/REST convention, not because they
  differ in behavior.

No dedicated unit test class exists for either controller — they're validated through
`UrlShortenerControllerWebMvcTest` (`@WebMvcTest`, mocked service layer) and the full-stack
`UrlShortenerIntegrationTest`/`AdminIntegrationTest`. A behavior change here (a new endpoint, a changed status
code, a changed header) needs one of those updated, not a new unit test file.
