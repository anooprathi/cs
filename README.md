# URL Shortener — AI-Assisted Engineering Assignment (Charles Schwab)

**Positioning: a production-informed prototype, not a production-ready system.** It was built with real
production concerns in mind (auth, rate limiting, billing, observability), and an adversarial self-review
plus an independent production-readiness review both found and fixed genuine issues along the way — but it
does not yet have everything actual production deployment requires (persistent database, migrations, CI/CD,
a real administrative identity system, and more). See §10 "Known Limitations" and the "Production Readiness
Roadmap" at the end of this document for the full, honest accounting.

A multi-tenant, production-*informed* URL shortener built with **Spring Boot 3 / Spring Cloud (OpenFeign) /
Spring Data JPA / H2 / Spring Security**, with per-tenant fair-share rate limiting and usage-based billing,
developed using AI-assisted engineering practices as described in `ENGINEERING_SUMMARY.md`.

See also:
- `ARCHITECTURE.md` — components, control flow, key design decisions
- `ENGINEERING_SUMMARY.md` — the three required scenarios (greenfield / brownfield / ambiguous), AI-assisted execution log, risks & validation

---

## 1. Tech Stack

| Concern              | Choice                                                        |
|-----------------------|----------------------------------------------------------------|
| Language               | Java 17+                                                        |
| Framework              | Spring Boot 3.3.x                                               |
| Cloud                  | Spring Cloud 2023.0.x (OpenFeign declarative client)             |
| Security               | Spring Security 6 — stateless API-key auth                       |
| Persistence             | Spring Data JPA + H2 (in-memory)                                 |
| Validation              | Jakarta Bean Validation                                          |
| Rate limiting           | Bucket4j (token bucket) + Caffeine (bucket cache), per tenant     |
| Billing                 | Custom metering + rating (usage -> statement; no payment processing) |
| API docs                | springdoc-openapi / Swagger UI                                   |
| Config                  | `.properties` files + Spring profiles (`dev`, `prod`, `test`)     |
| Error handling          | `@RestControllerAdvice` centralized exception mapping             |
| Test                    | JUnit 5, Mockito, AssertJ, Spring `MockMvc`, `spring-security-test`, `@WebMvcTest`, `@SpringBootTest` |
| Coverage                | JaCoCo (>=80% line coverage gate on `mvn verify` — see note in `pom.xml`) |

## 2. Project Layout

```
url-shortener/
├── pom.xml
├── README.md / ARCHITECTURE.md / ENGINEERING_SUMMARY.md
└── src
    ├── main/java/com/schwab/urlshortener/
    │   ├── UrlShortenerApplication.java
    │   ├── controller/        redirect + management REST endpoints
    │   ├── service/            business logic (interface + impl)
    │   ├── repository/         Spring Data JPA repository (links)
    │   ├── entity/              JPA entity (UrlMapping)
    │   ├── dto/                 request/response records
    │   ├── exception/           custom exceptions + GlobalExceptionHandler
    │   ├── client/               Spring Cloud OpenFeign client (URL safety check)
    │   ├── util/                 Base62Encoder
    │   ├── tenant/                Tenant entity/repo/service/controller, API keys
    │   ├── security/              Spring Security config + API-key auth filter
    │   ├── ratelimit/             per-tenant token-bucket rate limiting
    │   ├── billing/                usage metering + billing statement rating
    │   └── config/                 OpenAPI config, dev-only demo data seeder
    ├── main/resources/
    │   ├── application.properties       common defaults
    │   ├── application-dev.properties   local dev (H2 console, demo tenants)
    │   └── application-prod.properties  production-shaped hardening
    └── test/java/com/schwab/urlshortener/  (mirrors main, + integration/)
```

## 3. Setup Instructions

### Prerequisites
- JDK 17+
- Maven 3.9+
- Internet access to Maven Central (dependencies are **not** vendored)

> **Verification record:** this project was authored in a sandboxed environment with no access to Maven
> Central and no local JDK compiler, so it could not be `mvn`-built during authoring — every file was
> hand-reviewed for compilation correctness at the time, not compiler-verified. It has since been
> **independently built and run outside that sandbox**, and multiple real issues found in that process were
> fixed and are reflected in the current code (see `ENGINEERING_SUMMARY.md` §9-14 for the full account,
> including several genuine bugs this caught that a hand review alone had missed). See §9 below for the
> specific verification record — Java/Maven versions, the exact command run, test results, and coverage.

### Build
```bash
mvn clean install
```

### Run

A profile is now **required** — the app refuses to start without one (see `RequiredProfileGuard`; a
production review correctly flagged the old implicit "dev" default as a real security issue, not a
convenience):
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev    # local development — H2 console, seeded demo tenants,
                                                       # their API keys printed to the console log on startup
mvn spring-boot:run -Dspring-boot.run.profiles=prod   # production-shaped config

# or, running the packaged jar directly:
java -jar target/url-shortener-1.0.0.jar --spring.profiles.active=prod
```

The service starts on `http://localhost:8080`.
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- H2 console (dev only): `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:mem:urlshortener`, user `sa`, empty password)

### Run tests + coverage
```bash
mvn clean verify
```
JaCoCo report: `target/site/jacoco/index.html`.

---

## 4. How to Run It Yourself — curl Walkthrough

Everything below assumes the service is running locally on port 8080 (`mvn spring-boot:run -Dspring-boot.run.profiles=dev`).

### 4.1 Register a tenant (no auth needed — this is how you get a key)

```bash
curl -s -X POST http://localhost:8080/api/v1/tenants \
  -H "Content-Type: application/json" \
  -d '{"name": "acme-corp", "plan": "STANDARD"}' | tee /tmp/tenant.json
```

Response (the `apiKey` is shown **exactly once** — save it, it cannot be retrieved again):
```json
{
  "tenantId": 1,
  "name": "acme-corp",
  "plan": "STANDARD",
  "apiKey": "usk_9f2a...redacted...",
  "createdAt": "2026-08-31T12:00:00Z"
}
```

Grab it into a shell variable for the rest of this walkthrough:
```bash
export API_KEY=$(jq -r .apiKey /tmp/tenant.json)
```

> **Dev-profile shortcut:** if you started the app with the default `dev` profile, two demo tenants
> (`demo-standard-tenant`, `demo-premium-tenant`) were already registered at startup — check the console log
> for a block starting `DEV SEED DATA` and copy an API key from there instead of registering your own.

### 4.2 Confirm the key works
```bash
curl -s http://localhost:8080/api/v1/tenants/me \
  -H "X-API-Key: $API_KEY"
```
```json
{"tenantId": 1, "name": "acme-corp", "plan": "STANDARD"}
```

### 4.3 Create a short URL
```bash
curl -s -i -X POST http://localhost:8080/api/v1/urls \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"originalUrl": "https://www.schwab.com/research/some/long/path"}'
```
Note the `X-RateLimit-Limit` / `X-RateLimit-Remaining` response headers, and the `201 Created` body:
```json
{
  "shortCode": "aZ3kQ9m",
  "shortUrl": "http://localhost:8080/aZ3kQ9m",
  "originalUrl": "https://www.schwab.com/research/some/long/path",
  "createdAt": "2026-08-31T12:01:00Z",
  "expiresAt": null
}
```

Or with a custom alias and an expiry:
```bash
curl -s -X POST http://localhost:8080/api/v1/urls \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
        "originalUrl": "https://www.schwab.com/research",
        "customAlias": "schwab-research",
        "expiresAt": "2026-12-31T00:00:00Z"
      }'
```

### 4.4 Use the short link (public — no API key needed)
```bash
curl -s -i http://localhost:8080/schwab-research
# HTTP/1.1 302 Found
# Location: https://www.schwab.com/research
```

### 4.5 Check click/usage stats for a link (tenant-scoped)
```bash
curl -s http://localhost:8080/api/v1/urls/schwab-research/stats \
  -H "X-API-Key: $API_KEY"
```
```json
{
  "shortCode": "schwab-research",
  "originalUrl": "https://www.schwab.com/research",
  "clickCount": 1,
  "createdAt": "2026-08-31T12:01:00Z",
  "lastAccessedAt": "2026-08-31T12:02:00Z",
  "expiresAt": "2026-12-31T00:00:00Z",
  "active": true
}
```

### 4.6 Deactivate a link
```bash
curl -s -i -X DELETE http://localhost:8080/api/v1/urls/schwab-research \
  -H "X-API-Key: $API_KEY"
# HTTP/1.1 204 No Content
```

### 4.6a List your own links, update one, and undo a deactivation

Three tenant-facing operations added after the initial build, for a real functional gap: there was no way
for a tenant to enumerate their own links (only look one up by a code you already knew), no way to fix a
typo'd destination without losing the link's history, and no way to undo an accidental deactivation.

```bash
# Every link you've ever created, active or not (paginated: page/size, default 50/page)
curl -s http://localhost:8080/api/v1/urls \
  -H "X-API-Key: $API_KEY"
```
```json
{
  "content": [
    {"shortCode": "schwab-research", "originalUrl": "https://www.schwab.com/research", "clickCount": 1,
     "createdAt": "...", "lastAccessedAt": "...", "expiresAt": null, "active": true}
  ],
  "page": 0, "size": 50, "totalElements": 1, "totalPages": 1
}
```

```bash
# Fix a typo'd destination, or push the expiry out — both fields optional,
# at least one required. Re-validated through the same safety checker as creation.
curl -s -X PATCH http://localhost:8080/api/v1/urls/schwab-research \
  -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"originalUrl": "https://www.schwab.com/research-2026"}'

# Undo an accidental deactivation — idempotent if the link is already active.
# Note: reactivating a link whose expiresAt is already in the past brings it
# back to active=true, but it will still 410 on the next redirect until its
# expiry is also pushed out via the PATCH above — these are deliberately two
# separate actions, not one implicit "reactivate and extend."
curl -s -X PATCH http://localhost:8080/api/v1/urls/schwab-research/reactivate \
  -H "X-API-Key: $API_KEY"
```

### 4.7 Check your current billing statement
```bash
curl -s http://localhost:8080/api/v1/tenants/me/billing \
  -H "X-API-Key: $API_KEY"
```
```json
{
  "tenantId": 1,
  "plan": "STANDARD",
  "billingPeriod": "2026-08",
  "apiCallsUsed": 2,
  "apiCallsIncluded": 50,
  "redirectsUsed": 1,
  "redirectsIncluded": 500,
  "baseFeeCents": 0,
  "overageChargeCents": 0,
  "totalChargeCents": 0
}
```
This is a computed statement (metering + rating) — **no payment is ever processed**; see
`ARCHITECTURE.md` / `BillingService`'s Javadoc for the scope boundary and where a real payment provider
would plug in.

### 4.7a Generate and download an invoice

Turn the current billing statement into a formal, frozen invoice:
```bash
curl -s -X POST http://localhost:8080/api/v1/tenants/me/invoices \
  -H "X-API-Key: $API_KEY" | tee /tmp/invoice.json
```
```json
{
  "invoiceId": 1,
  "invoiceNumber": "INV-2026-08-000001",
  "billingPeriod": "2026-08",
  "plan": "STANDARD",
  "apiCallsUsed": 2, "apiCallsIncluded": 50,
  "redirectsUsed": 1, "redirectsIncluded": 500,
  "baseFeeCents": 0, "overageChargeCents": 0, "totalChargeCents": 0,
  "status": "ISSUED",
  "issuedAt": "2026-08-31T12:05:00Z"
}
```
Calling this again for the *same* period returns `409 Conflict` — an invoice, once issued, is a frozen
snapshot, not something that gets silently regenerated as usage continues. You can also request a specific
past period: `-d '{"billingPeriod": "2026-07"}'` (a future period is rejected with `400`).

List and fetch invoices:
```bash
curl -s http://localhost:8080/api/v1/tenants/me/invoices -H "X-API-Key: $API_KEY"
curl -s http://localhost:8080/api/v1/tenants/me/invoices/1 -H "X-API-Key: $API_KEY"
```

Download it as a PDF:
```bash
export INVOICE_ID=$(jq -r .invoiceId /tmp/invoice.json)
curl -s -o invoice.pdf http://localhost:8080/api/v1/tenants/me/invoices/$INVOICE_ID/pdf \
  -H "X-API-Key: $API_KEY"
open invoice.pdf   # macOS; use xdg-open on Linux or start on Windows
```

### 4.8 See the "noisy neighbor" protection in action
```bash
# Fire more requests than the STANDARD plan's per-minute limit
# (see application.properties: app.rate-limit.plans.standard.api-permits-per-minute)
for i in $(seq 1 25); do
  curl -s -o /dev/null -w "%{http_code} " http://localhost:8080/api/v1/tenants/me -H "X-API-Key: $API_KEY"
done
echo
# ... 200 200 200 ... 429 429 429   <- this tenant is now throttled

# A second, unrelated tenant is completely unaffected — register one and try it:
curl -s -X POST http://localhost:8080/api/v1/tenants -H "Content-Type: application/json" \
  -d '{"name": "other-tenant"}' | tee /tmp/tenant2.json
export API_KEY_2=$(jq -r .apiKey /tmp/tenant2.json)
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/v1/tenants/me -H "X-API-Key: $API_KEY_2"
# 200  <- unaffected by the first tenant hammering the API
```

### 4.9 Error cases
```bash
# No API key
curl -s -i http://localhost:8080/api/v1/urls/anything/stats
# 401 Unauthorized

# Invalid API key
curl -s -i http://localhost:8080/api/v1/urls/anything/stats -H "X-API-Key: not-a-real-key"
# 401 Unauthorized

# Unknown / expired short code
curl -s -i http://localhost:8080/doesNotExist
# 404 Not Found

# Duplicate custom alias
curl -s -i -X POST http://localhost:8080/api/v1/urls -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" -d '{"originalUrl":"https://example.com","customAlias":"schwab-research"}'
# 409 Conflict (if schwab-research already exists and is still active)
```

### 4.10 Admin — cross-tenant visibility & management

A **separate credential** from tenant API keys — `X-Admin-Key`, not `X-API-Key`. There's no admin
self-registration endpoint (that would defeat the point); the key is configured server-side via
`app.admin.api-key-hash` (a SHA-256 hash, same pattern as tenant keys — see `AdminSecurityProperties`).
**Dev profile only:** the dev-only key is `admin-dev-only-key-change-me` (see `application-dev.properties`
for its hash and the "never use in production" warning right next to it).

```bash
export ADMIN_KEY=admin-dev-only-key-change-me

# List every tenant, their plan, and active status
curl -s http://localhost:8080/api/v1/admin/tenants \
  -H "X-Admin-Key: $ADMIN_KEY"
```
```json
[
  {"tenantId": 1, "name": "demo-standard-tenant", "plan": "STANDARD", "active": true, "createdAt": "..."},
  {"tenantId": 2, "name": "demo-premium-tenant", "plan": "PREMIUM", "active": true, "createdAt": "..."}
]
```

```bash
# One tenant, plus a snapshot of their current-period usage
curl -s http://localhost:8080/api/v1/admin/tenants/1 \
  -H "X-Admin-Key: $ADMIN_KEY"

# Upgrade a tenant's plan
curl -s -X PATCH http://localhost:8080/api/v1/admin/tenants/1/plan \
  -H "X-Admin-Key: $ADMIN_KEY" -H "Content-Type: application/json" \
  -d '{"plan": "PREMIUM"}'

# Suspend a tenant — this blocks their API key immediately, not just cosmetically
curl -s -X PATCH http://localhost:8080/api/v1/admin/tenants/1/status \
  -H "X-Admin-Key: $ADMIN_KEY" -H "Content-Type: application/json" \
  -d '{"active": false}'

# See every link a tenant has ever created, including deactivated ones
curl -s http://localhost:8080/api/v1/admin/tenants/1/urls \
  -H "X-Admin-Key: $ADMIN_KEY"

# Platform-wide usage this period, broken down by tenant
curl -s http://localhost:8080/api/v1/admin/usage \
  -H "X-Admin-Key: $ADMIN_KEY"

# Lost API key recovery: rotate a tenant's key without touching their name,
# plan, links, or billing history. The old key stops working immediately —
# no overlap window — and the new raw key is returned exactly once, just
# like at registration; it cannot be retrieved again after this response.
curl -s -X POST http://localhost:8080/api/v1/admin/tenants/1/rotate-key \
  -H "X-Admin-Key: $ADMIN_KEY"

# Branded/custom domain: application-level association only — this does NOT
# provision DNS or a TLS certificate for the domain (see §12 "Custom domains"
# for that explicit boundary). Once set, new links created by this tenant use
# it in their returned shortUrl.
curl -s -X PATCH http://localhost:8080/api/v1/admin/tenants/1/domain \
  -H "X-Admin-Key: $ADMIN_KEY" -H "Content-Type: application/json" \
  -d '{"customDomain": "go.acme.com"}'

# Clear it — customDomain: null reverts the tenant to the platform's default host.
curl -s -X PATCH http://localhost:8080/api/v1/admin/tenants/1/domain \
  -H "X-Admin-Key: $ADMIN_KEY" -H "Content-Type: application/json" \
  -d '{"customDomain": null}'
```

```bash
# A tenant's own key is NOT an admin credential — using it here is 403,
# distinct from presenting no credential at all (401)
curl -s -i http://localhost:8080/api/v1/admin/tenants \
  -H "X-API-Key: $API_KEY"
# 403 Forbidden

curl -s -i http://localhost:8080/api/v1/admin/tenants
# 401 Unauthorized
```

---

## 5. API Reference (summary)

| Method | Path                              | Auth        | Notes |
|--------|-------------------------------------|-------------|-------|
| POST   | `/api/v1/tenants`                    | none        | Self-register a tenant, returns the API key **once** |
| GET    | `/api/v1/tenants/me`                 | API key     | Confirm your key / see your plan |
| GET    | `/api/v1/tenants/me/billing`         | API key     | Current-period usage + computed charges |
| POST   | `/api/v1/tenants/me/invoices`        | API key     | Generate an invoice for the current (or a given) period — once per period |
| GET    | `/api/v1/tenants/me/invoices`        | API key     | List all invoices for this tenant |
| GET    | `/api/v1/tenants/me/invoices/{id}`   | API key     | Fetch one invoice |
| GET    | `/api/v1/tenants/me/invoices/{id}/pdf` | API key   | Download the invoice as a PDF |
| POST   | `/api/v1/urls`                        | API key     | Create a short URL |
| GET    | `/api/v1/urls`                        | API key     | List every link this tenant has created, active or not (paginated) |
| GET    | `/api/v1/urls/{shortCode}/stats`      | API key     | Tenant-scoped — 404s for another tenant's link |
| PATCH  | `/api/v1/urls/{shortCode}`            | API key     | Update destination and/or expiry — at least one field required |
| PATCH  | `/api/v1/urls/{shortCode}/reactivate` | API key     | Undo a deactivation — idempotent if already active |
| DELETE | `/api/v1/urls/{shortCode}`            | API key     | Deactivate (soft delete), tenant-scoped |
| GET    | `/{shortCode}`                        | **none**    | Public redirect (302), rate-limited against the link owner's quota |
| GET    | `/api/v1/admin/tenants`               | Admin key   | List every tenant, plan, and active status |
| GET    | `/api/v1/admin/tenants/{id}`          | Admin key   | One tenant + current-period usage snapshot |
| PATCH  | `/api/v1/admin/tenants/{id}/plan`     | Admin key   | Change a tenant's plan (upgrade/downgrade) |
| PATCH  | `/api/v1/admin/tenants/{id}/status`   | Admin key   | Suspend (`active:false`) or reinstate a tenant — blocks their key immediately |
| POST   | `/api/v1/admin/tenants/{id}/rotate-key` | Admin key | Rotate a tenant's API key (lost-key recovery) — old key stops working immediately |
| PATCH  | `/api/v1/admin/tenants/{id}/domain`   | Admin key   | Set/clear a tenant's branded short-link domain — application-level association only, no DNS/TLS provisioning |
| GET    | `/api/v1/admin/tenants/{id}/urls`     | Admin key   | Every link that tenant has created, active or not |
| GET    | `/api/v1/admin/usage`                 | Admin key   | Platform-wide usage for the current period, broken down by tenant |

Full interactive reference: `http://localhost:8080/swagger-ui.html` (click "Authorize" and paste your API key).

Every error response uses the same envelope:
```json
{
  "timestamp": "2026-08-31T12:00:00Z",
  "status": 409,
  "error": "Conflict",
  "message": "Custom alias is already in use: schwab-research",
  "path": "/api/v1/urls",
  "details": []
}
```

## 6. Multi-Tenancy, Security, Fair-Share Rate Limiting & Billing — Summary

- **Tenancy**: every short link belongs to exactly one tenant (`UrlMapping.tenantId`). Management-API reads/
  writes are tenant-scoped at the query level (`findByShortCodeAndActiveTrueAndTenantId`) — a cross-tenant
  lookup returns `404`, not `403`, so it doesn't even confirm the link exists for someone else. The public
  redirect is intentionally tenant-agnostic (anyone with the link can use it).
- **Security**: stateless `X-API-Key` header auth (`ApiKeyAuthenticationFilter` -> Spring Security context).
  Keys are generated with `SecureRandom`, shown once, and stored only as a SHA-256 hash (see `Tenant`
  Javadoc for why SHA-256 rather than BCrypt is the right call here).
- **Fair share / noisy-neighbor protection**: `TenantRateLimiterService` gives every tenant independent
  Bucket4j token buckets — one for the management API, one for redirects (charged against the *link owner*,
  since redirect callers are anonymous). One tenant maxing out its bucket cannot reduce another tenant's
  available capacity — proven directly in `TenantRateLimiterServiceTest`.
- **Billing**: `UsageMeteringService` counts billable events (create + redirect) per tenant per calendar
  month; `BillingService` rates that into a statement (base fee + metered overage, in integer cents). This
  computes what a tenant would owe — it does not charge a card or move money (see `BillingService` Javadoc).
- **Invoicing**: `POST /api/v1/tenants/me/invoices` freezes a billing statement into an immutable `Invoice`
  row (one per tenant per period — regenerating the same period is a `409`), retrievable as JSON or as a
  generated PDF (`InvoicePdfGenerator`, via OpenPDF). Same scope boundary as billing: an invoice states what
  is owed, it does not collect payment.
- **Admin**: a genuinely separate identity from tenant API keys, not an elevated tenant — a distinct
  `X-Admin-Key` header, checked by its own `AdminAuthenticationFilter` against a server-configured hash
  (`app.admin.api-key-hash`), with no self-registration endpoint. Fails **closed**: if that hash isn't
  configured, admin access is disabled outright rather than silently open. Gives cross-tenant visibility
  (list/inspect any tenant, see any tenant's links) and management (plan changes, suspend/reinstate) that
  intentionally bypass per-tenant scoping — the entire point of the surface — while staying fully separate
  from and unreachable via any tenant's own key (a tenant key on an admin route is `403`, not elevated
  access).

See `ARCHITECTURE.md` for the full control-flow diagram and design-decision rationale.

## 7. Spring Boot 4 Migration Attempt (Reverted)

This project **attempted** a migration to Spring Boot 4.1.1 and reverted back to 3.3.4. Kept here as a
record of what was tried, what worked, and what didn't — this isn't hidden or quietly undone.

**What was correctly identified and fixed along the way** (mechanical Boot 4 changes, verified against
current documentation before applying): `spring-boot-starter-web` → `spring-boot-starter-webmvc` rename;
`@MockBean`/`@SpyBean` removed in favor of `@MockitoBean`; `@WebMvcTest`/`@AutoConfigureMockMvc` moved to a
dedicated `spring-boot-webmvc-test` module; Spring Cloud release train bumped to the one aligned with Boot
4.0.x/4.1.x; springdoc-openapi bumped to its Jakarta-EE-11 line; a deliberate decision to stay on Jackson 2
via Spring's own `spring-boot-jackson2` bridge rather than adopt Jackson 3 unverified.

**What ended the attempt:** `@WebMvcTest` in Boot 4 does not supply an `HttpSecurity` bean to a custom
`SecurityFilterChain @Bean` method inside an `@Import`-ed `@Configuration` class — `SecurityConfig`'s
`securityFilterChain(HttpSecurity http)` method failed with `NoSuchBeanDefinitionException` for its own
`HttpSecurity` parameter, even with `SecurityConfig` explicitly imported into the test slice. This is a real
behavior change from Boot 3, whose `@WebMvcTest` documentation explicitly guaranteed Spring Security
auto-configuration ("By default, tests annotated with `@WebMvcTest` will also auto-configure Spring
Security"). Two prior guesses at the cause (a compile-time package move, then a hypothesized Jackson
bean-resolution ambiguity) were each plausible, each fixed something real, and neither was the actual root
cause — the real error only surfaced once the full stack trace was available, not the Maven summary section.
At that point, further guessing without a compiler available in the authoring environment stopped being a
reasonable way to spend the user's time, and reverting to the known-working 3.3.4 baseline was the right
call — see `ENGINEERING_SUMMARY.md` for the fuller account.

**What was kept from the attempt, because it's an improvement independent of Spring Boot version:**
`SecurityConfig`, `ApiKeyAuthenticationFilter`, and `RateLimitFilter` no longer depend on Spring's
auto-configured `ObjectMapper` bean — they use a small dedicated `ErrorResponseWriter` instead, since they
only ever serialize one small, fixed DTO. This removes a class of bean-resolution fragility regardless of
Boot version and was not reverted.

## 8. Production Readiness

Async execution, ACID hardening, observability, and multi-datacenter support — see `ARCHITECTURE.md` §7 for
the full writeup (including an explicit, honest boundary between what's real application code here and what
genuinely requires infrastructure outside this repository for true multi-DC operation). Summary:

- **Async**: `UsageMeteringService`'s writes run off the hot redirect path via a dedicated bounded executor
  (`AsyncConfig`) — real async, with the consistency trade-off stated in that class's Javadoc, not hidden.
- **ACID**: the short-code/custom-alias creation race (previously a documented-but-unhandled limitation) now
  resolves correctly under real concurrency — see `UrlShortenerServiceImpl`.
- **Observability**: Prometheus metrics (`urls.created`, `redirects.served`, `rate_limit.rejections`),
  distributed tracing (Micrometer + Brave/Zipkin), and Kubernetes-style liveness/readiness health probes.
- **Multi-DC**: a swappable rate-limiter backend (`app.rate-limit.backend=local|redis` —
  `RedisRateLimiterBackend`) — the one piece of in-process state that would otherwise prevent correct
  multi-instance/multi-DC operation, since local buckets let a tenant's effective limit multiply by instance
  count. The app was already stateless otherwise (no server-side sessions).

## 9. Verification Record — STATUS: CONFIRMED (with one fix made during this pass)

A real `mvn clean verify` and a real Postman/Newman run were executed against this working tree — not
inferred, not carried over from an earlier commit. One genuine discrepancy surfaced doing this and was fixed
before this table was filled in (see the note below); everything else passed clean on the first try.

| Item | Value |
|---|---|
| Date verified | 2026-09-08 |
| Commit verified | `f0ed5ed745f72310c00a7b9800b76fc3bff6e0da`, **plus uncommitted working-tree changes** (the admin API-key-rotation feature and its tests — run `git status` for the exact file list). This is not a clean checkout of a single commit; it is the literal state of the working tree at verification time. |
| Java version | `21.0.4` (Oracle, LTS) |
| Maven version | Apache Maven `3.9.9` |
| Command run | `mvn verify` (equivalent to `clean verify` for this purpose — no stale `target/` from a different commit was present) |
| Test result | Tests run: 188, Failures: 0, Errors: 0, Skipped: 0 |
| JaCoCo line coverage | 94.9% (878/925 lines) — clears the 0.80 gate with margin; see `target/site/jacoco/index.html` after running `mvn verify` for the full per-class breakdown |
| Application smoke test | Started via `mvn spring-boot:run -Dspring-boot.run.profiles=dev`; create → redirect → stats → deactivate flow confirmed working end-to-end via both curl and the Postman collection below |
| Postman collection | The full 69-request collection (39 test-scripts, 61 assertions) run via `newman run postman/url-shortener.postman_collection.json` against a freshly started dev instance: **0 failures** |

**What the Postman run actually found before it was clean**: the first run failed exactly one assertion —
`7. Error Cases / 403 - Root Path` expected `403` and got `401`. This was not an application bug: the JUnit
regression test for the same scenario (`UrlShortenerIntegrationTest#rootPath_matchesNoRoute_isRejectedCleanly_notAnUnhandled500`)
already correctly asserts `401` and explains why (an anonymous caller denied by `denyAll()` gets routed to
Spring Security's `AuthenticationEntryPoint`, not its `AccessDeniedHandler`). The Postman collection's own
assertion was simply never updated to match, despite the commit that introduced this behavior (`cf53698`)
claiming in its message that "the existing regression test **and Postman assertion** ... were updated
accordingly" — only the JUnit side of that claim was actually true. Fixed here: the Postman test now expects
`401` and its name/comment explain why, matching the JUnit test's reasoning (see `SecurityConfig`'s inline
comment above `.anyRequest().denyAll()`, added during this same pass for the same reason). Re-run after the
fix: clean, 0 failures. This is exactly the class of gap this document has repeatedly said only running the
system — not reading it — can catch, and it held true again here.

## 10. Known Limitations / Trade-offs

See `ENGINEERING_SUMMARY.md` §5 for the full list. Highlights:
- Rate-limit buckets default to single-node/in-process (`app.rate-limit.backend=local`) — switch to
  `redis` for multi-instance/multi-DC deployments (see §8 above).
- No real payment processor integration — billing is metering + rating only, by design (see §4.7 above).
- ~~No tenant plan-upgrade/downgrade flow, no admin console for cross-tenant visibility~~ — addressed: see
  §4.10 / §6 above (`/api/v1/admin/**`). Remaining gap: no admin UI, API only; no audit log of admin actions
  beyond the application log lines each mutation already writes.
- The Spring Cloud OpenFeign "URL safety check" integration is a real, wired extension point but is
  feature-flagged **off** by default (no external service to call in this environment).
- True multi-datacenter deployment requires real infrastructure (DB replication topology, cross-DC traffic
  routing, secrets distribution) this codebase cannot itself provide — see ARCHITECTURE.md §7.4 for exactly
  where that line sits.
- Still on Spring Boot 3.3.4 — see §7 above for why a Boot 4 upgrade was attempted and reverted. It's also no
  longer one of Spring's actively-maintained community versions (3.4/3.5 are current) — a real deployment
  should upgrade Spring Boot and Spring Cloud together and add automated dependency-currency checks.
- ~~`GlobalExceptionHandler`'s handlers for `RateLimitExceededException`/`MethodArgumentTypeMismatchException`,
  and `ExpiredUrlCleanupService` entirely, had no direct test coverage~~ — addressed: `GlobalExceptionHandlerTest`
  and `ExpiredUrlCleanupServiceTest` now cover both directly (see `ENGINEERING_SUMMARY.md` §16).

## 11. Production Readiness Roadmap

Everything below is a genuine, currently-open gap between this prototype and an actual production
deployment — organized by what it would take to close each one, not glossed over. Items marked **(code)**
are pure application-code/config changes that could be made without new infrastructure; items marked
**(infra)** require real infrastructure this project's own codebase cannot provide or verify on its own
(a real database, a CI runner, a container registry, a monitoring stack, etc.) — attempting to fake those
would be worse than naming them plainly.

### Data & persistence **(infra + code)**
- Replace in-memory H2 with PostgreSQL (or MySQL) for any real deployment; nothing in the JPA/Hibernate
  layer is H2-specific, but the actual database, its connection details, and its operational ownership
  (backups, HA) are infrastructure outside this repository.
- Add Flyway or Liquibase migrations, and switch `spring.jpa.hibernate.ddl-auto` from `update` to `validate`
  in production — schema changes should be an explicit, reviewed, versioned migration, not something
  Hibernate infers and applies automatically at startup.
- Add real foreign-key constraints between tenants, links, usage records, and invoices (today these are
  plain indexed `Long` columns — a deliberate simplicity trade-off documented in `ARCHITECTURE.md`, not an
  oversight, but one a real deployment should revisit alongside the migration work above, since retrofitting
  FK constraints onto a live table with any pre-existing orphaned rows needs a real migration, not just an
  entity annotation).
- Backups, point-in-time recovery, and periodic restore testing — none of which exist today because there's
  no persistent store to back up in the first place.

### Authentication & access control **(code, mostly)**
- Rate-limit tenant registration, invalid-key attempts, and admin endpoints by IP/client — today's
  `RateLimitFilter` only ever sees *authenticated* requests; an unauthenticated flood against
  `POST /api/v1/tenants` or repeated invalid-key guesses against any protected route has no throttle at all.
- Replace the single shared admin credential with a real identity system (OIDC/RBAC or equivalent) —
  workable for one operator, not for an organization with more than a handful of people needing admin access
  and no way to tell them apart in an audit log.
- Add API-key lifecycle management: rotation, expiry, revocation, scopes, last-used timestamps, and an audit
  history of key usage — today a key is valid forever from creation until a tenant is suspended outright,
  with no finer-grained control.
- Protect `/actuator/metrics` and `/actuator/prometheus` with something more purpose-built than reusing the
  admin credential (done as an interim fix — see `SecurityConfig`'s Javadoc for why it's explicitly flagged
  as pragmatic, not final) — a real deployment would put these on a private network/port a monitoring system
  reaches without going through the public-facing filter chain at all.

### Abuse prevention & data handling **(code + infra)**
- Enable URL reputation/malware/phishing checks by default in production (`FeignUrlSafetyChecker` is real
  and wired, feature-flagged off because there's no real safety-check provider to call from this
  environment — flipping it on requires an actual external service, which is infra, not code).
- Add domain/destination deny-lists, abuse reporting, and takedown support — none of which exist today.
- Replace regex-only URL validation with real URI parsing and explicit scheme/host validation (today's
  `@Pattern`-based check accepts anything shaped like a URL; it doesn't parse and validate it as one).
- Stop logging complete destination URLs verbatim — redact query parameters and fragments before they ever
  reach a log line, since a shortened URL can legitimately contain tokens or personal information in its
  query string.
- Layer edge/IP/global rate limits ahead of this application (a gateway or WAF) — today, an anonymous caller
  hitting `GET /{shortCode}` is only rate-limited against the *link owner's* quota (a deliberate design — see
  `ARCHITECTURE.md` — but one an independent review correctly noted means an attacker can exhaust a victim's
  own redirect quota by hammering their public link; the owner-quota check is real protection against
  runaway cost, not a substitute for edge-level abuse controls).

### Billing correctness **(code, substantial redesign)**
- Introduce closed billing periods — today, a tenant can invoice the current (still-accruing) month early,
  and the existing duplicate-invoice protection then permanently blocks generating the corrected final
  invoice once the month actually closes. A real system needs an explicit period-close step before
  invoicing is allowed, or a superseding/correction mechanism.
- Store versioned plan/pricing snapshots per billing period — invoices currently rate historical usage
  against the tenant's *current* plan and pricing configuration, so a plan or price change retroactively
  changes what a past invoice would compute to if regenerated.
- Replace the async, at-most-once usage counters with a transactional outbox or durable event stream if
  billing needs to be exact-to-the-event (the current trade-off — occasional lost or, more precisely,
  possibly-orphaned events on a rollback — is stated plainly in `UsageMeteringService`'s own Javadoc as
  acceptable for usage *summaries*, explicitly not for real charging).
- Add currency handling, taxes, credits, refunds, payment state, and accounting reconciliation if billing
  ever needs to charge a real payment instrument — today it computes and reports what's owed, in cents,
  in one implied currency, and never touches a payment processor at all, by design.
- Paginate invoice history (`GET /invoices` currently returns every invoice a tenant has ever generated,
  unbounded — the same category of gap already fixed for the admin listing endpoints, not yet applied here).

### Scalability & reliability **(code, mostly)**
- Use the Redis rate-limiter backend (already built — see `RedisRateLimiterBackend`) for any multi-instance
  deployment; the default `local` backend means limits multiply by instance count.
- Make the Redis backend's `INCR` + expiry check genuinely atomic (a Lua script, or a proven distributed
  rate-limiting library) rather than the current two-command-plus-self-healing-check approach — functional
  and tested against the specific failure mode it targets, but a hand-rolled approximation of what a
  purpose-built library would guarantee more rigorously.
- Cache short-code-to-URL mappings and tenant plan lookups — every redirect currently does a live DB lookup
  for both; fine at prototype scale, a real bottleneck at high redirect volume.
- Move click-count analytics off the synchronous hot-row `UPDATE` and toward batched/streamed aggregation —
  correct and race-free today, but a point contention risk under very high per-link traffic.
- Change expired-link cleanup from "load every expired row, save them all" to a paged or bulk `UPDATE`, add
  a composite `(active, expires_at)` index, and add a distributed lock (e.g. ShedLock) before this ever runs
  as more than one instance — today it's correct for a single instance and would do redundant work, though
  not incorrect work, across several.

### Delivery & operations **(infra, entirely)**
- CI pipeline running tests, coverage, static analysis, and packaging on every change — none exists; this
  project has been validated by manual `mvn clean verify` runs only.
- A Dockerfile and reproducible deployment definition — none exists; "how this actually gets deployed
  anywhere" is entirely unaddressed by this repository.
- Testcontainers-based tests against a real database and Redis, plus load/concurrency/soak testing — the
  existing test suite validates logic correctness against H2 and mocks; it says nothing about behavior under
  real concurrent load or against a real Postgres/Redis.
- Dependency, secret, SAST, container, and license scanning, plus SBOM generation — none configured.
- Dashboards, alerts, SLOs, a runbook, rollback procedure, and disaster-recovery/restore testing — none
  exist because there's no running production deployment to operate in the first place.

None of the above is presented as "coming soon" in a way that implies it's simple — several of these
(billing period closure, the outbox pattern, a real admin identity system, the entire delivery/operations
list) are substantial engineering efforts in their own right, comparable in scope to portions of this project
that already took multiple iterative passes. They're listed here because a "production-informed prototype"
should say precisely what separates it from a production-ready system, not leave that gap implicit.

## 12. Product Direction Considered — "Secure Temporary Link Manager" (Not Implemented)

A distinct **product** direction (as opposed to §11's engineering/infrastructure gaps) was proposed and is
recorded here deliberately — thought about, deliberately not built, not overlooked. It reframes the in-memory
H2 database from a limitation into an intentional feature: a disposable, self-contained link manager for
development teams, internal campaigns, demos, and time-limited sharing, where links are meant to expire, a
restart deliberately clearing the environment is a feature rather than a data-loss risk, and no external
database install is required to stand one up locally or inside a private network. **None of the reframing or
the feature list below has been adopted or implemented** — the application today is still the general-purpose
tenant/billing-oriented shortener described in the rest of this document.

Status markers below: unmarked = not implemented at all; **✅** = already covered by something that exists
today; **◐** = partially covered, with the gap named.

**1. Web dashboard** — no UI exists; the product is API-only (Swagger UI is API documentation, not an operator
dashboard). Building one would now have real REST endpoints to sit on top of, not a green field: **◐** listing
(`GET /api/v1/urls`), viewing statistics (`GET /api/v1/urls/{shortCode}/stats`), and disable/reactivate
(`DELETE` / `PATCH .../reactivate`) all exist at the API level today — a dashboard would be presentation over
existing capability, not new capability. Still not implemented at any level: search (the list endpoint pages
but doesn't filter/search), and filtering by active/expired/disabled specifically (the list endpoint returns
everything; a caller filters client-side today).

**2. Temporary-link features**
- Maximum lifetime (e.g. 24h / 30 days as a plan-level policy, not just a caller-supplied date) — not implemented.
- One-time links (auto-deactivate after the first redirect) — not implemented.
- Maximum-click limit — not implemented.
- Scheduled activation (a link that isn't live until a future time) — not implemented.
- Automatic expiration — **✅** already implemented (`expiresAt` + `ExpiredUrlCleanupService`'s scheduled sweep).
- Password-protected links — not implemented.

**3. Link management**
- Tags and campaign names — not implemented.
- Destination editing after creation — **✅** implemented (`PATCH /api/v1/urls/{shortCode}`, added this session —
  re-validated through the same safety checker as creation, and evicts the redirect cache so an edit takes
  effect immediately rather than waiting out the cache TTL).
- Duplicate-URL detection (warn/dedupe when the same destination is shortened twice) — not implemented; today's
  uniqueness check is on the short code/alias, never on the destination URL.
- Bulk creation via CSV, bulk deactivation — not implemented (every management endpoint is single-link).
- QR-code generation, a UTM parameter builder, a destination-preview page — not implemented.

**4. Better analytics** — today's analytics is `clickCount` + `lastAccessedAt` per link, nothing more. Not
implemented: clicks-over-time series, unique-visitor tracking, referrer capture, device/browser detection,
country/region (geo-IP), bot detection, CSV export, or a "most popular links" ranking. Any of these would also
reopen the "stop logging complete destination URLs" and general PII-handling questions already flagged in
§11's abuse-prevention section, since request-level analytics data (IP, user agent, referrer) is itself
sensitive.

**5. Teams and security**
- Multiple API keys per tenant, key expiration (TTL) — not implemented; a tenant has exactly one active key.
- Key rotation — **✅** implemented (admin-triggered `POST /api/v1/admin/tenants/{id}/rotate-key`, added this
  session for lost-key recovery — see §4.10). Key **revocation as a distinct concept** is only ◐ partial:
  suspending the whole tenant (`active=false`) blocks its key, but there's no way to revoke one key among
  several, since there's only ever one.
- Read-only vs. editor roles — not implemented; a tenant is a single undifferentiated role, and admin is a
  single `ROLE_ADMIN` with no finer grants.
- Audit log — not implemented as a queryable record; only ad hoc `INFO`-level application log lines exist per
  mutation (already named as a gap in §10).
- Email or invite-based registration — not implemented; registration today is self-service, name-only, with
  no identity verification at all.
- Prevent public users from assigning themselves PREMIUM — **✅** already fixed (see `ENGINEERING_SUMMARY.md`
  §19) — registration unconditionally assigns `STANDARD`; a plan change is admin-only.

**6. Custom domains** (e.g. `go.company.com/pricing`) — **◐** partial, added this session: an admin can
associate a branded domain with a tenant (`PATCH /api/v1/admin/tenants/{id}/domain`, `Tenant.customDomain`,
enforced unique across tenants), and links that tenant creates afterward return a `shortUrl` built from that
domain instead of the platform default. **What this deliberately does not do**: provision DNS (the domain
must already be CNAME'd to this deployment by whoever controls it) or issue/manage a TLS certificate for that
exact hostname — without both, `https://{customDomain}/{shortCode}` won't actually resolve to or be trusted
for this app. That's real infrastructure (typically a reverse proxy with automatic ACME/Let's Encrypt
handling, e.g. Caddy or Traefik, sitting in front of this app) in the same category as the Postgres/Redis/K8s
gaps in §11 — this codebase records and validates the association, it does not and cannot make the domain
actually work end-to-end on its own. Redirect resolution itself also remains path-only (`GET /{shortCode}`
matches on path regardless of which `Host` header it arrived on) — the custom domain is purely cosmetic in
the returned `shortUrl` today, not a routing/namespace change, so short codes are still globally unique across
all tenants rather than scoped per-domain.

**7. Safety controls**
- URL malware/phishing scanning — ◐ partial: `FeignUrlSafetyChecker` is real and wired but feature-flagged
  **off** by default, since there's no real safety-check provider to call in this environment (see §8, §11).
- Domain deny-list, a report-abuse endpoint — not implemented (deny-list already named as a gap in §11).
- Administrative suspension of a *specific link* (as opposed to an entire tenant) — not implemented; today
  admin action stops at the tenant level (`PATCH /api/v1/admin/tenants/{id}/status`), there's no per-link
  admin override.
- Registration/IP rate limiting — not implemented (already named as a gap in §11 — today's `RateLimitFilter`
  only ever sees already-authenticated requests).
- Stop logging complete destination URLs verbatim — not implemented (already named as a gap in §11).

**Making in-memory storage safer** (leaning into H2-in-memory as a deliberate choice rather than trying to
hide it):
- A maximum total link count and a maximum links-per-tenant cap — not implemented (no ceiling exists today
  beyond available heap).
- Automatic eviction of expired records — ◐ partial: `ExpiredUrlCleanupService` **deactivates** expired links
  on a schedule, but never deletes the rows, so expired data still occupies memory indefinitely; true eviction
  (row deletion, or moving to a cold store) is not implemented.
- Memory-usage metrics and alerts, and graceful degradation when a capacity limit is reached — not implemented.
- Export links to JSON/CSV, import links at startup (beyond `DevDataSeeder`'s fixed demo data), and an optional
  periodic encrypted-file snapshot — none of this exists; there is no persistence path out of or into the
  in-memory store at all today.
- A clear, runtime-visible warning that unsaved links disappear on restart — ◐ partial: this is documented in
  prose (this README, and a comment directly above `spring.datasource.url` in `application.properties`), but
  there is no warning surfaced at runtime (a startup log banner, a response header, or a dashboard notice) —
  only written documentation a reader has to already know to look for.
