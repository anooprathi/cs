# Implementation Plan — Agentic SDLC Orchestration Layer

**Status:** implemented. This plan was written before any code for the change; §8 at the end is the
post-implementation record of what actually shipped and how it was validated (kept separate so the
original plan stays readable as a plan).

## 1. Goal

Add a working **agentic SDLC orchestration layer** to this repository that turns a free-text engineering
requirement into a set of **reviewable artifacts**: a normalized requirement spec, a codebase impact
analysis, a dependency-ordered implementation plan, a test plan, a risk assessment, and a deterministic
quality-gate review. A human approves or sends the result back. The existing URL-shortener behavior and its
236 tests must be unaffected.

**Hard honesty constraint (from the request):** never claim a capability works unless it's implemented and
validated, and keep **real LLM execution** clearly separate from **deterministic demo behavior** everywhere:
code, API responses, stored artifacts, and docs.

## 2. Current architecture (summary of what already exists)

- **Stack:** Spring Boot 3.3.4, Java 17 target (built on JDK 21), Spring Data JPA + in-memory H2, Spring
  Security (stateless header auth), Spring Cloud OpenFeign, Caffeine, Bucket4j, springdoc, Lombok,
  JUnit 5 / Mockito / MockMvc, JaCoCo with an 80% line-coverage gate on `mvn verify`.
- **Two entry points into one service layer:** `RedirectController` (public `GET /{shortCode}`) and
  `UrlShortenerController` (tenant-authenticated `/api/v1/urls/**`). Tenancy is enforced in the repository
  queries themselves. Cross-tenant access returns 404.
- **Two separate identities:** tenant `X-API-Key` (`ApiKeyAuthenticationFilter` → `RateLimitFilter`), and an
  operator `X-Admin-Key` (`AdminAuthenticationFilter`, fails closed when no hash is configured).
  `SecurityConfig` ends in `denyAll()`, so a new route only works if it's listed explicitly.
- **Cross-cutting conventions:** one `GlobalExceptionHandler` and an `ApiException` hierarchy for
  status-plus-message errors; type-safe `@ConfigurationProperties` records; feature flags for anything that
  calls an external service (`app.url-safety-check.enabled=false`, fail-open); dev/prod/test profiles
  required explicitly (`RequiredProfileGuard`).
- **Existing AI-assisted engineering documentation:** `ENGINEERING_SUMMARY.md` (scenarios, traceability of
  generated/edited/rejected), `ARCHITECTURE.md`, `SUBMISSION.md`, and package-level `CLAUDE.md` files. These
  record how AI was used *to build* the system. Nothing in the system itself *performs* agentic SDLC work
  today.

## 3. Reusable components vs. missing capabilities

| Need | Reuse | Missing (to build) |
|---|---|---|
| Who may run the orchestrator | `AdminAuthenticationFilter` / `ROLE_ADMIN` (operator identity, fails closed) | One explicit `SecurityConfig` rule for `/api/v1/agentic/**` |
| Error envelope | `GlobalExceptionHandler` + `ApiException` | Two subclasses: run-not-found (404), invalid review state (409); LLM-unavailable (503) |
| Persistence | Spring Data JPA + H2, same entity/Lombok idioms as `Invoice` | `SdlcRun` and `SdlcArtifact` entities + repositories |
| Config | `@ConfigurationProperties` records, auto-scanned | `AgenticProperties` (`app.agentic.*`) |
| External-call pattern | Feature-flagged, off by default, like `FeignUrlSafetyChecker` | LLM client off by default; when off, LLM mode is **refused**, not silently swapped for demo mode |
| Swagger "admin key" wiring | `OpenApiConfig.ADMIN_KEY_SCHEME` | `@SecurityRequirement` on the new controller |
| Paging | `PageResponse`, `@PageableDefault` | — |
| Codebase knowledge for impact analysis | — | A curated **component catalog** of this repo (resource JSON), with a test that every path in it exists, so it can't silently drift |
| Orchestration, agents, validation, rendering | — | All new (`agentic` package) |
| Real LLM calls | — | `AnthropicLlmClient` using the official `anthropic-java` SDK with structured outputs |

## 4. Design

**Package:** `com.schwab.urlshortener.agentic` (with `model/`, `agent/`, `llm/`, `review/` subpackages).

**Pipeline** (each stage's output feeds the next; the order is declared by `dependsOn` and validated with a
topological sort at startup, so a mis-wired stage fails fast at boot, not mid-run):

```
REQUIREMENTS  -> RequirementSpec     (functional/NFR statements, acceptance criteria, ambiguities+assumptions, out of scope)
IMPACT        -> ImpactAnalysis      (catalog components touched, why, change type; proposed new components)
PLAN          -> ImplementationPlan  (tasks with dependsOn, component refs, acceptance-criteria refs)
TESTS         -> TestPlan            (test cases mapped to acceptance criteria and levels)
RISKS         -> RiskAssessment      (risk, severity, mitigation)
REVIEW        -> QualityGateReport   (ALWAYS deterministic: traceability and consistency checks)
```

**Two execution modes, selected per run and stamped on the run and on every artifact:**

- `DETERMINISTIC_DEMO`: the five generative stages run rule-based, template-driven logic (keyword
  classification, a vague-term lexicon, catalog keyword matching, layer-ordered task generation, rule-based
  risks). Same input gives the same output, no network, no cost. **It is a demo of the orchestration
  mechanics, not intelligence**, and every rendered artifact says so in its header.
- `LLM`: each generative stage calls Claude through the official Anthropic Java SDK with **structured outputs**
  (the stage's Java record is the JSON schema), so the response is parsed into the same typed artifact as
  demo mode. Off by default (`app.agentic.llm.enabled=false`); the API key comes only from the
  `ANTHROPIC_API_KEY` environment variable and is never logged or stored. If LLM mode is requested while it's
  disabled, the request is rejected with `503`. It never quietly falls back to demo output.

**Guardrails (deterministic, in both modes):** the `REVIEW` stage never uses the LLM. It checks:
acceptance-criteria IDs are unique and non-empty; every impacted component exists in the catalog (catches
hallucinated components in LLM mode); task IDs are unique, every `dependsOn` resolves, and the task graph is
acyclic; every acceptance criterion is covered by at least one task **and** at least one test case; every
impacted component has a task; every HIGH risk has a mitigation; and every ambiguity has a stated assumption.
Findings are `BLOCKER` / `WARNING` / `INFO`; any BLOCKER fails the gate.

**Human in the loop:** a run ends in `AWAITING_REVIEW` (or `FAILED`). `POST .../review` records `APPROVED`
or `CHANGES_REQUESTED` with reviewer and comment. **Approval is refused (409) while the gate has blockers.**
Nothing the orchestrator produces is applied to the codebase automatically. Artifacts are proposals.

**API** (operator identity, `X-Admin-Key`; under `/api/v1/agentic`):

| Method | Path | Purpose |
|---|---|---|
| GET | `/capabilities` | Which modes are actually available right now, model id, catalog size |
| POST | `/runs` | Run the pipeline on `{title, requirement, mode?}` → `201` with run + artifact summaries |
| GET | `/runs` | Paged list, newest first |
| GET | `/runs/{id}` | One run, with every stage's status/mode/duration/tokens |
| GET | `/runs/{id}/artifacts/{stage}` | One artifact: typed JSON + rendered Markdown |
| GET | `/runs/{id}/report.md` | All artifacts as one reviewable Markdown document |
| POST | `/runs/{id}/review` | Human decision (`APPROVED` / `CHANGES_REQUESTED`) |

Runs execute **synchronously** in the request. This is fine for demo mode (milliseconds). In LLM mode it means
five sequential model calls. That's an accepted prototype limitation, recorded in §7.

## 5. Tasks, dependencies, acceptance criteria

| # | Task | Depends on | Acceptance criteria |
|---|---|---|---|
| T1 | Add `anthropic-java` dependency; confirm it resolves and coexists with Boot 3.3.4's Jackson/OkHttp | — | `mvn clean verify` still green with 236 tests before any new code uses it |
| T2 | `AgenticProperties` + config defaults in `application.properties` (LLM disabled), test profile settings | — | App boots in dev/test with no `ANTHROPIC_API_KEY`; property binding tested |
| T3 | Artifact model records (`RequirementSpec`, `ImpactAnalysis`, `ImplementationPlan`, `TestPlan`, `RiskAssessment`, `QualityGateReport`) + `SdlcStage`, `ExecutionMode` | — | Records are immutable, JSON-serializable, usable as structured-output schemas |
| T4 | Component catalog resource + `ComponentCatalog` loader | T3 | Test: every catalog `path` exists under the repo; names unique |
| T5 | `LlmClient` interface + `AnthropicLlmClient` (structured outputs, refusal / max-tokens handling, token usage capture), created only when enabled | T1, T2 | Unit tests at the SDK boundary (mocked `AnthropicClient`): request has model/max tokens/system/schema; refusal → stage failure with clear reason; usage recorded |
| T6 | Five generative agents, each with a deterministic implementation and LLM prompts | T3, T4, T5 | Unit test per agent on a realistic requirement (ambiguity detection, catalog matching, acyclic layered plan, AC coverage, rule-based risks); determinism test (same input → identical output) |
| T7 | `QualityGateReviewer` (deterministic guardrails) | T3, T4 | Unit tests: clean artifacts pass; each rule fires on a crafted violation (unknown component, dangling dependency, cycle, uncovered AC, unmitigated HIGH risk) |
| T8 | `MarkdownRenderer` for every artifact + combined report with prominent execution-mode banner | T3 | Tests: banner text differs per mode; report contains every stage |
| T9 | Entities `SdlcRun`/`SdlcArtifact`, repositories, `SdlcOrchestrator` (stage ordering, per-stage timing/status, failure handling), `SdlcRunService` (review state machine) | T5–T8 | Tests: full demo run persists 6 artifacts + `AWAITING_REVIEW`; LLM stage failure → run `FAILED`, later stages skipped, error recorded; LLM mode while disabled → 503; approve with blockers → 409 |
| T10 | Exceptions + `AgenticController` + `SecurityConfig` rule + Swagger admin scheme | T9 | Integration tests through MockMvc: 401 without key, 403 with a tenant key, 201 with admin key; report.md is `text/markdown`; existing URL-shortener tests unchanged and green |
| T11 | Docs: README section, ARCHITECTURE section, ENGINEERING_SUMMARY addendum, SUBMISSION mapping, package `CLAUDE.md`, sample generated report committed from a **real** demo-mode run | T10 | Every claim in the docs corresponds to a test or an executed command; LLM-mode validation status stated exactly as achieved |
| T12 | Full `mvn clean verify` + run the app and exercise the endpoints by hand | T11 | 0 failures, JaCoCo gate met; demo run reproduced via curl against a running dev instance |

## 6. Assumptions

- The assignment brief's exact wording isn't in the repository. Its intent is taken from the request:
  "transform engineering requirements into reviewable artifacts" via an agentic SDLC layer, inside this repo's
  existing stack.
- "Reviewable" means a human-readable artifact (Markdown) plus the structured form (JSON) a tool could consume,
  with an explicit human decision recorded. It does **not** mean the system opens PRs or edits code.
- The orchestrator is an internal engineering capability, so it belongs behind the existing **operator**
  identity (`X-Admin-Key`), not tenant keys, and isn't tenant-scoped or tenant-rate-limited.
- Default model is `claude-opus-5` (configurable via `app.agentic.llm.model`). No model was specified, and
  this is the current default for new Claude integrations.
- H2 in-memory persistence is acceptable for runs/artifacts, consistent with the rest of the prototype.

## 7. Risks and mitigations

| Risk | Mitigation |
|---|---|
| New dependency (`anthropic-java`) conflicts with Boot 3.3.4's managed Jackson/OkHttp/Kotlin | T1 runs the full baseline suite immediately after adding it, before any code depends on it |
| Demo mode mistaken for "AI" | Mode stamped on run, on every artifact, in API responses and as a banner in every rendered document; docs say plainly that it's rule-based |
| LLM mode claimed as working without live validation | Live calls can't be validated without spending the owner's API credit. The SDK boundary is unit-tested, and an opt-in live smoke test is gated on an env var. Docs state exactly which of these ran |
| LLM hallucinates components/IDs, or returns inconsistent references | Structured outputs constrain shape; the deterministic quality gate checks references against the catalog and the other artifacts; approval blocked on blockers |
| Prompt injection via requirement text | Requirement wrapped in delimiters and declared as data in the system prompt. LLM output is only ever parsed as typed data, never executed, and goes nowhere without human approval. Length capped (`@Size`) |
| Requirement text sent to a third party | LLM mode off by default; enabling it is an explicit operator decision; documented |
| Long synchronous requests in LLM mode | Accepted for the prototype and documented. The roadmap item is async execution with polling, using the existing bounded-executor pattern (`AsyncConfig`) |
| Catalog drifts from the real code | Test fails the build if any catalog path stops existing |
| Coverage gate drops below 80% with the new code | Unit + integration tests per task. `AnthropicLlmClient`'s live path is mocked at the SDK boundary, not left uncovered |
| Touching `SecurityConfig` regresses existing auth | One additive matcher line before the `/api/v1/**` rule; the existing security/integration tests must stay green |

## 8. Post-implementation record

See `ENGINEERING_SUMMARY.md` §17 for the full execution log (what was generated/edited/rejected, and the
issues found while running it) and the verification numbers. In brief:

| Task | Outcome |
|---|---|
| T1-T10 | Done. All acceptance criteria met as written, each with tests |
| T11 | Done. One deviation: the Postman collection was not extended (curl walkthrough in README §12.2 instead) |
| T12 | Done: `mvn clean verify` 322 tests, 0 failures, 1 skipped (opt-in live test); 96.1% line coverage; manual curl run against a live dev instance |
| Risk "LLM mode claimed without live validation" | Handled by **not claiming it**: the live smoke test exists but was not run, and README §12.4 says so |

## 9. Follow-on: the SDLC workflow orchestrator

This plan covers the single-pass agentic layer (`/api/v1/agentic`, README §12). Two follow-up briefs extended
it into the full orchestrator (`workflow/`, `/api/v1/workflows`, README §13). That module's requirement →
implementation mapping, including validation evidence for every requirement, is `SUBMISSION_NOTES.md`. Its
design is `ARCHITECTURE.md` §9 and `docs/architecture.md`, and its execution log is `ENGINEERING_SUMMARY.md` §18.

The orchestrator reuses this plan's T3-T7 agents unchanged, as the generative step inside its stage agents.
Two of this plan's stated limitations no longer hold for the orchestrator:
- §12.5 "Artifacts are proposals only": the orchestrator applies patches in isolated git worktrees, runs
  validation and test commands there, and promotes approved patches to a local branch.
- §12.5 "Synchronous runs": orchestrator runs are asynchronous with polling.

Both still hold for `/api/v1/agentic`, which is kept as the lightweight preview.
