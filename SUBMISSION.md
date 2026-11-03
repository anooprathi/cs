# Submission: Agentic SDLC System on a Multi-Tenant URL Shortener

**Charles Schwab, Agentic-Proficient Software Engineer assignment.** This is the single entry point. It says
what to run, what to read, what is real and what is simulated, and how this tree was verified.

---

## 1. What this is

Two things in one Spring Boot 3.3 service:

1. **An agentic SDLC orchestrator** (`com.schwab.urlshortener.workflow`, `/api/v1/workflows`). This is what
   the assignment asks for. It takes a free-text engineering requirement through an explicit 7-stage
   dependency graph (requirements → design → implementation → tests ∥ security ∥ docs → release) and
   carries it to a reviewed, tested patch on a local branch. Along the way it uses:
   - entry and exit gates;
   - persistent state with resume;
   - versioned artifacts with decision lineage;
   - human approvals bound to exact artifact versions;
   - bounded retries and a defined fallback;
   - isolated git worktrees and real validation and test commands;
   - an append-only, hash-chained audit log, metrics, and a timeline.

   Nothing is ever merged, pushed, or deployed.
2. **The system it works on**: a production-*informed* (not production-ready) multi-tenant URL shortener.
   It has API-key auth, per-tenant fair-share rate limiting, usage billing and invoicing, and an admin API.
   It also gives the orchestrator a real brownfield codebase to change.

A lighter **single-pass preview** (`/api/v1/agentic`) produces the same planning artifacts in one synchronous
call, with no side effects. The orchestrator reuses its agents as graph stages. README §12 and §13 say when
to use which.

## 2. Real versus simulated: read this before anything else

| Capability | Status |
|---|---|
| Orchestration: graph, scheduling, parallelism, gates, persistence, resume, re-planning, approvals, retries, fallback, cancel, rollback, audit, metrics | **Real**, tested, exercised by three end-to-end scenarios |
| Default execution mode `DETERMINISTIC_DEMO` | **Not AI.** Stage agents are keyword rules and templates. Every artifact, report, and API response says so |
| Code in demo mode | **Real code, for two recognized requirements only.** Two **pre-authored deterministic recipes** (`click-limit`, `expired-reactivation`) apply anchored edits to the worktree's real source and add integration tests. Any other requirement gets a change-record document (`kind: DOCUMENT`). Each patch's `author` says which it is (`recipe:<id>`, `change-record`, or `model`) |
| Test execution | **Real when chosen** (`testExecution: COMMAND`): `mvn -B -o test -Dtest=<plan classes + the patch's own tests>` in the patched worktree. Greenfield and brownfield use it. The default (`PLAN_VERIFICATION`) runs no product tests and says so |
| `LLM` mode (Claude via the official Anthropic Java SDK, structured outputs, token/cost budget) | **Implemented, tested against mocked and scripted clients. Never run against the live API** in this repository's verification. The opt-in `AnthropicLiveSmokeTest` exists and was skipped |
| Human approvals in the scenarios | Made by a **scripted reviewer** through the same version-bound decision API a person uses |
| The recoverable failure in the greenfield scenario | An **injected** transient fault (`demoFaults`), accepted only in demo mode and recorded in the audit trail |
| Merge / push / deploy | **Not implemented, by design.** Promotion commits the approved diff to a local branch in a private sandbox clone |

The full list is in `docs/limitations-and-tradeoffs.md`.

## 3. Run it (about 10 minutes)

Requires JDK 17+, Maven 3.9+, git, and a local Maven repository populated by the first build (the scenario
test stage runs Maven offline).

```bash
mvn clean verify                                     # build, all tests, 80% JaCoCo gate (includes the 3 scenarios)

# the three end-to-end scenarios, no credentials; reports in target/workflow-scenarios/
mvn spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--app.workflow.cli.scenario=all --server.port=0"

# or interactively: app on :8080, operator key admin-dev-only-key-change-me, Swagger UI at /swagger-ui.html
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# real LLM mode: separate, spends API credit, NOT run in this verification
ANTHROPIC_API_KEY=... APP_AGENTIC_LLM_ENABLED=true mvn spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--app.workflow.cli.scenario=greenfield --app.workflow.cli.mode=LLM --server.port=0"
```

Committed copies of the scenario reports: `docs/demo-output/greenfield.md`, `brownfield.md`, `ambiguous.md`.
Each one has the steps, an evidence table computed from the audit log, the final engineering summary, and
the timeline.

## 4. The three scenarios

| Scenario | Requirement | What it demonstrates |
|---|---|---|
| **Greenfield** | Optional per-link click limits: 410 Gone once reached, 400 below 1, remaining clicks on stats | The `click-limit` recipe writes real code across entity, DTOs, repository (an atomic limit in the click-count `UPDATE`), cache projection, and service, plus a codemod over 40 existing test call sites, **generated unit tests** (`ClickLimitServiceTest`, `UrlMappingMapperClickLimitTest`), an integration test (`ClickLimitIntegrationTest`), and the README API-reference update. Also: an interface contract with migration DDL; **real `mvn test` in the worktree**; parallel tests/security/docs; an injected transient failure retried and recovered; a version-bound human approval; promotion to a local branch; and a formatting-only re-plan that reuses unaffected work and keeps the approval valid |
| **Brownfield** | Refuse reactivation of expired links with 409 (plus an out-of-scope "charge the card" clause) | Impact analysis of existing modules. **A rejected policy gate** (POL-SCOPE) with no retry, while the independent branches finish. Then the amendment re-plans: the worktree is reset, and the `expired-reactivation` recipe adds the 409 guard, a unit test (`ExpiredLinkReactivationServiceTest`), an integration test (`ExpiredLinkReactivationIntegrationTest`) and the README update, and those tests really run |
| **Ambiguous** | "Make links safer and analytics more useful." | Ambiguities with proposed assumptions; a clarification checkpoint that holds all downstream work; a human rejection that goes **STALE** when the spec changes; re-planning on the clarified requirement. No recipe matches, so the patch is honestly a change record |

Walkthrough with every check: `docs/demo-scenarios.md`.

## 5. Verification record (this working tree)

| Item | Value |
|---|---|
| Date | 2026-11-02 |
| Tree | Commit `189b6c4` **plus the review-pass changes** described in `ENGINEERING_SUMMARY.md` §18 |
| Java / Maven / OS | Java 21.0.4 (Oracle) / Maven 3.9.9 / Windows 10 |
| `mvn clean verify` | **Tests run: 409, Failures: 0, Errors: 0, Skipped: 1** (the skip is the opt-in `AnthropicLiveSmokeTest`). All coverage checks met. BUILD SUCCESS in 5 min 13 s. `ScenarioEndToEndTest`: 3/3 passed in 190 s, including the real nested Maven test runs |
| JaCoCo line coverage | **93.4%** (5,829/6,244) bundle-wide, clearing the 80% gate. `workflow` packages: 91.7% (3,800/4,145) |
| Scenario CLI (`--app.workflow.cli.scenario=all`, dev profile) | Exit 0. Evidence checks: greenfield **11/11**, brownfield **10/10**, ambiguous **7/7**. Reports copied to `docs/demo-output/` |
| Real tests inside the scenarios | Greenfield: `mvn -B -o test` in the patched worktree on the generated `ClickLimitIntegrationTest`, `ClickLimitServiceTest`, `UrlMappingMapperClickLimitTest`, and the plan's existing classes. Maven reported **Tests run: 113, Failures: 0, Errors: 0, Skipped: 0**. Brownfield: the generated `ExpiredLinkReactivationIntegrationTest` and `ExpiredLinkReactivationServiceTest` plus the plan's classes, **Tests run: 83, Failures: 0, Errors: 0, Skipped: 0** |
| Application smoke test | `mvn spring-boot:run -Dspring-boot.run.profiles=dev` (by hand, earlier on 2026-11-02 before the review pass): health `UP`, create → 302 redirect, workflow capabilities 200, Swagger UI 200. The post-review Newman run exercises the same paths |
| Postman / Newman (`postman/url-shortener.postman_collection.json`, 106 requests in 12 folders) | `newman run` (Newman 6.2.3) against a freshly started dev instance, re-run after the review pass: **180 assertions, 0 failures**. That covers the URL-shortener folders 0–7, plus new folders `8. Agentic SDLC` (10 requests) and `9. Workflow Orchestrator` (34 requests: a full greenfield run through approval, promotion, re-planning, and compensation, plus an ambiguous run through clarification and rejection; async runs are polled). Folders 0–7 register fixed tenant names, so they need a fresh instance; folders 8–9 also pass on a re-run |
| Live Anthropic API | **Not run** (`AnthropicLiveSmokeTest` skipped) |
| OWASP dependency-check | **Not run** (needs a multi-gigabyte NVD download) |

Static analysis (`javac -Xlint:all`, SpotBugs) and the credential scan were re-run in the review pass; results are in `SUBMISSION_NOTES.md`.

## 6. Deliverables, and where to look

| Deliverable (assignment §5) | Where |
|---|---|
| Working prototype, runnable end to end | §3 above; `mvn clean verify`; the scenario CLI; the Postman collection |
| Architecture overview (components, orchestration model, control flow, key decisions) | `ARCHITECTURE.md` §9 (orchestrator) and §8 (preview), `docs/architecture.md`, `docs/orchestration.md` |
| Three scenarios, each showing decomposition, orchestration, validation | §4 above, `docs/demo-scenarios.md`, `docs/demo-output/*.md` |
| Setup instructions | §3 above, `README.md` §3 and §13 |
| Testing approach, limitations, and trade-offs | `docs/testing-approach.md`, `docs/limitations-and-tradeoffs.md` |

| Evaluation criterion | Where |
|---|---|
| Agentic SDLC design and orchestration | `ARCHITECTURE.md` §9 (and §8 for the preview), `docs/architecture.md`, `docs/orchestration.md` |
| Requirement → implementation traceability | `SUBMISSION_NOTES.md`: every assignment requirement, with its code location, demo command, and validation evidence |
| Governance, safety, human-in-the-loop | `docs/governance.md`: version-bound approvals, staleness, policies, change control, rollback boundaries |
| Realism and quality of outputs | `docs/demo-output/*.md`: real runs. The greenfield patch is a real diff against this repository, and its tests ran |
| AI-assisted execution: what was generated, edited, or rejected, and why | `ENGINEERING_SUMMARY.md` §18 (orchestrator) and §17 (preview). Both include the bugs found only by running the system |
| Validation and risk management | `docs/testing-approach.md`; scenario evidence checks (computed from the audit log, not asserted by the script); `WorkflowEngineTest` as the behavioral spec; `docs/limitations-and-tradeoffs.md` |
| Engineering judgment | §2 above; the "Rejected" lists in `ENGINEERING_SUMMARY.md` §17-18; the design-decision tables in `ARCHITECTURE.md` §8.2 and §9.3 |
| The URL shortener itself | `README.md` §1-11, `ARCHITECTURE.md` §1-7, `ENGINEERING_SUMMARY.md` §1-16 |

**Files to read first:**
1. `workflow/engine/WorkflowEngine.java`: scheduling, gates, retries, fallback, resume, re-planning.
2. `workflow/engine/StageAgent.java`, `AgentRequest.java`, `AgentResult.java`: the agent contract.
3. `workflow/agents/ImplementationAgent.java` and `workflow/agents/recipes/`: patch authoring and the
   demo recipes.
4. `workflow/WorkflowService.java`: approvals, promotion, amendment.
5. `workflow/workspace/WorkspaceManager.java`: isolation and rollback.
6. `docs/demo-output/greenfield.md`: what a real run produces.

## 7. Core engineering principles, briefly

- **Modular.** The orchestrator is a separate package with a one-way dependency on the preview's agents.
  Stage agents, execution adapters, test runners, and recipes are interfaces with swappable implementations.
- **Testable.** Unit tests per component. Real-git workspace tests. API tests through the security chain.
  Three end-to-end scenarios whose checks are computed from the audit log. An 80% JaCoCo gate in the build.
- **Reliable.** Short transactions. Resume after a crash (RUNNING → INTERRUPTED → resumable). Bounded
  retries for transient failures only, never for policy or gate failures. Input fingerprints, so resume
  doesn't repeat valid work.
- **Secure.** Operator-only API (fail-closed). No shell, argv-only commands from configuration only.
  A path allowlist, with protected paths that need approval. Model output and repository content are
  treated as untrusted. Secret redaction in every audit event.
- **Safe change management.** Patches exist only in per-run worktrees and are re-applied from their exact
  diff. Approvals bind to artifact hashes and go stale on change. Promotion is an explicit, approved, local
  action with compensation.

## 8. Git history

The URL-shortener commits (everything before `55e5fc2`) were organized retroactively by theme. They are not
a literal replay of the editing session, and individual early commits are not guaranteed to compile in
isolation. `ENGINEERING_SUMMARY.md` §8-10 documents the history that isn't visible, including a reverted
Spring Boot 4 migration. The agentic and orchestrator commits (`55e5fc2` onward) were made as the work
happened.
