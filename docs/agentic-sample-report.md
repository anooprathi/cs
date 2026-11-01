<!--
  Sample output, NOT hand-written: this is the verbatim response of
  GET /api/v1/agentic/runs/1/report.md from a dev-profile instance on 2026-11-01, after
  POST /api/v1/agentic/runs (mode omitted, so DETERMINISTIC_DEMO) and a CHANGES_REQUESTED review.
  No language model was involved. See README.md section 12 to reproduce it.
-->

# SDLC run 1: Max-click limit for short links

> **Execution mode: DETERMINISTIC_DEMO. No language model was called.**
> Stages 1-5 were produced by rule-based templates: keyword classification, a vague-term lexicon, and keyword matching against a curated catalog of this codebase's components. Treat this as a demonstration of the orchestration pipeline and its guardrails, not as AI analysis of the requirement.

| Field | Value |
|---|---|
| Status | CHANGES_REQUESTED |
| Quality gate | PASSED |
| Created | 2026-11-01T22:20:34.949038Z |
| Completed | 2026-11-01T22:20:35.141490Z |

## Original requirement

> Tenants must be able to set an optional maximum click limit when creating a short link.
> Once the limit is reached, the redirect should stop working and return 410 Gone, like an expired link.
> The click count shown in stats must stay accurate under concurrent redirects.
> The redirect path must stay fast.
> Admins should be able to see the limit when listing a tenant's links.
> Bulk editing of existing links is out of scope.

_Source: deterministic rule-based demo logic. No model was called._

## 1. Requirements

**Max-click limit for short links**: Tenants must be able to set an optional maximum click limit when creating a short link.

### Functional

- **FR-1**: Tenants must be able to set an optional maximum click limit when creating a short link.
- **FR-2**: Once the limit is reached, the redirect should stop working and return 410 Gone, like an expired link.
- **FR-3**: Admins should be able to see the limit when listing a tenant's links.

### Non-functional

- **NFR-1**: The click count shown in stats must stay accurate under concurrent redirects.
- **NFR-2**: The redirect path must stay fast.

### Acceptance criteria

| ID | Verifies | Given | When | Then |
|---|---|---|---|---|
| AC-1 | FR-1 | an authenticated tenant (X-API-Key) using the management API | the behavior described in FR-1 is exercised | the system observably satisfies: "Tenants must be able to set an optional maximum click limit when creating a short link" |
| AC-2 | FR-2 | an authenticated tenant (X-API-Key) using the management API | the behavior described in FR-2 is exercised | the system observably satisfies: "Once the limit is reached, the redirect should stop working and return 410 Gone, like an expired link" |
| AC-3 | FR-3 | an operator authenticated with X-Admin-Key | the behavior described in FR-3 is exercised | the system observably satisfies: "Admins should be able to see the limit when listing a tenant's links" |
| AC-4 | NFR-1 | the agreed measurement conditions for NFR-1 | the quality attribute is measured or verified | the result meets: "The click count shown in stats must stay accurate under concurrent redirects" |
| AC-5 | NFR-2 | the agreed measurement conditions for NFR-2 | the quality attribute is measured or verified | the result meets: "The redirect path must stay fast" |

### Ambiguities and assumptions

| ID | Ambiguity | Question for the requirement owner | Assumption taken |
|---|---|---|---|
| AMB-1 | "fast" in: The redirect path must stay fast. | What latency target is required, at which percentile and load? | p95 latency under 200 ms at current integration-test load; no new load-test infrastructure is in scope. |

### Out of scope

- Bulk editing of existing links is out of scope.
- Applying code changes automatically: every artifact in this run is a proposal for human review.

_Source: deterministic rule-based demo logic. No model was called._

## 2. Impact analysis

| Component | Change | Related criteria | Reason |
|---|---|---|---|
| `UrlMapping` | MODIFY | AC-1, AC-2, AC-3, AC-4 | JPA entity for a short link: shortCode, originalUrl, tenantId, expiry, click count, active flag (soft delete). Matched requirement keywords: [expir, click count, link]. |
| `UrlShortenerServiceImpl` | MODIFY | AC-1, AC-2, AC-3 | Core business rules: create (custom alias / generated code), update, deactivate, reactivate, resolve-and-record-hit. Matched requirement keywords: [short link, expir, link]. |
| `CachedShortCodeLookup` | MODIFY | AC-2, AC-4, AC-5 | Caffeine-cached short-code resolution on the redirect hot path, with eviction on change. Matched requirement keywords: [redirect]. |
| `ExpiredUrlCleanupService` | MODIFY | AC-2 | Scheduled batch deactivation of expired links. Matched requirement keywords: [expir]. |
| `AdminService` | MODIFY | AC-3 | Cross-tenant operator views and mutations, composed over the tenant/url/usage aggregates. Matched requirement keywords: [admin]. |
| `RedirectController` | EXTEND | AC-2, AC-4, AC-5 | Public, unauthenticated GET /{shortCode} redirect (302) hot path. Matched requirement keywords: [redirect]. |
| `UrlShortenerController` | EXTEND | AC-3, AC-4 | Tenant-authenticated /api/v1/urls management API: create, list, stats, update, reactivate, deactivate. Matched requirement keywords: [stats, list]. |
| `AdminController` | EXTEND | AC-3 | Operator-only /api/v1/admin/** endpoints (X-Admin-Key). Matched requirement keywords: [admin]. |

### Unaffected areas

- Authentication and authorization filter chain: no requirement keyword matched its components [SecurityConfig, ApiKeyAuthenticationFilter].
- Billing and invoicing: no requirement keyword matched its components [BillingService, InvoiceService, UsageMeteringService, InvoicePdfGenerator].
- Per-tenant rate limiting: no requirement keyword matched its components [TenantRateLimiterService, RateLimitFilter].

_Source: deterministic rule-based demo logic. No model was called._

## 3. Implementation plan

| ID | Task | Depends on | Components | Criteria |
|---|---|---|---|---|
| T-1 | **Change UrlMapping**<br>MODIFY UrlMapping: JPA entity for a short link: shortCode, originalUrl, tenantId, expiry, click count, active flag (soft delete). Matched requirement keywords: [expir, click count, link]. | - | UrlMapping | AC-1, AC-2, AC-3, AC-4 |
| T-2 | **Change UrlShortenerServiceImpl**<br>MODIFY UrlShortenerServiceImpl: Core business rules: create (custom alias / generated code), update, deactivate, reactivate, resolve-and-record-hit. Matched requirement keywords: [short link, expir, link]. | T-1 | UrlShortenerServiceImpl | AC-1, AC-2, AC-3 |
| T-3 | **Change CachedShortCodeLookup**<br>MODIFY CachedShortCodeLookup: Caffeine-cached short-code resolution on the redirect hot path, with eviction on change. Matched requirement keywords: [redirect]. | T-1 | CachedShortCodeLookup | AC-2, AC-4, AC-5 |
| T-4 | **Change ExpiredUrlCleanupService**<br>MODIFY ExpiredUrlCleanupService: Scheduled batch deactivation of expired links. Matched requirement keywords: [expir]. | T-1 | ExpiredUrlCleanupService | AC-2 |
| T-5 | **Change AdminService**<br>MODIFY AdminService: Cross-tenant operator views and mutations, composed over the tenant/url/usage aggregates. Matched requirement keywords: [admin]. | T-1 | AdminService | AC-3 |
| T-6 | **Change RedirectController**<br>EXTEND RedirectController: Public, unauthenticated GET /{shortCode} redirect (302) hot path. Matched requirement keywords: [redirect]. | T-2, T-3, T-4, T-5 | RedirectController | AC-2, AC-4, AC-5 |
| T-7 | **Change UrlShortenerController**<br>EXTEND UrlShortenerController: Tenant-authenticated /api/v1/urls management API: create, list, stats, update, reactivate, deactivate. Matched requirement keywords: [stats, list]. | T-2, T-3, T-4, T-5 | UrlShortenerController | AC-3, AC-4 |
| T-8 | **Change AdminController**<br>EXTEND AdminController: Operator-only /api/v1/admin/** endpoints (X-Admin-Key). Matched requirement keywords: [admin]. | T-2, T-3, T-4, T-5 | AdminController | AC-3 |
| T-9 | **Write and extend automated tests**<br>Add unit, slice, and integration tests per the TESTS artifact so every acceptance criterion has executable proof; keep the existing suite green. | T-1, T-2, T-3, T-4, T-5, T-6, T-7, T-8 | UrlMapping, UrlShortenerServiceImpl, CachedShortCodeLookup, ExpiredUrlCleanupService, AdminService, RedirectController, UrlShortenerController, AdminController | AC-1, AC-2, AC-3, AC-4, AC-5 |
| T-10 | **Update documentation**<br>Update README.md (endpoints, curl walkthrough), ARCHITECTURE.md (design decisions) and ENGINEERING_SUMMARY.md (what was generated, edited, rejected, and why). | T-9 | - | - |
| T-11 | **Verify end to end**<br>Run mvn clean verify (all tests plus the 80% JaCoCo line-coverage gate) and the Postman collection against a freshly started dev instance; record results. | T-9, T-10 | - | - |

_Source: deterministic rule-based demo logic. No model was called._

## 4. Test plan

| ID | Level | Target | Scenario | Criteria |
|---|---|---|---|---|
| TC-1 | UNIT | `src/test/java/com/schwab/urlshortener/service/UrlShortenerServiceImplTest.java` | **Verify AC-1 (FR-1)**<br>Given an authenticated tenant (X-API-Key) using the management API, when the behavior described in FR-1 is exercised, then the system observably satisfies: "Tenants must be able to set an optional maximum click limit when creating a short link". | AC-1 |
| TC-2 | INTEGRATION | `src/test/java/com/schwab/urlshortener/integration/UrlShortenerIntegrationTest.java` | **Verify AC-2 (FR-2)**<br>Given an authenticated tenant (X-API-Key) using the management API, when the behavior described in FR-2 is exercised, then the system observably satisfies: "Once the limit is reached, the redirect should stop working and return 410 Gone, like an expired link". | AC-2 |
| TC-3 | INTEGRATION | `src/test/java/com/schwab/urlshortener/controller/UrlShortenerControllerWebMvcTest.java` | **Verify AC-3 (FR-3)**<br>Given an operator authenticated with X-Admin-Key, when the behavior described in FR-3 is exercised, then the system observably satisfies: "Admins should be able to see the limit when listing a tenant's links". | AC-3 |
| TC-4 | INTEGRATION | `src/test/java/com/schwab/urlshortener/integration/UrlShortenerIntegrationTest.java` | **Verify AC-4 (NFR-1)**<br>Given the agreed measurement conditions for NFR-1, when the quality attribute is measured or verified, then the result meets: "The click count shown in stats must stay accurate under concurrent redirects". | AC-4 |
| TC-5 | INTEGRATION | `src/test/java/com/schwab/urlshortener/integration/UrlShortenerIntegrationTest.java` | **Verify AC-5 (NFR-2)**<br>Given the agreed measurement conditions for NFR-2, when the quality attribute is measured or verified, then the result meets: "The redirect path must stay fast". | AC-5 |
| TC-6 | INTEGRATION | `src/test/java/com/schwab/urlshortener/integration/UrlShortenerIntegrationTest.java` | **Pin assumption AMB-1**<br>Asserts the behavior assumed for AMB-1: p95 latency under 200 ms at current integration-test load; no new load-test infrastructure is in scope. Update this test if the requirement owner answers differently. | - |
| TC-7 | INTEGRATION | `mvn clean verify` | **Existing suite stays green**<br>All pre-existing tests pass unchanged and line coverage stays at or above the 80% JaCoCo gate. | - |

_Source: deterministic rule-based demo logic. No model was called._

## 5. Risk assessment

| ID | Severity | Risk | Mitigation | Components |
|---|---|---|---|---|
| R-1 | MEDIUM | Entity change is applied by Hibernate ddl-auto=update with no migration tooling, so a real persistent database would need a hand-written migration. | Keep schema changes additive (new nullable columns); record the migration need in README §10. | UrlMapping |
| R-2 | MEDIUM | Extra work on the public redirect hot path adds latency to every redirect. | Keep synchronous redirect work to indexed lookups and the atomic click UPDATE; move anything else to the bounded async executor; evict CachedShortCodeLookup on change. | CachedShortCodeLookup, RedirectController |
| R-3 | MEDIUM | 1 assumption(s) may not match the requirement owner's intent (AMB-1). | Confirm AMB-1 with the requirement owner before implementation starts; the TESTS artifact pins each assumption in a test so a changed answer has one obvious place to update. | - |
| R-4 | LOW | New code lowers line coverage below the 80% JaCoCo gate, failing mvn verify. | Write tests per the TESTS artifact alongside each task, not after. | - |

_Source: deterministic quality gate (never model-generated)._

## 6. Quality gate

**PASSED**: 0 blocker(s), 0 warning(s).

| Metric | Value |
|---|---|
| Acceptance criteria | 5 |
| Criteria covered by a task | 5 |
| Criteria covered by a test | 5 |
| Impacted components | 8 |
| Tasks | 11 |
| Test cases | 7 |
| Risks | 4 |
| Open ambiguities | 1 |

| Severity | Rule | Finding |
|---|---|---|
| INFO | `provenance.execution-mode` | Stages 1-5 were produced by deterministic rule-based templates (DETERMINISTIC_DEMO), not by a language model. Content reflects keyword rules, not understanding of the requirement. |

## Human review

**CHANGES_REQUESTED** by eng-lead at 2026-11-01T22:20:35.692021Z.

> Confirm the p95 latency target (AMB-1) with product before starting T-1.
