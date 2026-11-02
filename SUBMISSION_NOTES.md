# Submission notes: agentic SDLC workflow orchestrator

**What was added.** A separate orchestration module (`com.schwab.urlshortener.workflow`) in the existing Spring Boot 3.3
stack. It runs a requirement through an explicit **7-stage dependency graph** with:
- parallel scheduling and dependency barriers
- explicit entry and exit gates
- persistent task state with restart and resume
- versioned artifacts with decision lineage
- human approval checkpoints bound to exact artifact versions
- configurable security and change-control policies
- bounded retries and a defined fallback
- safe-stop and cancellation
- git-worktree isolation with rollback and compensation
- dynamic re-planning
- an append-only, hash-chained audit log, durable metrics, and a readable timeline

It also includes three runnable end-to-end scenarios.

**Honesty up front:**
- **Demo mode is not AI.** It is rule-based and needs no credentials. The real-LLM adapter (Anthropic Java SDK) is
  implemented and tested against mocked or scripted clients, and **was not run against the live API**.
- **Demo-mode code comes from two pre-authored, rule-based recipes, not from a model.** `click-limit`
  (greenfield) and `expired-reactivation` (brownfield) edit the worktree's real source and add integration
  tests. Any other requirement gets a change-record document, not product code. Each patch's `kind` and
  `author` say which one it is.
- **Test execution is chosen per run.** `PLAN_VERIFICATION` (the default) runs no product tests. `COMMAND`
  runs real `mvn -o test` on the plan's classes plus the patch's own tests, in the worktree. The greenfield
  and brownfield scenarios use `COMMAND`. It does not run the full suite or the coverage gate.
- **Nothing is ever merged, pushed, or deployed.**

`docs/limitations-and-tradeoffs.md` lists every simulated or unimplemented capability.

## Run it

```bash
mvn clean verify                                        # build, all tests, 80% JaCoCo gate
mvn spring-boot:run -Dspring-boot.run.profiles=dev      # app on :8080, operator key: admin-dev-only-key-change-me

# the three scenarios, end to end, no credentials (reports in target/workflow-scenarios/)
mvn spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--app.workflow.cli.scenario=all --server.port=0"

# real LLM-backed execution (separate; spends API credit; not run in this verification)
ANTHROPIC_API_KEY=... APP_AGENTIC_LLM_ENABLED=true mvn spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--app.workflow.cli.scenario=greenfield --app.workflow.cli.mode=LLM --server.port=0"
```

Walkthrough with curl: `README.md` §13. Committed scenario outputs: `docs/demo-output/`.

## Requirement → implementation mapping

Validation evidence names test classes under `src/test/java/com/schwab/urlshortener/`, abbreviated as `…/` below.

### Orchestration module

| Requirement | Implementation | Demonstration | Validation evidence |
|---|---|---|---|
| Separate orchestration module, existing stack | `workflow/` package, Spring Boot/JPA/H2, no new runtime dependency | `GET /api/v1/workflows/capabilities` | all workflow tests below |
| Workflow as an explicit dependency graph | `graph/WorkflowDefinition`, `TaskNode`; snapshot per run | `GET /definitions/standard` | `GraphValidatorTest#standardGraph_isValid_withThreeWayParallelLayer` |
| 7 stages | `graph/WorkflowStage`; 7 `agents/*Agent` | `GET /capabilities` (agents) | `WorkflowApiIntegrationTest#capabilities_and_definitionValidation` |
| Sequential and parallel execution | `WorkflowEngine.schedule/dispatch`, `max-parallelism` | scenario timelines | `WorkflowEngineTest#independentBranches_runInParallel_andJoinWaitsForBoth` (2-party barrier), `SequentialExecutionTest` |
| Dependency synchronization | `WorkflowEngine.promoteReady` (all deps SUCCEEDED) | timeline: release after tests/security/docs | `WorkflowEngineTest#dependencyOrder_…`, scenario check "Dependency barrier" |
| Entry/exit gates with explicit criteria | `engine/GateCriterion`, `GateResult`; per-agent `entryCriteria/exitCriteria` | `GET /runs/{id}` → `entryGate`/`exitGate` | `WorkflowEngineTest#entryGateFailure_…`, `#exitGateFailure_…` |
| Persistent workflow and task state | `persistence/WorkflowRun`, `WorkflowTask`; `WorkflowStore` short transactions | `GET /runs/{id}` | every engine test reads persisted state |
| Restart/resume without repeating valid work | `WorkflowEngine.prepare/invalidReason/tryReuse`, input fingerprints | `POST /runs/{id}/resume` | `WorkflowEngineTest#resume_afterFailure_…`, `#crashRecovery_…` |
| Cross-stage context, versioned artifact refs | `engine/AgentRequest`, `ArtifactRef`, `outputRefsJson`, `WorkflowArtifact` versions | `GET /runs/{id}/artifacts` | `WorkflowEngineTest#invalidation_withChangedUpstreamOutput_…` (v2 refs) |
| Decision lineage | `WorkflowLineageEdge` (inputs actually read), agent result envelope, gates, approvals | `GET /runs/{id}/artifacts/{name}/lineage` | `WorkflowApiIntegrationTest#fullRun_throughTheApi` |
| Explicit task states | `engine/TaskState` (all 9) | `GET /runs/{id}` | state assertions throughout `WorkflowEngineTest` |
| Graph validation (cycles, invalid deps) | `graph/GraphValidator` (11 error codes incl. `INPUT_NOT_UPSTREAM`) | `POST /definitions/validate` | `GraphValidatorTest` (7 tests) |
| API/CLI: submit, start, status, artifacts, history | `api/WorkflowController`, `scenario/ScenarioCli` | `README.md` §13 | `WorkflowApiIntegrationTest` |

### Stage agents and execution adapters

| Requirement | Implementation | Demonstration | Validation evidence |
|---|---|---|---|
| Stage-specific agent interface with intent, constraints, criteria, repo context, approved inputs, tools, limits | `engine/StageAgent`, `AgentRequest`, `ToolPolicy` | `GET /capabilities` (tools, criteria) | `ExecutionAdaptersTest`, `WorkflowEngineTest#agentFailure_andToolViolation_…` |
| Structured output: summary, artifacts, evidence, assumptions/risks, decisions, status | `engine/AgentResult`; stored as `resultJson`; `AGENT_RESULT` events | lineage `agentResult` | `WorkflowApiIntegrationTest#fullRun_…` (decisions present) |
| Real LLM adapter via environment variables | `adapter/LlmExecutionAdapter` → `agentic/llm/AnthropicLlmClient` (SDK) | LLM command above | `ExecutionAdaptersTest` (scripted client), `AnthropicLlmClientTest` (mocked SDK). **No live call** |
| Deterministic demo adapter | `adapter/DeterministicDemoAdapter` | scenarios | `ExecutionAdaptersTest#router_…`, all scenario tests |
| Execution mode labeled in every run | `executionNotice`, artifact `origin/adapter/model`, Markdown banners, audit `executionMode` | any run view or timeline | `WorkflowApiIntegrationTest`, `ScenarioEndToEndTest` (banner asserted) |
| Code changes in an isolated workspace / git worktree | `workspace/WorkspaceManager` (sandbox clone + per-run worktree) | greenfield: patch + promotion | `WorkspaceManagerTest` (real git, source repository untouched) |
| Restrict tools, paths, commands, time, tokens/cost | `ToolPolicy`, `ChangeControlPolicy`, `ProcessRunner`, `limits.*`, `BudgetLedger` | `GET /capabilities` | `ChangeControlPolicyTest`, `ExecutionAdaptersTest#…budget…`, `PolicyAndRunnersTest#commandRunner_timesOut` |
| Repository content and model output untrusted; no model shell commands | `PatchProposal` (no command field), delimiters, typed outputs, argv-only commands from config | n/a | `ChangeControlPolicyTest`, `WorkspaceManagerTest#apply_refusesToWriteOutsideTheWorktree_…` |
| Reviewable patches + approved validation commands | `ImplementationAgent`, `ChangePatch` (`kind`, `author`, diff, validation evidence), `policy.validation-commands` | greenfield `change-patch` | scenario check "Patch applied in an isolated git worktree and validated" |
| Real code change and test in demo mode (greenfield + brownfield) | `agents/recipes/` (`ClickLimitRecipe`, `ExpiredReactivationRecipe`, anchored `SourceEdits` + test-call-site codemod) | greenfield, brownfield | `CodeRecipesTest`; scenario check "Real code change: CODE patch with tests, executed by the real test command" |
| Tests executed against the patched worktree | `TestExecution` (per run), `CommandTestRunner` (+ the patch's own test classes), `ProcessRunner` (Windows `.cmd` resolution) | `"testExecution":"COMMAND"` | `PolicyAndRunnersTest#commandRunner_alsoRunsTheTestClassesThePatchAdds`, `ScenarioEndToEndTest` (real nested `mvn test`) |
| API / schema definition artifact | `artifact/InterfaceContract` (`interface-contract`), gate IMP-EXIT-7 | greenfield (3 API changes + DDL) | `PolicyAndRunnersTest#contractGate_…`; scenario check "Interface contract declares …" |
| Human approval before merging/deploying | `WorkflowService.promote` (valid approval + bound patch + confirm) | greenfield promotion | `WorkspaceManagerTest#promote_…`, `WorkflowApiIntegrationTest` (promotion refused without workspace) |

### Governance and recovery

| Requirement | Implementation | Demonstration | Validation evidence |
|---|---|---|---|
| Approval checkpoints for high-impact actions | graph/policy/clarification/protected-path/fallback checkpoints; promotion | ambiguous, greenfield | `WorkflowEngineTest#approval_…`, `#llmFailure_takesTheDefinedFallback_…` |
| Approvals bound to exact artifact version | `WorkflowApproval` (id+version+hash); stale-view 409 | decision API | `WorkflowEngineTest#approval_isBoundToTheExactVersion_…` |
| Approval invalidated when the artifact changes | `WorkflowStore.staleApprovals` | ambiguous (STALE rejection) | `WorkflowEngineTest#approvalGoesStale_…`, `#approvalSurvivesReplanning_…` |
| Configurable security and change-control policies | `app.workflow.policy.*`, `PolicyEngine`, `ChangeControlPolicy` | brownfield POL-SCOPE | `PolicyAndRunnersTest` (6 policy tests), `ChangeControlPolicyTest` |
| Bounded retries with backoff for transient failures | `WorkflowEngine.attemptWithRecovery`, `Retry.backoffBefore` | greenfield injected fault | `WorkflowEngineTest#transientFailures_…`, `#retryExhaustion_…` |
| No automatic retry for policy violations or missing approvals | failure taxonomy (`AgentFailures`) | brownfield, ambiguous | `#entryGateFailure_…` (no retry), `#rejection_…`, scenario check "Policy violation was NOT retried" |
| Defined fallback path | `fallbackOrFail` → deterministic, labeled, approval-gated | n/a (LLM mode) | `WorkflowEngineTest#llmFailure_takesTheDefinedFallback_…` |
| Safe-stop and cancellation | `WorkflowService.stop/cancel`, engine halt checks | API | `WorkflowEngineTest#safeStop_…`, `#cancel_…` |
| Workspace rollback / compensation | `WorkspaceManager.reset`, `compensatePromotion`, `StageAgent.compensate` | `POST /workspace/rollback`, `/promotion/compensate` | `WorkspaceManagerTest#reset_…`, `#promote_…AndCompensationDeletesIt` |
| Rollback boundaries documented | `docs/governance.md` §5 | n/a | n/a |
| Dynamic re-planning (invalidate affected, preserve unaffected, record reason, re-run stages and approvals) | `amendRequirement`, `invalidate`, fingerprint re-validation, reuse | all three scenarios | `WorkflowEngineTest#requirementAmendment_…`, `#invalidation_…` (×2), scenario checks |
| Tests for retry exhaustion, rejection, stale approvals, cancellation, rollback, downstream invalidation | see the cells above | `mvn test -Dtest='Workflow*Test,WorkspaceManagerTest'` | all pass (see Verification) |

### Audit and metrics

| Requirement | Implementation | Demonstration | Validation evidence |
|---|---|---|---|
| Durable audit with run/task/agent ids, versions, transitions, decisions, validation, approval identity and time, retry/fallback/rollback/safe-stop, mode/model | `audit/AuditLog`, `WorkflowEvent` (sequence, agentId, from/to, actor, refs, details) | `GET /runs/{id}/history` | `WorkflowEngineTest#auditLog_redactsSecrets_andEveryTaskTransitionIsRecorded`, `#approval_…` (actor and decidedAt) |
| No secrets or sensitive prompts in logs | `SecretPatterns.redact` on every event and log line; hashes instead of requirement text | n/a | `PolicyAndRunnersTest#redaction_…`, `WorkflowEngineTest#auditLog_redacts…` |
| Append-only interface + integrity limitations | `WorkflowEventRepository` (no update/delete), `@PreUpdate`, per-run SHA-256 chain, `/audit/verify` | `GET /runs/{id}/audit/verify` | `WorkflowEngineTest#auditChain_isIntact_andDetectsTampering`; limits in `docs/governance.md` §6 |
| Metrics with precise definitions | `metrics/WorkflowMetricsService` (+ Micrometer counters) | `GET /api/v1/workflows/metrics` | `WorkflowMetricsServiceTest` (5 tests pin each definition) |
| Readable execution timeline | `WorkflowReports.timeline` (span chart + event table) | `GET /runs/{id}/timeline` | `WorkflowApiIntegrationTest`, `docs/demo-output/*.md` |
| Final engineering summary: plan and rationale, risks and trade-offs, assumptions, what was built | `WorkflowReports.summary` (from the accepted artifacts and each stage's recorded decisions) | `GET /runs/{id}/report.md` | `WorkflowApiIntegrationTest#fullRun_throughTheApi` (asserts every section); `docs/demo-output/*.md` |

### Scenarios

| Requirement | Implementation | Demonstration | Validation evidence |
|---|---|---|---|
| Greenfield / brownfield / ambiguous scenarios with normalization, graph, execution, artifacts, evidence, approval, summary | `scenario/ScenarioService` | CLI or `POST /scenarios/{name}` | `ScenarioEndToEndTest` (3 tests; every evidence check must pass) |
| Parallel execution, recoverable failure, rejected gate, upstream-change re-planning | greenfield / brownfield / ambiguous | `docs/demo-scenarios.md` | the scenario evidence tables in `docs/demo-output/` |
| Demo without paid credentials; separate LLM command | default mode; LLM command above | both commands | demo: verified; LLM: **not executed** |

## Verification (this working tree)

Run on 2026-11-01, Java 21.0.4 / Maven 3.9.9, Windows 10. The build, scenario, and CLI rows are from this
pass (real code recipes, per-run test execution). The `javac -Xlint`, SpotBugs, and credential-scan rows are
from the previous pass and were not re-run.

| Check | Result |
|---|---|
| `mvn clean verify` | **405 tests, 0 failures, 0 errors, 1 skipped** (`AnthropicLiveSmokeTest`, opt-in, spends API credit). JaCoCo line coverage 93.1% (workflow packages 91.7%), clearing the 80% gate. BUILD SUCCESS |
| Scenario tests | `ScenarioEndToEndTest` 3/3, using real git and, for greenfield and brownfield, a real nested `mvn -B -o test` in the patched worktree. Maven reported 106 and 80 tests, 0 failures |
| Scenario CLI (`--app.workflow.cli.scenario=all`, dev profile) | greenfield 11/11, brownfield 10/10, ambiguous 7/7 evidence checks, exit 0. Reports in `docs/demo-output/` |
| `javac -Xlint:all` (all main sources) | 0 errors. 31 warnings: 27 `[serial]` (exceptions without `serialVersionUID`, same as the existing exception classes), 3 `[deprecation]` in pre-existing `LocalRateLimiterBackend` (Bucket4j API), 1 annotation-processor notice |
| SpotBugs 4.8.6 (max effort, medium threshold, via Maven) | 229 findings before fixes. Fixed 5 null-path and 2 map-iteration findings in new code; 224 remain: 213 `EI_EXPOSE_REP/REP2` (Spring/Lombok false positives), 6 `CT_CONSTRUCTOR_THROW` (intentional fail-fast startup validation), 3 `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` (not re-triaged after the fixes), 1 format-string newline, 1 `DM_EXIT` (the CLI's exit code) |
| Credential-pattern scan (`git grep`, tracked files, same patterns as `SecretPatterns`) | Only deliberate synthetic test fixtures. One fixture derived from a real dev key was replaced with a synthetic value |
| OWASP dependency-check | **Not run**: it needs a multi-gigabyte NVD download |
| Live Anthropic API | **Not run** |

## Files to inspect first

1. `src/main/java/com/schwab/urlshortener/workflow/engine/WorkflowEngine.java`: scheduling, gates, retries, fallback, resume, re-planning
2. `src/main/java/com/schwab/urlshortener/workflow/engine/StageAgent.java`, `AgentRequest.java`, `AgentResult.java`: the agent contract
3. `src/main/java/com/schwab/urlshortener/workflow/WorkflowService.java`: approvals (binding, staleness), promotion, amendment
4. `src/main/java/com/schwab/urlshortener/workflow/workspace/WorkspaceManager.java` + `ChangeControlPolicy.java`: isolation and rollback
5. `src/main/java/com/schwab/urlshortener/workflow/audit/AuditLog.java`: append-only hash chain and redaction
6. `src/test/java/com/schwab/urlshortener/workflow/WorkflowEngineTest.java`: the behavioral spec
7. `docs/demo-output/greenfield.md`: what a real run looks like
8. `docs/limitations-and-tradeoffs.md`: what is simulated or missing
