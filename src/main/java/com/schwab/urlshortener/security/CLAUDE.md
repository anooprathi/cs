# CLAUDE.md

Guidance for Claude Code when working in `src/main/java/com/schwab/urlshortener/security/`.

**Two independent authentication filters, both registered `addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)`**
(`SecurityConfig.securityFilterChain`): `AdminAuthenticationFilter` (reads `X-Admin-Key`) and
`ApiKeyAuthenticationFilter` (reads `X-API-Key`). Their relative order doesn't matter functionally — a real
request presents one header or the other, never both — but both must run before `RateLimitFilter`
(`addFilterAfter(..., ApiKeyAuthenticationFilter.class)`), since that filter only acts when a
`TenantPrincipal` is already in the `SecurityContext`; an admin request has none, so it passes through
untouched. Fair-share rate limiting is a tenant concept, not an admin one — don't add admin-plan rate
limiting to `RateLimitFilter` itself, that would conflate two identity models this codebase keeps
deliberately separate (see root `CLAUDE.md` and `AdminAuthenticationFilter`'s Javadoc).

**Each auth filter fails a *presented-but-invalid* credential immediately** (writes `401` and returns,
doesn't call `filterChain.doFilter`) rather than falling through to "unauthenticated." A *missing* header
is treated as simply unauthenticated and allowed to continue — `SecurityConfig`'s
`authorizeHttpRequests`/`AuthenticationEntryPoint` is what turns that into a `401` for routes that actually
require identity. Keep this distinction if you touch either filter: "wrong key" and "no key" both end up
`401` today, but via different code paths, and a route-level `permitAll()` only works correctly if "no
key" doesn't short-circuit inside the filter.

**`denyAll()` is the catchall, not `permitAll()`** (`.anyRequest().denyAll()`) — a route this config doesn't
explicitly list is refused by default. This was a real gap: `/actuator/metrics` and `/actuator/prometheus`
were previously reachable unauthenticated purely because nothing claimed them and the old catchall
defaulted open. If you add a new endpoint, it needs an explicit `authorizeHttpRequests` line — it will not
work by omission the way it might under a `permitAll()` default.

**Actuator metrics/prometheus are gated behind `ROLE_ADMIN`** as a pragmatic reuse of the existing identity
model, explicitly documented in `SecurityConfig`'s Javadoc as not the final answer — a real deployment
would put these on a private network/port instead of behind the same credential used for tenant
administration (tracked in `README.md`'s production-readiness roadmap, not silently treated as done).

**`401` vs `403` is deliberate and tested**, not incidental: `handleUnauthenticated`
(`AuthenticationEntryPoint`) fires for no-credential-at-all; `handleAccessDenied` (`AccessDeniedHandler`)
fires for a real-but-insufficient credential (e.g. a valid tenant `X-API-Key` hitting `/api/v1/admin/**`).
An anonymous caller matched by `denyAll()` gets routed to the entry point (`401`), not the access-denied
handler (`403`), because Spring Security treats an unauthenticated principal as "not authenticated" first —
see `UrlShortenerIntegrationTest#rootPath_matchesNoRoute_isRejectedCleanly_notAnUnhandled500` for the
regression this distinction guards, and don't "fix" a denyAll 401 into a 403 assuming it's a bug.

**Admin key comparison uses `MessageDigest.isEqual`**, not `String.equals` or `==`, specifically to avoid a
timing side-channel — this is the one credential in the system with unrestricted cross-tenant access, so
the constant-time comparison is worth the marginal cost even though tenant-key comparison
(`findByApiKeyHashAndActiveTrue`, a DB-indexed lookup rather than a string compare) doesn't need the same
treatment.

**Admin auth fails closed on missing config**: `AdminAuthenticationFilter` rejects *any* presented
`X-Admin-Key` outright when `app.admin.api-key-hash` is blank, rather than treating "not configured" as "no
check needed." `application.properties` deliberately leaves this unset; only `application-dev.properties`
sets a (clearly labeled, dev-only) value. Don't add a default hash to the common properties file.
