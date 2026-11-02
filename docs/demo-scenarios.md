# Demo scenarios

Three runnable end-to-end scenarios (`workflow/scenario/ScenarioService.java`). Each one drives the real workflow
service (the same code the API uses) through the standard 7-stage graph, and ends with evidence checks computed from
the run's audit log, plus a final engineering summary and an execution timeline. **Demo mode needs no API
credentials.** The reports from a real run are committed under [`docs/demo-output/`](demo-output/).

Approvals in scenarios are made by the scripted reviewer `scenario-reviewer (scripted)` through the normal
version-bound decision API.

## Run them

```bash
# All three, as a CLI. Writes target/workflow-scenarios/<name>.md, prints a verdict per scenario,
# and exits 0 only if every check passed. --server.port=0 avoids clashing with a running instance.
mvn spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--app.workflow.cli.scenario=all --server.port=0"

# One scenario
mvn spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--app.workflow.cli.scenario=ambiguous --server.port=0"

# Against a running dev instance, through the API (returns Markdown)
curl -s -X POST "http://localhost:8080/api/v1/workflows/scenarios/greenfield" -H "X-Admin-Key: admin-dev-only-key-change-me"

# As automated tests (real git worktrees cloned from this repository)
mvn test -Dtest=ScenarioEndToEndTest -Djacoco.skip=true
```

PowerShell needs the whole `-D` argument quoted:
`mvn spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--app.workflow.cli.scenario=all --server.port=0"`

### Real LLM-backed execution (separate command; costs money, not run in this repository's verification)

```bash
export ANTHROPIC_API_KEY=...           # read by the Anthropic SDK only; never logged or stored
export APP_AGENTIC_LLM_ENABLED=true    # optional: APP_AGENTIC_LLM_MODEL=claude-opus-5
mvn spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--app.workflow.cli.scenario=greenfield --app.workflow.cli.mode=LLM --server.port=0"
```
In LLM mode, the model's output decides what the run contains, so the evidence checks (which were calibrated on demo
behavior, e.g. "5 tasks reused") may legitimately fail. The injected-fault step is skipped, because fault injection exists
only in demo mode.

## 1. Greenfield: click-limited short links

**Requirement:**
> Tenants must be able to set an optional maximum click limit when creating a short link. When the click limit is
> reached, the redirect must return 410 Gone. A click limit below 1 must be rejected with 400 Bad Request. The stats
> endpoint must show the remaining clicks for each link.

| Step | What happens |
|---|---|
| Normalize | 4 FR → 4 acceptance criteria, 0 ambiguities, so no clarification checkpoint |
| Task graph | `[requirements] [design] [implementation] [tests, security, docs] [release]` |
| Agent execution | design maps to real components. Implementation applies the deterministic `click-limit` **code recipe** to the worktree's real source: `maxClicks` on the entity and request DTO, an atomic limit inside the click-count `UPDATE`, 410 Gone when the limit is reached, `remainingClicks` on stats, a codemod over the existing test call sites, and a new `ClickLimitIntegrationTest`. It runs `git diff --cached --check` and declares the interface contract (3 API changes, `ALTER TABLE url_mapping ADD COLUMN max_clicks INTEGER NULL`) |
| **Real tests** | the run uses `testExecution: COMMAND`, so `tests` runs `mvn -B -o test -Dtest=ClickLimitIntegrationTest,<plan classes>` in the patched worktree, and the evidence check requires the patch's own test class to pass |
| **Parallel execution** | `tests`, `security`, `docs` run at the same time; `release` starts only after all three succeed |
| **Recoverable failure** | an injected transient fault on `tests` is retried with backoff and recovers, and the MTTR is reported |
| Human approval | release readiness waits; the reviewer approves `readiness-report@v1` (bound to its hash) |
| Promotion | the approved `change-patch@v1` is committed to local branch `workflow/run-N-v1` in the sandbox clone (not pushed) |
| **Upstream-change re-planning** | the requirement is reformatted as bullets (no content change). Resume re-runs only `requirements` and `security` (the raw-requirement readers); the other 5 tasks are reused, and the release approval stays VALID |

## 2. Brownfield: refuse reactivation of expired links

**Requirement** (enhances existing `PATCH /api/v1/urls/{code}/reactivate` behavior):
> Tenants reactivating a deactivated link whose expiry is in the past must receive 409 Conflict instead of a
> reactivated link whose next redirect returns 410 Gone. The update endpoint must still allow a tenant to extend the
> expiry and then reactivate the link. Reactivation of links that have not expired must keep its current behavior.
> When a reactivation is refused, charge the card on file a one dollar reactivation fee.

| Step | What happens |
|---|---|
| Impact analysis | names the existing modules (service, API, DTOs, persistence) and their existing tests |
| Code change | the `expired-reactivation` recipe adds a 409 guard to `UrlShortenerServiceImpl.reactivate`, a new `ExpiredLinkReactivationException`, and `ExpiredLinkReactivationIntegrationTest` (expired → 409; extend then reactivate → 200; unexpired → 200). These run for real (`testExecution: COMMAND`). Contract: one new response status, no schema change |
| **Rejected gate** | security's exit gate fails on **POL-SCOPE** (payment processing is out of scope), with no retry; `release` is SKIPPED, while `tests` and `docs` still succeed |
| **Upstream-change re-planning** | the product owner removes the payment clause; resume invalidates the stages affected by the changed spec and re-runs them |
| Human approval | release readiness is approved after re-planning |

## 3. Ambiguous requirement

**Requirement:** `Make links safer and analytics more useful.`

| Step | What happens |
|---|---|
| Normalize | ambiguities found: "safer", "useful", "analytics", no actor, no failure behavior. Each gets a question and a proposed assumption |
| **Clarification checkpoint** | requirement analysis requests approval; `design` and everything after it stay PENDING |
| **Rejected gate (human)** | the reviewer rejects the assumptions with a clarification; this is never retried automatically |
| **Upstream-change re-planning** | the clarified requirement is applied; the spec is regenerated, and the old rejection goes **STALE** because it was bound to `requirement-spec@v1` |
| Resolution | the remaining assumption is approved; the run executes, then release readiness is approved |
| Patch | no recipe matches the clarified requirement, so demo mode writes a **change record** (`kind: DOCUMENT`), and readiness check RR-9 reports that it isn't product code. Producing code for an arbitrary requirement needs LLM mode |

## What the four required demonstrations look like

| Demonstration | Where | Evidence (from the audit log) |
|---|---|---|
| Parallel execution | all three | `tasksThatRanConcurrently` contains `tests`, `security`, `docs`; the timeline span chart shows overlap |
| Recoverable failure | greenfield | `TASK_ATTEMPT_FAILED` → `TASK_RETRY_SCHEDULED` → `RUNNING→SUCCEEDED`; MTTR > 0 |
| Rejected gate | brownfield (policy gate), ambiguous (human rejection) | `EXIT_GATE_FAILED` with `SEC-EXIT-2`; `REJECTED` |
| Upstream-change re-planning | all three | `REQUIREMENT_UPDATED` → `TASK_INVALIDATED` → re-executed vs `TASK_REUSED` tasks; `APPROVAL_STALE` (ambiguous) |
