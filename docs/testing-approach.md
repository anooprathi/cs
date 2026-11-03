# Testing approach

How this repository is tested, what each layer proves, what is stubbed, and what is not tested at all. Exact
results for the current tree are in `SUBMISSION.md` §5.

## The layers

| Layer | What it proves | Tools | Where |
|---|---|---|---|
| **Unit** | One class's rules, with collaborators mocked or replaced by real pure objects | JUnit 5, Mockito, AssertJ | `service/`, `tenant/`, `billing/`, `ratelimit/`, `agentic/` (agents, gate, renderer), `workflow/` (graph validator, policies, runners, recipes, reports) |
| **Slice** | The REST contract of one controller: validation, status codes, error envelope | `@WebMvcTest` + MockMvc | `controller/UrlShortenerControllerWebMvcTest` |
| **Integration** | The real stack: security filter chain → controller → service → JPA → H2, including rate limiting, tenancy isolation (404 not 403), and the global exception handler | `@SpringBootTest` + MockMvc, `test` profile | `integration/` (URL shortener, admin, agentic API in both modes, workflow API) |
| **Engine behavior** | The orchestrator's rules with fake agents: dependency order, the parallel barrier, entry/exit gates, retries and exhaustion, fallback, approvals bound to versions, stale approvals, rejection, cancel, safe-stop, rollback, crash recovery, resume and reuse, invalidation, re-planning, audit chain and redaction | `FakeAgents` (scripted outputs, failures, latencies) + real engine/store/audit on H2 | `workflow/WorkflowEngineTest` (the behavioral spec), `SequentialExecutionTest` |
| **Real git** | Workspaces never touch the source repository: apply, reset, re-apply of an exact diff (including diffs far larger than the process-output buffer), promotion, compensation, refusal to write outside the worktree | Real `git` in temporary repositories | `workflow/workspace/WorkspaceManagerTest` |
| **End-to-end scenarios** | The three assignment scenarios through the real stage agents. Every evidence check is computed from the audit log, not asserted by the script. Greenfield and brownfield run **real `mvn -o test`** in the patched worktree, on the generated unit and integration tests plus the plan's existing test classes | Real agents, real git, a nested Maven build | `workflow/scenario/ScenarioEndToEndTest`; the same code runs from the CLI (`--app.workflow.cli.scenario=all`) |
| **API contract (black box)** | Every endpoint of the running app, in order, with assertions: product folders 0–7, agentic preview (8), workflow orchestrator (9, self-polling async runs) | Postman collection, Newman | `postman/url-shortener.postman_collection.json` |
| **Coverage gate** | Nothing ships with untested bulk code | JaCoCo, 80% line coverage on `mvn verify` | `pom.xml` |

## What is stubbed, and why

| Stub | Stands in for | Why it's acceptable | What would catch a regression |
|---|---|---|---|
| Mocked `AnthropicClient` (`AnthropicLlmClientTest`) | The Anthropic SDK over the network | Tests the request shape, structured-output schema, refusal, max_tokens, and error mapping without spending money | The opt-in `AnthropicLiveSmokeTest` (**not run** in this repository's verification) |
| Scripted `LlmClient` (`SdlcOrchestratorTest`, `AgenticLlmModeIntegrationTest`, `ExecutionAdaptersTest`) | Model output | Lets tests inject hallucinated components, failures, and token usage deterministically | The deterministic quality gate and exit gates, which are real in every mode |
| `FakeAgents` (`WorkflowEngineTest`) | The seven stage agents | Isolates engine rules from agent logic, with exact control of failures and timing | `ScenarioEndToEndTest` runs the real agents |
| Injected `demoFaults` | A real transient outage | Makes the retry and recovery path reproducible | `WorkflowEngineTest` retry and exhaustion tests |
| H2 in memory | A production database | The app itself runs on H2 (documented prototype limit) | Nothing: no migration tooling exists (README §10) |

## What is deliberately not tested

- **Live LLM quality.** No evaluation set exists. A live call has never been made in this repository's verification.
- **Load and latency.** No performance or soak tests. The redirect hot path is designed for them (cache, atomic
  update, async metering), but no numbers are claimed.
- **Multiple instances.** The orchestrator's run ownership, audit sequencing, and budget ledger are in-process
  (`docs/limitations-and-tradeoffs.md`).
- **The full suite inside a workflow worktree.** `testExecution=COMMAND` runs the targeted classes, not
  `mvn verify` with its coverage gate. Run that on a promoted branch before merging.

## How the generated code is tested

The demo recipes generate tests at the same layers a human would write:

| Recipe | Unit tests (generated) | Integration test (generated) | Existing tests updated |
|---|---|---|---|
| `click-limit` | `ClickLimitServiceTest` (Mockito: under the limit, exhausted, unlimited, create persists the limit) and `UrlMappingMapperClickLimitTest` (remaining-clicks arithmetic, clamp at zero) | `ClickLimitIntegrationTest` (302 until the limit, then 410; remaining clicks; unlimited; 400 below 1) | A lexer-aware codemod over every `new ShortenUrlRequest/UrlStatsResponse/RedirectTarget(...)` |
| `expired-reactivation` | `ExpiredLinkReactivationServiceTest` (expired refused and never saved; unexpired and no-expiry reactivate) | `ExpiredLinkReactivationIntegrationTest` (409; extend then reactivate; unexpired) | None needed |

`CodeRecipesTest` checks that each recipe applies to this repository's real source and refuses cleanly when its
anchors don't match. `ScenarioEndToEndTest` proves that the patched worktree compiles and that the generated tests
pass.

## Running each layer

```bash
mvn clean verify                                              # everything + coverage gate
mvn test -Dtest=WorkflowEngineTest                            # orchestrator behavioral spec (fast)
mvn test -Dtest='CodeRecipesTest,WorkspaceManagerTest' -Djacoco.skip=true
mvn test -Dtest=ScenarioEndToEndTest -Djacoco.skip=true       # 3 scenarios, nested Maven (~3-5 min)
mvn spring-boot:run -Dspring-boot.run.profiles=dev            # then, against this fresh instance:
newman run postman/url-shortener.postman_collection.json
```
