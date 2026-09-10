# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A multi-tenant URL shortener built as an AI-assisted engineering exercise (Charles Schwab assignment) —
positioned explicitly as a "production-informed prototype," not a production-ready system. It has real
auth, per-tenant fair-share rate limiting, and usage-based billing, but runs on in-memory H2 with no
migrations, CI, or payment processor integration. See `README.md` §9/§10 and `ARCHITECTURE.md` §7 for the
full, honest list of what separates it from production, and `ENGINEERING_SUMMARY.md` for the AI-assisted
execution log (what was generated/edited/rejected and why).

## Commands

```bash
mvn clean install              # build
mvn clean verify               # run all tests + JaCoCo coverage report (target/site/jacoco/index.html)
mvn test -Dtest=ClassName                        # run a single test class
mvn test -Dtest=ClassName#methodName             # run a single test method

# Run locally — a Spring profile is REQUIRED (app refuses to start without one, see RequiredProfileGuard)
mvn spring-boot:run -Dspring-boot.run.profiles=dev    # H2 console, seeded demo tenants (keys printed to console log)
mvn spring-boot:run -Dspring-boot.run.profiles=prod   # production-shaped config

# Postman/Newman collection (postman/url-shortener.postman_collection.json) against a running dev instance:
newman run postman/url-shortener.postman_collection.json
```

- JaCoCo enforces an **80% line-coverage gate on `mvn verify`** (`pom.xml`) — a failing build from coverage,
  not just test failures, is expected behavior, not a bug to route around.
- Swagger UI: `http://localhost:8080/swagger-ui.html`. H2 console (dev only):
  `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:mem:urlshortener`, user `sa`, empty password).
- Requires JDK 17+, Maven 3.9+, and internet access to Maven Central (dependencies are not vendored).

## Architecture

**Two entry points into one service layer**, deliberately kept separate: `RedirectController` (public,
unauthenticated `GET /{shortCode}` hot path) and `UrlShortenerController` (authenticated `/api/v1/urls/**`
management API). Both funnel through `UrlShortenerService` → `UrlMappingRepository` → H2. This split exists
so the high-traffic redirect surface can later be scaled/cached/rate-limited independently of management
traffic.

**Tenancy is enforced at the query level, not with an `if` check.** Every management-API read/write goes
through repository methods scoped by `(shortCode, tenantId)` together (e.g.
`findByShortCodeAndActiveTrueAndTenantId`), so a cross-tenant access is structurally impossible to satisfy
from the DB rather than blocked by a comparison a future refactor could accidentally drop. Cross-tenant
access returns `404`, never `403` (a `403` would confirm the resource exists for someone else). The public
redirect path is intentionally tenant-agnostic.

**Request pipeline for authenticated routes:**
```
ApiKeyAuthenticationFilter (X-API-Key → TenantPrincipal)
  → RateLimitFilter (consumes 1 "api" token for the resolved tenant; 429 if exhausted)
  → Spring Security AuthorizationFilter
  → Controller
```
Admin routes (`/api/v1/admin/**`) run through a **separate, parallel identity**: `AdminAuthenticationFilter`
checks a distinct `X-Admin-Key` header against a single server-configured hash
(`app.admin.api-key-hash`). There is no admin row in the tenant table and no self-registration — a tenant's
own (valid) API key on an admin route is `403`, not elevated access. This fails **closed**: an unconfigured
hash disables admin access outright rather than silently opening it.

**Fair-share rate limiting** (`TenantRateLimiterService`): every tenant gets independent Bucket4j buckets
(separate "api" and "redirect" families), cached in Caffeine, keyed strictly by `tenantId` — one tenant
maxing out its bucket cannot reduce another tenant's capacity. Redirect-path consumption is charged to the
**link owner**, not the anonymous caller, since the caller has no identity to rate-limit. The backend is
swappable (`app.rate-limit.backend=local|redis`, see `RateLimiterBackend`/`RedisRateLimiterBackend`) — this
is the one piece of in-process state that would otherwise prevent correct multi-instance/multi-DC operation,
since local buckets let a tenant's effective limit multiply by instance count.

**Billing is metering + rating only — it never touches a payment processor.** `UsageMeteringService` counts
billable events (create + redirect) per tenant per calendar month; those writes run **async**, off the
redirect hot path, on a dedicated bounded executor (`AsyncConfig`) — a deliberate trade-off where a crash
between the primary write and the metering write can lose that one usage count (documented in
`UsageMeteringService`'s Javadoc; acceptable for billing *summaries*, not exact-to-the-event charging).
`BillingService` reads those counters and rates them (base fee + included quota + per-unit overage, integer
cents, never floating point) into a `BillingStatementResponse`. `InvoiceService` freezes a statement into an
immutable `Invoice` snapshot — one per `(tenant, period)`, regenerating the same period is `409` — via
`BillingService.getStatementForPeriod` (a pure read), never a live reference back to usage records, so an
issued invoice's amounts can never change retroactively.

**Concurrency/ACID posture:** `existsByShortCode` pre-checks (custom alias and random-code generation) are
fast-fail UX only — the real uniqueness guarantee is the DB unique constraint on `shortCode`. Both creation
paths catch `DataIntegrityViolationException` from a lost race and map it to `409 DuplicateAliasException`
rather than an unhandled `500`; the generated-code path deliberately does **not** retry in-place after a
failed flush (the transaction/persistence context isn't safe to keep using — same reasoning as
`UsageRecordCreator` below). Click counts use an atomic `UPDATE ... SET clickCount = clickCount + 1`
(`@Modifying @Query`), never read-modify-write.

**Spring self-invocation gotcha to know about:** `UsageRecordCreator` is its own bean specifically so its
`@Transactional(REQUIRES_NEW)` insert goes through the AOP proxy — a same-class private-method call would
silently bypass Spring's transaction advice. If you're adding new `REQUIRES_NEW` (or any advice-dependent)
logic, it needs the same bean-boundary treatment, not a private method.

**Random Base62 short codes, not sequential/ID-derived** — sequential codes are enumerable (`GET /1`, `/2`,
...), leaking creation order and letting anyone scrape all links. Generation retries a bounded number of
times on collision before failing with a safe, non-leaking `500`.

**Soft delete only** (`active` flag) — no hard deletes anywhere in the URL-mapping lifecycle, to preserve
click history/analytics and avoid referential surprises. `ExpiredUrlCleanupService` runs on a fixed schedule
to batch-deactivate expired rows so the "active" index stays clean without per-request overhead on the
redirect path.

**External URL-safety check** (`UrlSafetyClient`/`FeignUrlSafetyChecker`, Spring Cloud OpenFeign) is a real,
wired extension point but **feature-flagged off by default** (no external service to call in this
environment) — it fails open (treats URL as safe) on error/timeout rather than blocking creation on a
third-party outage.

**`GlobalExceptionHandler`** (`@RestControllerAdvice`) is the single place every error response gets mapped
to the shared JSON envelope (`timestamp`/`status`/`error`/`message`/`path`/`details`) — internal exception
details/stack traces never reach a client from anywhere else in the app.

## Config

Three Spring profiles (`dev`/`prod`/`test`) via `.properties` files under `src/main/resources/` — no profile
is silently defaulted (`RequiredProfileGuard` makes a missing `--spring.profiles.active` a hard startup
failure). Plan-based rate limits and pricing are type-safe `@ConfigurationProperties`
(`RateLimitProperties`, `BillingProperties`), tunable per environment without a code change — compare
`application.properties` against `application-prod.properties` rather than assuming defaults carry over.
