# CLAUDE.md

Guidance for Claude Code when working in `src/main/java/com/schwab/urlshortener/client/`.

`UrlSafetyClient` is only the declarative Spring Cloud OpenFeign interface — `GET {base-url}/v1/check?url=...`
returning `UrlSafetyResult(boolean safe, String category)`, target URL externalized via
`app.url-safety-check.base-url` so a real provider can be swapped in without a code change. It has no logic
of its own: the feature flag, the fail-open-on-error/timeout behavior, and everything about how a "not safe"
result actually affects URL creation live in `service/safety/FeignUrlSafetyChecker` (a different package,
which wraps this client) and its no-op sibling `service/safety/NoOpUrlSafetyChecker`. Changing safety-check
*behavior* means editing `service/safety/`, not this package — this interface only needs to change if the
external API's request/response shape changes.
