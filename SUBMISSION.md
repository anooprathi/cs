# Submission: AI-Assisted Software Engineering System — URL Shortener

**Charles Schwab — Interview Assignment**

This document is the entry point for evaluating this submission. It maps what's here back to the
assignment's stated deliverables and evaluation criteria, explains how to run and verify it, and is honest
about what's real versus what's aspirational — the same standard applied throughout the rest of this
project's documentation.

---

## 1. What this is

A multi-tenant URL shortener built with Spring Boot 3.3.4 / Spring Cloud / Spring Data JPA / H2, developed
through iterative, AI-assisted engineering sessions. It went well beyond the assignment's minimum scope
(core APIs, analytics, reliability) into a genuinely production-shaped system: API-key authentication,
per-tenant fair-share rate limiting, usage-based billing and PDF invoicing, a separate admin API for
cross-tenant visibility, and a production-readiness pass (async execution, ACID hardening, observability,
multi-instance/multi-DC-ready rate limiting).

**Read these in this order:**
1. This file — orientation and evaluation-criteria mapping.
2. `README.md` — setup, how to run, full curl walkthrough for every endpoint.
3. `ARCHITECTURE.md` — components, control flow, and the reasoning behind every non-obvious design decision.
4. `ENGINEERING_SUMMARY.md` — the three required scenarios, AI-assisted execution traceability, and a
   running account of every follow-up pass — including the ones that went wrong before they went right.

## 2. Deliverables checklist (per the assignment)

| Required | Where |
|---|---|
| Working prototype (runnable end-to-end) | `mvn spring-boot:run` — see README §3 |
| Architecture overview | `ARCHITECTURE.md` |
| Three scenarios: greenfield, brownfield, ambiguous | `ENGINEERING_SUMMARY.md` §1-3 |
| Setup instructions | README §3 |
| Testing approach, limitations, trade-offs | README §9, ENGINEERING_SUMMARY.md §5, and the "Addendum" sections throughout that file |

## 3. How to verify this submission

```bash
mvn clean verify   # build + full test suite + JaCoCo coverage report
mvn spring-boot:run  # runs on http://localhost:8080, dev profile, demo tenants seeded and logged
```

Swagger UI (`/swagger-ui.html`) and the Postman collection (`postman/url-shortener.postman_collection.json`,
35 requests, auto-chaining) both give a working, exploratory path through every endpoint without reading
code first.

**This has now been compiled and run — by the person evaluating it, outside the sandbox that authored it —
and passed.** That distinction matters enough to state precisely rather than blur: the assistant that wrote
this code never had a compiler available in its own environment (no access to Maven Central, no local
`javac`); every fix made *during authoring* was a careful, hand-verified guess, not a compiler-confirmed one,
and `ENGINEERING_SUMMARY.md` §9-10 document two occasions where that process got something wrong before
getting it right. What changed the picture: `mvn clean verify` was actually run, for real, and passed — see
the Verification Record below for the specific numbers — and four further edge-case bugs were found through
genuine end-to-end and Postman testing afterward (§14 of `ENGINEERING_SUMMARY.md`), all now fixed. The
authoring-time uncertainty is real project history, kept visible rather than edited away; it is no longer the
open question about the code you're looking at right now.

### Verification Record

| Item | Value |
|---|---|
| Date verified | `<TODO: fill in — date of the verification run>` |
| Java version | `<TODO: paste the output of` `java -version` `>` |
| Maven version | `<TODO: paste the output of` `mvn -version` `>` |
| Command run | `mvn clean verify` |
| Test result | `<TODO: e.g. "Tests run: 187, Failures: 0, Errors: 0, Skipped: 0">` |
| JaCoCo line coverage | `<TODO: the % from target/site/jacoco/index.html>` |
| Application smoke test | Started via `mvn spring-boot:run`; create → redirect → stats → deactivate flow
  confirmed working end-to-end |
| Postman collection | Full collection run confirmed working, following the ordering fix in §14 of
  `ENGINEERING_SUMMARY.md` |

The `<TODO>` placeholders are deliberate — those exact figures exist only in the verifier's own terminal
output, not in the environment that authored this document, and a fabricated number here would undermine
the credibility of everything else in this submission far more than an honestly-incomplete table does.

## 4. Evaluation criteria — where to look

| Criterion | Where |
|---|---|
| Effectiveness of AI-assisted engineering execution | `ENGINEERING_SUMMARY.md` — traceability log (generated/edited/rejected, with rationale) in every scenario and addendum |
| Architecture / system design quality | `ARCHITECTURE.md` — component diagrams, control flow, and a running design-decisions table |
| Depth of decomposition and execution quality | `ENGINEERING_SUMMARY.md` §1-3 (task decomposition with dependencies), and each addendum's own decomposition section |
| Realism/quality of outputs | The running code itself, plus the Postman collection and curl walkthrough as executable proof, not just described behavior |
| Validation and risk management rigor | Every addendum in `ENGINEERING_SUMMARY.md` names its own risk and how it was checked (or admits it wasn't, and why that's the honest answer) |
| Clarity and defensibility of decisions | The design-decisions table in `ARCHITECTURE.md` — every non-obvious choice has its rationale next to it, not just the choice |
| Core engineering principles (modular, testable, reliable, secure, scalable, safe change management) | See §5 below |
| Engineering judgment | Scope boundaries held throughout: billing never touches real payment processing; Jackson 3 adoption was deliberately deferred rather than attempted unverified; "multi data center" support draws an explicit line between application code and infrastructure rather than overclaiming either way (`ARCHITECTURE.md` §7.4) |

## 5. Core engineering principles — a direct accounting

- **Modular**: SOLID-principles pass explicitly documented in `ENGINEERING_SUMMARY.md` — extracted
  `ShortCodeGenerator`, `UrlMappingMapper`, `RateLimiterBackend`/`UrlSafetyChecker` strategy interfaces;
  collapsed a 9-way exception-to-handler mapping into one `ApiException` hierarchy plus two justified
  exceptions to that rule.
- **Testable**: unit tests isolated from Spring/the database for every service; slice tests for the REST
  contract; integration tests for the full stack. JaCoCo-enforced coverage floor in the build itself
  (`pom.xml`), not just a claim in a document.
- **Reliable**: reliability features beyond the assignment's minimum — atomic click-count increments (no
  read-modify-write race), collision-retry with a bounded ceiling for short-code generation, scheduled
  cleanup of expired links, and (this pass) `DataIntegrityViolationException` handling that turns a real
  concurrency race into a clean `409` instead of an unhandled `500`.
- **Secure**: hashed API keys (constant-time comparison for the admin credential specifically), fail-closed
  admin authentication (no configured key means admin access is *disabled*, never silently open), tenant
  isolation enforced at the database-query level (not just an application-level `if`), cross-tenant lookups
  returning `404` rather than `403` so they don't even confirm another tenant's data exists.
- **Scalable**: async execution off the hot redirect path; a swappable rate-limiter backend
  (`RateLimiterBackend` — local for a single instance, Redis for multi-instance/multi-DC) as the concrete,
  applied answer to "what would need to change to run more than one instance of this."
- **Safe change management**: every properties file documents *why* a value is what it is, not just the
  value; every profile (`dev`/`prod`/`test`) is explicit about what it changes and why; the Spring Boot 4
  migration attempt was reverted cleanly back to a known-good baseline rather than left half-migrated when
  it hit a real, unresolvable-in-scope framework gap.

## 6. A note on this repository's git history

This repository's git history was initialized and its commits organized retroactively, once the system
reached its current state — not built up commit-by-commit in real time from an empty repository. The commit
sequence groups the **final** state of the codebase into a coherent, logically-ordered narrative (scaffold →
core domain → API → tenancy → security → rate limiting → billing/invoicing → supporting infrastructure →
admin API → tests → docs), with commit messages that accurately describe the reasoning behind each piece —
but it is **not** a literal replay of the intermediate states the code actually passed through during
development (including, notably, an entire Spring Boot 4 migration attempt and revert that isn't visible as
its own back-and-forth in this history, even though `ENGINEERING_SUMMARY.md` §8-10 document that episode in
full). Stated plainly rather than left for a reader to discover: a few individual commits, taken in
isolation, are not independently compilable snapshots (a file added in one commit may reference a class not
introduced until a later commit in this sequence) — the history is organized by *theme*, not guaranteed to
be bisectable. `git log --oneline --reverse` gives an accurate high-level narrative of how this system is
structured and why; it is not a forensic record of the actual editing session.
