# Architecture Overview

## 1. Components

```
                         ┌─────────────────────────────────────────┐
                         │             Client (browser/API)          │
                         └───────────────┬───────────────┬─────────┘
                                          │               │
                         POST/GET /api/v1/urls/*     GET /{shortCode}
                                          │               │
                         ┌────────────────▼───┐  ┌───────▼──────────────┐
                         │ UrlShortenerController│  │  RedirectController   │
                         │ (management API)      │  │  (public redirect)    │
                         └────────────┬──────────┘  └──────────┬───────────┘
                                      │                          │
                                      └───────────┬──────────────┘
                                                   ▼
                                     ┌─────────────────────────┐
                                     │   UrlShortenerService     │◄──── UrlSafetyGuard (feign, feature-flagged)
                                     │   (business rules)         │
                                     └────────────┬────────────┘
                                                   ▼
                                     ┌─────────────────────────┐
                                     │  UrlMappingRepository     │
                                     │  (Spring Data JPA)         │
                                     └────────────┬────────────┘
                                                   ▼
                                     ┌─────────────────────────┐
                                     │        H2 (in-memory)      │
                                     └─────────────────────────┘

                         Cross-cutting: GlobalExceptionHandler (@RestControllerAdvice)
                         wraps every controller; ExpiredUrlCleanupService (@Scheduled)
                         runs independently against the same repository.
```

## 2. Tools & Execution Approach

Built with an AI coding assistant (Claude) used as an **in-task accelerator**, not an autonomous agent:
- Each unit of work (entity design, service logic, exception mapping, a test class) was scoped as a discrete
  task with explicit intent + acceptance criteria before generation.
- Generated code was reviewed line-by-line for correctness, Spring idioms, and security implications
  (e.g., short-code enumerability, error message information disclosure) before being accepted.
- See `ENGINEERING_SUMMARY.md` for the traceability log (generated / edited / rejected, with rationale).

## 3. Control Flow

**Create:**
`POST /api/v1/urls` → bean validation on `ShortenUrlRequest` → `UrlSafetyGuard.isSafe()` (no-op unless
feature-flagged on) → alias-collision or random-code-generation → persist → `201` with `Location` header.

**Redirect (hot path):**
`GET /{shortCode}` → `findByShortCodeAndActiveTrue` (indexed lookup) → expiry check → atomic
`incrementClickCount` (single `UPDATE`, no read-modify-write race) → `302` with `Location` header.

**Reliability:** `ExpiredUrlCleanupService` runs on a fixed delay, batch-deactivating expired rows so the
"active" index stays clean without adding per-request overhead to the redirect path.

## 4. Key Design Decisions

| Decision | Rationale |
|---|---|
| Random Base62 short codes (not sequential-id encoding) | Sequential/id-derived codes are enumerable (`GET /1`, `/2`, `/3`, ...), leaking creation order and letting anyone scrape all shortened links. Random codes with a bounded collision-retry loop avoid that at negligible cost. |
| Separate `RedirectController` (root path) vs `UrlShortenerController` (`/api/v1/urls`) | Keeps the public, high-traffic redirect surface decoupled from the management/analytics API — they can be scaled, cached, secured, or rate-limited independently later. |
| Atomic `UPDATE ... SET clickCount = clickCount + 1` via `@Modifying @Query` | Avoids a read-modify-write race on click counts under concurrent hits to the same popular short code. |
| Soft delete (`active` flag) instead of hard delete | Preserves click-history/analytics for a deactivated link and avoids FK/referential surprises; matches "reliability" requirement better than destructive deletes. |
| `@RestControllerAdvice` centralizing all error mapping | One place to guarantee every error response uses the same envelope and that internal exception details never leak to clients (security: no stack traces / class names in `message`). |
| Spring Cloud OpenFeign `UrlSafetyClient`, feature-flagged off | Demonstrates a realistic extension point (blocking phishing/malware links) using Spring Cloud's declarative HTTP client, without making the prototype depend on an external service to run. Fails open on error/timeout — see Risks. |
| JPA `ddl-auto: update` + H2 in-memory | Matches the "inbuilt H2 DB" requirement; for a real deployment this would move to Flyway/Liquibase migrations against Postgres/MySQL — called out as a limitation, not hidden. |
| Records for DTOs | Immutable, concise, and validation annotations sit directly on the components — idiomatic Java 17+. |
| Tenant scoping at the *query* level (`findByShortCodeAndActiveTrueAndTenantId`), not just an app-level `if` check | Defense in depth — a cross-tenant read/write is structurally impossible to satisfy from the DB, not just blocked by a comparison that a future refactor could accidentally remove. |
| Cross-tenant access returns `404`, never `403` | A `403` confirms the resource exists for someone else; `404` reveals nothing about another tenant's data — standard practice for multi-tenant systems. |
| API keys hashed with SHA-256 (not BCrypt) | API keys are high-entropy, machine-generated secrets, not user-chosen passwords — they don't need BCrypt's deliberately-slow per-guess cost to resist offline brute-forcing, and a fast deterministic hash allows a direct indexed lookup instead of scanning/comparing against every stored hash. |
| Per-tenant rate limiting via independent Bucket4j buckets, not a single global limiter | A global limiter (or a limiter keyed by IP) lets one tenant's spike degrade everyone; per-tenant buckets guarantee fair share by construction — see §5. |
| Redirect-path rate limiting charged to the *link owner*, not the (anonymous) caller | The caller of a public redirect has no identity to rate-limit; the capacity being protected belongs to whoever created the link, so that's what's metered and throttled. |
| Billing as metering + rating only, no payment processor integration | Actually charging a card requires handling payment credentials and executing a financial transaction — both out of scope for this exercise. The statement this system produces is exactly the input a real payment integration (e.g. Stripe metered billing) would consume. |
| `UsageRecordCreator` as its own Spring bean, not a private method with `@Transactional(REQUIRES_NEW)` | Spring's transaction advice only applies through the AOP proxy; a same-class ("self-invocation") call bypasses it silently. Extracting the insert into a real bean-to-bean call makes the `REQUIRES_NEW` isolation actually take effect — a real Spring gotcha, not just style. |
| Invoices are immutable snapshots, not a live view over usage | An issued invoice must not change amount after the fact just because the tenant kept using the service that month. Generating twice for the same period is a `409`, not an overwrite or a silent no-op — mirrors how real invoicing systems behave. |
| Invoice number derived from `(period, tenantId)` rather than a separate sequence table | The `(tenantId, billingPeriod)` unique constraint already guarantees uniqueness, so `INV-{period}-{tenantId:06d}` is human-readable, deterministic, and needs no second write/lookup to mint — simpler than a two-phase "save, then patch in the generated number" approach. |
| PDF via OpenPDF, not a templating/HTML-to-PDF pipeline | The invoice layout is simple and fully data-driven (a handful of key/value pairs and a small line-item table) — a direct programmatic API is less moving parts than adding an HTML template engine + renderer for this. |

## 5. Reliability & Failure Scenarios Considered

| Scenario | Handling |
|---|---|
| Two requests race to claim the same custom alias | DB unique constraint on `shortCode` is the real backstop; the `existsByShortCode` pre-check is a fast-fail UX improvement, not the sole guarantee. **[SUPERSEDED]** A `DataIntegrityViolationException` from the DB is now caught and mapped to the same `409 DuplicateAliasException` the pre-check would throw — see §7.2 "ACID / concurrency hardening." (This note previously said the opposite — "not yet mapped to 409" — accurate when written, fixed in the production-readiness pass.) |
| Random code generator collides repeatedly | Bounded retry (5 attempts) then `ShortCodeGenerationException` → `500` with a safe, non-leaking message; server-side log captures detail for on-call. |
| Redirect requested for expired link | `410 Gone`, distinct from `404`, so clients/analytics can tell "never existed" apart from "existed, now expired." |
| External URL-safety service down (if enabled) | Fails open (treats URL as safe) rather than blocking all link creation on a third-party outage — a deliberate availability-over-strictness trade-off, logged at ERROR for visibility. |
| Malformed JSON / wrong field types | Mapped to `400` via `HttpMessageNotReadableException` handler, not a raw `500`. |

## 6. Multi-Tenancy, Security, Rate Limiting & Billing

### 6.1 Tenancy model
`Tenant` (id, name, hashed API key, plan) is the isolation boundary. `UrlMapping.tenantId` ties every link to
its creator. The redirect path is intentionally tenant-agnostic (public); every management-API path
(`create` implicitly stamps the caller's tenant; `stats`/`deactivate` query by `(shortCode, tenantId)`
together) enforces isolation at the repository-query level rather than via an application-level `if` check —
see the design-decisions table above for why that distinction matters.

### 6.2 Security
```
Request → ApiKeyAuthenticationFilter (resolves X-API-Key → TenantPrincipal in SecurityContext)
        → RateLimitFilter (consumes 1 "api" token for the resolved tenant; 429 if exhausted)
        → Spring Security's AuthorizationFilter (authorizeHttpRequests rules)
        → Controller (@AuthenticationPrincipal TenantPrincipal)
```
Stateless (`SessionCreationPolicy.STATELESS`) — every request re-authenticates independently via the header;
no cookie, no CSRF token needed (CSRF is disabled accordingly, which is only safe *because* there's no
cookie-based session to forge).

### 6.3 Fair-share rate limiting (noisy-neighbor protection)
```
                     ┌────────────────────────────────┐
   Tenant A ────────▶│  Bucket A ("api")   [●●●○○]      │──▶ allowed / 429
                     ├────────────────────────────────┤
   Tenant B ────────▶│  Bucket B ("api")   [●●●●●]      │──▶ allowed / 429   (independent of A)
                     └────────────────────────────────┘
```
Two bucket families per tenant ("api" for the management API, "redirect" for the public hot path), each an
independent Bucket4j `Bucket` cached in Caffeine. Because buckets are keyed strictly by `tenantId`, one
tenant's traffic — however extreme — cannot consume another tenant's tokens; there is no shared counter for
a noisy tenant to exhaust. Limits differ by plan (`STANDARD` vs `PREMIUM`) via type-safe
`@ConfigurationProperties`, tunable per environment without a code change (compare `application.properties`
vs `application-prod.properties`).

**Single-node caveat**: buckets live in local process memory. Horizontally scaling this service to N
instances would let a tenant's *effective* rate multiply by N (each instance enforces its own bucket). A
production rollout at that point would swap Bucket4j's local buckets for its Redis/Hazelcast-backed
distributed mode — the `TenantRateLimiterService` interface/call sites wouldn't need to change, only the
`Bucket` construction inside it.

### 6.4 Billing (metering + rating, not payment processing)
```
create/redirect → UsageMeteringService (atomic per-tenant, per-month counters)
                           │
GET /tenants/me/billing ──▶ BillingService (reads counters, applies plan pricing) ──▶ BillingStatementResponse
```
`TenantUsageRecord` holds one row per `(tenantId, "yyyy-MM")`. Two billable events are metered: creating a
link and serving a redirect; read-only calls (stats, `/me`, the billing endpoint itself) are not metered.
`BillingService` applies a "base fee + included quota + per-unit overage" pricing model (integer cents,
never floating point) — the same shape most real usage-based SaaS billing uses.

**This is deliberately as far as the system goes.** It computes what a tenant would owe; it does not store
payment instruments, call a payment gateway, or move money. In a real deployment, `BillingStatementResponse`
is exactly the shape you'd hand to a provider like Stripe's metered-billing API to actually collect payment
— that integration point is a natural brownfield extension, not something faked here.

**Concurrency note**: the first usage event in a new billing period does an insert-or-retry (see
`UsageMeteringService` / `UsageRecordCreator` Javadoc) rather than relying on DB-specific `MERGE`/upsert
syntax, so it works unchanged against H2 today and Postgres/MySQL later.

### 6.5 Invoicing
```
POST /tenants/me/invoices ──▶ InvoiceService
                                   │  1. reject if an Invoice already exists for (tenant, period) → 409
                                   │  2. rate the period via BillingService.getStatementForPeriod(...)
                                   │  3. freeze the result into an Invoice row (immutable from here on)
                                   ▼
                              Invoice (JSON) ──or──▶ InvoicePdfGenerator ──▶ PDF bytes (GET .../pdf)
```
An `Invoice` is a point-in-time snapshot — once generated, its monetary fields never change, even if the
tenant's usage for that period is (implausibly) still being metered concurrently. This is why `InvoiceService`
calls `BillingService.getStatementForPeriod` (a pure read) rather than storing a reference back to the live
`TenantUsageRecord`. The PDF is rendered directly from the frozen `Invoice` entity's fields, not by
re-querying billing, for the same reason.

**Scope boundary, stated plainly**: this system computes and documents what a tenant owes. It never stores a
payment instrument, never calls a payment gateway, and the `InvoiceStatus.ISSUED` value is the only state
this system can ever legitimately set — there's no webhook or reconciliation process that could ever move it
to `PAID`. A real payment integration is a natural next step (the `Invoice` record is exactly the shape a
provider like Stripe's invoicing API would want), but implementing one is out of scope here, both for the
exercise and because it would mean handling payment credentials directly.

### 6.6 Admin surface

```
X-Admin-Key ──▶ AdminAuthenticationFilter ──▶ ROLE_ADMIN ──▶ /api/v1/admin/** (SecurityConfig)
                                                                      │
                                                                      ▼
                                                              AdminController
                                                                      │
                                                                      ▼
                              AdminService ──▶ TenantService (list/get/updatePlan/updateActiveStatus)
                                          ├──▶ UrlMappingRepository (cross-tenant link visibility)
                                          └──▶ TenantUsageRecordRepository + UsageMeteringService (usage summary)
```

**A separate identity, not an elevated tenant.** `AdminAuthenticationFilter` checks a distinct header
(`X-Admin-Key`, never `X-API-Key`) against a single server-configured hash — there is no admin row in the
`tenant` table, no admin self-registration endpoint, and `AdminPrincipal` isn't a `TenantPrincipal` subtype
or wrapper. A tenant's own key, however legitimate, authenticates them as `ROLE_TENANT` only; hitting an
admin route with it is `403` (authenticated, wrong role), not an escalation path. This separation was a
deliberate design choice over the alternative (an `isAdmin` flag on `Tenant`) specifically because it makes
"can this credential see other tenants' data" a question answerable by inspecting one filter and one header
name, not by auditing every tenant row for a flag.

**Fails closed.** `app.admin.api-key-hash` has no default in `application.properties` — if it's blank,
`AdminAuthenticationFilter` rejects any presented key outright rather than treating "no configured hash" as
"no check needed." The alternative (fail open) would mean admin endpoints silently become unauthenticated
the moment someone forgets to set the property in a new environment; failing closed makes that
misconfiguration loud (every admin request 401s) instead of silent.

**`AdminService` as its own orchestration layer**, not folded into `TenantService`: it composes across three
aggregates that each already own their persistence (tenants, links, usage records), the same role
`UrlShortenerServiceImpl` plays for its own aggregate. `TenantService` gained `getTenantById` (404 for an
unknown id — ordinary client input) alongside its existing `getTenantOrThrow` (500 for "the *authenticated*
tenant has no row," an invariant violation, not user input) — the two exist side by side deliberately, for
two genuinely different failure semantics rather than one method serving both.

**Suspension is real, not cosmetic.** `PATCH /tenants/{id}/status` with `active:false` doesn't just flip a
display flag — `TenantService.authenticate` already filters on `findByApiKeyHashAndActiveTrue`, so a
suspended tenant's existing API key stops resolving on their very next request, no separate revocation step
needed.

## 7. Production Readiness

This section covers what changed to move the prototype toward production shape: async execution, ACID
hardening, observability, and multi-datacenter support. Each is scoped honestly — some of this is real,
compiled-in application code; multi-DC in particular has a hard boundary between what a codebase can provide
and what is genuinely infrastructure/operations work, and that boundary is stated explicitly rather than
glossed over.

### 7.1 Async execution
```
UrlShortenerServiceImpl.resolveAndRecordHit()
    │
    ├─▶ repository.incrementClickCount(...)   [synchronous — click count is part of the response contract]
    │
    └─▶ usageMeteringService.recordRedirect() [ASYNC — fire-and-forget onto meteringTaskExecutor]
            (returns immediately; the redirect response does not wait on this write)
```
`UsageMeteringService.recordApiCall`/`recordRedirect` run on a dedicated, bounded `ThreadPoolTaskExecutor`
(`AsyncConfig`) rather than the calling request thread. The redirect path is the highest-QPS, most
latency-sensitive endpoint in this system; a tenant's viral link should not get slower to serve just because
every hit also does a metering write. The explicit trade-off: a crash between the primary write (the
redirect/click-count itself, which stays synchronous) and this async metering write can lose that one usage
count. Accepted because usage metering feeds billing *summaries* and *on-demand* invoice generation, not a
real-time balance — occasional undercounting by a handful of events during an outage is a materially smaller
risk than losing the click count itself. If billing ever needs to be exact-to-the-event, the correct fix is
a durable outbox (write the usage event in the *same* transaction as the primary write, then a separate
process publishes it) — genuinely more infrastructure than this system needs today, called out rather than
silently deferred. A second, more precise risk beyond "a crash could lose an event": since the async call is
fire-and-forget with no transaction coordination to the caller, it can complete and commit *before* the
caller's own transaction commits — if that caller transaction later rolled back for an unrelated reason, the
result would be a usage record for an operation that never actually happened, not merely a missing one. See
`UsageMeteringService`'s Javadoc for the full reasoning, and `AsyncConfig`'s for why
the executor is bounded (Spring's default `SimpleAsyncTaskExecutor` is *unbounded* — a burst of async work
against it can exhaust threads with no backpressure at all, a genuinely dangerous default to ship).

### 7.2 ACID / concurrency hardening
The `existsByShortCode` pre-checks in `UrlShortenerServiceImpl` (both the custom-alias and generated-code
paths) were always documented as fast-fail UX, not the actual uniqueness guarantee — that's the database's
own unique constraint on `shortCode`. What changed: the race that slips past the pre-check under real
concurrency now resolves *correctly* instead of surfacing as an unhandled 500. `createWithCustomAlias`
catches `DataIntegrityViolationException` and re-throws the same `DuplicateAliasException` the pre-check
would have (a `409`, not a `500`). `createWithGeneratedCode` catches the same exception for its own
(vanishingly rare — a random 7-character collision *and* a concurrent identical request in the same instant)
race, and deliberately does **not** attempt an in-place retry with a fresh candidate: after a failed flush,
Hibernate's persistence context for that transaction should not be treated as safe to keep using (the same
reasoning already applied to `UsageRecordCreator` being its own bean for an isolated `REQUIRES_NEW`
transaction — see that class's Javadoc). Failing the request cleanly and letting the client's normal retry
land on a fresh candidate is simpler and safer than building a retry mechanism that would itself need its own
isolated transaction to be correct.

Isolation level: left at the database default (`READ_COMMITTED` for both H2 and Postgres) rather than
specified explicitly. This is a deliberate non-decision, not an oversight — `SERIALIZABLE` would materially
hurt throughput for no correctness benefit this workload needs (the only genuine race conditions in this
system — short-code uniqueness, tenant-usage-record creation — are already handled by unique constraints and
atomic `UPDATE` statements, not by relying on transaction isolation to prevent them).

### 7.3 Observability
- **Metrics** (Micrometer, scraped via `/actuator/prometheus`): `urls.created`, `redirects.served`, and
  `rate_limit.rejections` (tagged by `plan`, deliberately *not* by tenant id — an unbounded-cardinality label
  is a real liability in a metrics backend once there are more than a handful of tenants; plan is a small,
  fixed set). A `Timer` (`redirect.resolve.duration`, with percentile histograms) wraps the hot redirect
  path specifically, since that's the one endpoint where p99 latency actually matters for user experience.
- **Tracing**: Micrometer Tracing bridged to Brave, exported in Zipkin's wire format (the most
  broadly-compatible choice — most tracing backends, including Zipkin itself, Jaeger, and most vendor APM
  tools, can ingest it). Sampling probability defaults to `1.0` (trace everything) at this prototype's
  traffic volume; a real deployment at real QPS would lower this — called out in the property's own comment
  rather than left as an unexplained value.
- **Health**: split into liveness ("is the process alive") and readiness ("can it serve traffic") probe
  groups, the standard Kubernetes-style distinction — an orchestrator shouldn't route traffic to an instance
  that's up but still starting, without also killing a healthy instance just because a downstream dependency
  blipped (which conflating the two into one health check would risk).

### 7.4 Multi-Datacenter

**What this codebase actually provides**, stated precisely rather than oversold:

1. **Statelessness by construction** — already true before this pass, and the single most important
   prerequisite for any multi-instance/multi-DC deployment. There is no server-side session, no sticky
   routing requirement: every request carries its own identity (`X-API-Key`/`X-Admin-Key`), so any instance
   in any DC can serve any request. This was a consequence of the original security design (§6.2), not
   something added for this pass.
2. **A swappable rate-limiter backend** (`RateLimiterBackend` — §6.3, `RedisRateLimiterBackend`), because the
   original local-only implementation was the one piece of state genuinely incompatible with running more
   than one instance: local Bucket4j buckets mean each instance enforces its own independent quota, so a
   tenant's *effective* limit multiplies by instance count. Pointing `app.rate-limit.backend=redis` at a
   Redis reachable from every instance/DC is what actually centralizes that state.
3. **Fully externalized configuration** — every environment-specific value (DB connection, rate limits,
   admin key, Redis endpoint) is a property overridable via environment variable, with no hardcoded
   assumption about a single deployment location. This is what lets the *same* artifact run in any region.

**What genuinely requires infrastructure this codebase cannot provide on its own** — named honestly rather
than implied to be "handled":

- **Database replication topology.** This app's JPA/Hibernate layer is topology-agnostic by design — it
  doesn't need to know if the database underneath is a single node, a read-replica set, or a globally
  distributed SQL database (e.g. AWS Aurora Global Database, CockroachDB, Google Spanner). What it *does*
  assume is that the database it's pointed at provides real ACID transactions for a single logical write —
  true of all of the above, but the *choice* between active-passive (one region accepts writes, others are
  read replicas promoted on failover) and active-active (multiple regions accept writes, with the database
  handling cross-region conflict resolution) is an infrastructure/database decision with real trade-offs
  (active-active generally trades some consistency guarantees for lower write latency in each region), not
  something an application-code change decides.
- **Traffic routing across DCs** (GSLB / DNS-based routing, or a service mesh) — deciding which region a
  given request reaches is a network/ops layer concern entirely outside this codebase.
- **Cross-DC Redis topology** for the rate limiter specifically: `RedisRateLimiterBackend` assumes it's
  pointed at *a* Redis reachable from wherever the app is running — whether that's a single regional Redis
  (introduces cross-region latency on every rate-limit check, but simplest to reason about) or a
  cross-DC-replicated Redis (e.g. Redis Enterprise Active-Active) is a deployment decision, not something the
  code chooses.
- **Config/secrets management across environments** — this app expects `APP_ADMIN_API_KEY_HASH`,
  `spring.datasource.*`, etc. as environment variables; *how* those get distributed and rotated across
  multiple DCs (a secrets manager, a config service) is ops tooling outside this repository.

Stated plainly: this pass makes the application **not the obstacle** to multi-DC deployment — every piece of
state that would have prevented it (in-process rate limiting) is now externalizable, and everything else was
already stateless. It does not, and cannot, provide the actual multi-region infrastructure a real
multi-DC deployment requires standing up around it.
