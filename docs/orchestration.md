# Orchestration

How the SDLC workflow module turns a requirement into a governed, resumable run of an explicit dependency graph.
Code: `src/main/java/com/schwab/urlshortener/workflow`. Tests: `src/test/java/com/schwab/urlshortener/workflow`.

## 1. The graph

A workflow is a `WorkflowDefinition`: nodes with a key, the stage agent that does the work, the node keys it depends
on, and whether it is approval-gated. The standard graph (`WorkflowDefinition.standard()`):

```
requirements → design → implementation ─┬─→ tests ────┐
                                        ├─→ security ─┼─→ release (approval-gated)
                                        └─→ docs ─────┘
parallel layers: [requirements] [design] [implementation] [tests, security, docs] [release]
```

A run snapshots its definition as JSON when it is created, so later changes to the default never alter how an
existing run resumes. A custom graph can be submitted with `POST /runs` (`definition`) and checked first with
`POST /definitions/validate`.

### Graph validation (`GraphValidator`)

| Check | Error code |
|---|---|
| no tasks | `EMPTY_GRAPH` |
| blank or repeated key | `BLANK_TASK_KEY`, `DUPLICATE_TASK` |
| agent not registered | `UNKNOWN_AGENT` |
| depends on itself / on a missing task / twice on the same task | `SELF_DEPENDENCY`, `UNKNOWN_DEPENDENCY`, `DUPLICATE_DEPENDENCY` |
| dependency cycle (the path is reported, e.g. `a -> c -> b -> a`) | `CYCLE` |
| two tasks produce the same artifact | `DUPLICATE_PRODUCER` |
| a consumed artifact nobody produces | `MISSING_INPUT` |
| a consumed artifact produced by a task that is **not an ancestor** (the two would race) | `INPUT_NOT_UPSTREAM` |

The last check catches graphs that are valid DAGs but still wrong. Validation also computes the topological order
and the parallel layers. The engine re-validates the snapshot at every drive.

## 2. Stages and stage agents

Each stage is one `StageAgent`. Its contract (`engine/StageAgent.java`):

- **Input** (`AgentRequest`):
  - task intent and constraints
  - acceptance criteria (its exit-gate criteria)
  - repository context (base commit, isolated workspace)
  - the approved upstream artifacts it declared, as versioned refs
  - its tool policy and its limits (timeout, token budget, output cap)
- **Output** (`AgentResult`):
  - status COMPLETED or FAILED, and a summary
  - produced artifacts (each with an origin)
  - validation evidence
  - assumptions and risks
  - decisions with rationale
  - an optional review reason (requests a human checkpoint) and usage

| Task | Agent | Stage | Consumes | Produces | Tools | Generative step |
|---|---|---|---|---|---|---|
| requirements | `requirement-analysis` | REQUIREMENT_ANALYSIS | (raw requirement) | requirement-spec | catalog, LLM | RequirementAnalystAgent |
| design | `architecture-design` | ARCHITECTURE_DESIGN | requirement-spec | architecture-design | artifacts, catalog, LLM | ImpactAnalystAgent |
| implementation | `implementation` | IMPLEMENTATION | requirement-spec, architecture-design | implementation-plan, **change-patch**, **interface-contract** | + repo files, workspace write, validation commands | PlannerAgent + patch author (model, demo code recipe, or change record) |
| tests | `test-generation-execution` | TEST_GENERATION_AND_EXECUTION | spec, design, plan, change-patch | test-plan, test-report | + test command | TestDesignerAgent + the run's TestRunner (plan verification, or the test command including the patch's own tests) |
| security | `security-policy` | SECURITY_POLICY_VALIDATION | spec, design, plan, change-patch (+ raw requirement) | risk-assessment, policy-report | artifacts, catalog, LLM | RiskReviewerAgent + PolicyEngine |
| docs | `documentation` | DOCUMENTATION | spec, design, plan | documentation | artifacts | deterministic |
| release | `release-readiness` | RELEASE_READINESS | spec, plan, patch, test-report, policy-report, risks, docs | readiness-report | artifacts | deterministic |

Every agent's entry and exit criteria are explicit, named objects (`GateCriterion`, e.g. `IMP-EXIT-4 "The patch
satisfies the change-control policy"`). They are listed by `GET /capabilities`, recorded per attempt on the task
(`entryGate` / `exitGate`), and recorded on every artifact (`gates`).

### Execution modes and adapters

| Mode | Adapter | What runs | Credentials |
|---|---|---|---|
| `DETERMINISTIC_DEMO` (default) | `DeterministicDemoAdapter` | each agent's rule-based path: reproducible, offline, free, **not AI** | none |
| `LLM` | `LlmExecutionAdapter` → `LlmClient` → Anthropic Java SDK, structured outputs | one model call per generative step, inside the run's token/cost budget | `ANTHROPIC_API_KEY` + `APP_AGENTIC_LLM_ENABLED=true` |

The mode is chosen per run. It is shown in the run's `executionNotice`, on every artifact (`origin`, `adapter`,
`model`), in the agent-result audit events, and as a banner on every Markdown view. `LLM` requested without a
configured client is refused (503). Deterministic stages (documentation, readiness, policy engine, test runners) are
labeled `DETERMINISTIC` in both modes.

## 3. Task states

`PENDING, READY, RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED, SKIPPED, INVALIDATED, CANCELLED`
(`engine/TaskState.java`). Every transition is persisted, and the store appends a `TASK_TRANSITION` audit event for it
automatically.

```
PENDING ─(all deps SUCCEEDED)→ READY ─┬─(prior output with same fingerprint)→ SUCCEEDED (reused)
                                      └→ RUNNING ─┬─ gates pass ───────────────→ SUCCEEDED
                                                  ├─ gates pass + checkpoint ──→ WAITING_APPROVAL ─→ SUCCEEDED | FAILED (rejected)
                                                  └─ gate / agent failure ─────→ FAILED
SKIPPED      ← a dependency is FAILED / SKIPPED / CANCELLED
INVALIDATED  ← its output was invalidated, or its input fingerprint changed (re-enters scheduling)
CANCELLED    ← the run was cancelled while the task was unfinished
resume: FAILED / SKIPPED / CANCELLED / interrupted RUNNING → PENDING
```

Run statuses: `CREATED, RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED, CANCELLED, STOPPED, INTERRUPTED, PAUSED`.

## 4. Scheduling: sequential, parallel, synchronized

One drive loops in rounds (`WorkflowEngine.schedule`):

1. **Skip**: schedulable tasks with a blocking dependency become SKIPPED. Independent branches continue.
2. **Promote**: PENDING/INVALIDATED become READY only when every dependency is SUCCEEDED. This is the synchronization
   barrier, and a dependency in WAITING_APPROVAL does not count as SUCCEEDED.
3. **Dispatch**: each READY task's inputs are resolved to versioned refs and fingerprinted. It is either reused or
   started, up to `app.workflow.max-parallelism` in flight. With `max-parallelism=1` the run is strictly sequential in
   topological order (`SequentialExecutionTest`).
4. **Wait** for any in-flight task to finish. A stop or cancel request ends dispatching. In-flight tasks finish first.

Evidence: `WorkflowEngineTest#independentBranches_runInParallel_andJoinWaitsForBoth` makes both branches wait at a
two-party barrier, which would time out if they ran one after the other. The scenario timelines show `tests`, `security`,
and `docs` overlapping.

## 5. Cross-stage context, versioned artifacts, lineage

- Every execution writes **new artifact versions** (`requirement-spec@v2`). Nothing is overwritten.
- A task's current output is recorded as exact artifact ids (`outputRefsJson`). Downstream tasks receive **refs** to
  exactly those versions: name, version, content hash, producer.
- An agent can read **only the inputs it declared**. Any other read throws. Each read is recorded, and **lineage
  edges** are written from every produced artifact to the inputs actually read (with their hashes). Gate checks read
  through `peek`, which is not recorded.
- The **decision lineage** of a version (`GET /runs/{id}/artifacts/{name}/lineage?version=N`) includes:
  - input versions and hashes, and the agent@version
  - the input fingerprint
  - the agent's structured result (summary, decisions with rationale, assumptions, risks, evidence)
  - the entry and exit gate outcomes
  - the approvals bound to it, and its consumers

## 6. Restart, resume, and re-planning

**Input fingerprint** (`Hashes.fingerprint`): SHA-256 over the agent key and version, the execution mode, the
requirement hash (only for agents that read the raw requirement: requirements and security), and every declared
input's content hash.

**Reuse**: before executing a READY task, the engine looks for a prior accepted, non-invalidated version with the
same fingerprint. If the task needs human approval, that version must also still have a VALID APPROVED approval. When
found, the task becomes SUCCEEDED without executing (`TASK_REUSED`).

**Resume** (`POST /runs/{id}/resume`; from FAILED, CANCELLED, STOPPED, INTERRUPTED, PAUSED):

1. FAILED, SKIPPED, CANCELLED, and interrupted RUNNING tasks are reset to PENDING.
2. Each SUCCEEDED and WAITING_APPROVAL task is re-validated: upstream still SUCCEEDED, output still accepted and not
   invalidated, and the recomputed fingerprint equal to the recorded one.
3. Any task failing that check is INVALIDATED, with the reason recorded, and so are its descendants.
4. Valid work is never repeated. Invalidated work whose inputs turn out unchanged is reused.

**Crash recovery**: at startup, any run still RUNNING is marked INTERRUPTED (`RUN_INTERRUPTED`). Resume then treats its
RUNNING tasks as interrupted. If an attempt had already stored and accepted its output before the crash, that output
is reused.

**Dynamic re-planning**:
- `PUT /runs/{id}/requirement` amends the requirement. The audit event records the old and new hash and the reason,
  never the text.
- `POST /runs/{id}/tasks/{task}/invalidate` declares an output invalid.
- On the next resume, the fingerprint check invalidates exactly the affected tasks and their dependents, and preserves
  the rest. Affected approvals go stale and are requested again.

Demonstrated by the greenfield scenario: a formatting-only amendment re-ran 2 of 7 tasks and reused 5, and the
release approval stayed valid.

## 7. API

All endpoints are under `/api/v1/workflows` and require `X-Admin-Key`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/capabilities` | modes, agents with their gates and tools, limits, policies, workspace status |
| GET | `/definitions/standard` | the standard graph |
| POST | `/definitions/validate` | validate a graph (errors, order, layers) |
| POST | `/runs` | submit a requirement (`title`, `requirement`, `mode?`, `definition?`, `demoFaults?`, `testExecution?` = `PLAN_VERIFICATION`\|`COMMAND`) → 201 CREATED |
| GET | `/runs`, `/runs/{id}` | list; status with every task, its gates, outputs, and approvals |
| POST | `/runs/{id}/start` \| `/resume` | 202 (async by default) |
| POST | `/runs/{id}/stop` \| `/cancel` | safe-stop / cancel (`{actor}`) |
| POST | `/runs/{id}/tasks/{task}/decision` | `{decision, reviewer, comment, artifactVersions}`; bound to exact versions |
| POST | `/runs/{id}/tasks/{task}/invalidate` | `{reason, actor}` |
| PUT | `/runs/{id}/requirement` | `{requirement, reason, actor}`; re-planned on resume |
| POST | `/runs/{id}/workspace/rollback` | reset the worktree to the base commit |
| POST | `/runs/{id}/promotion`, `/promotion/compensate` | promote the approved patch to a local branch / undo it |
| GET | `/runs/{id}/artifacts`, `/artifacts/{name}?version=`, `/artifacts/{name}/lineage?version=` | versioned artifacts and decision lineage |
| GET | `/runs/{id}/approvals`, `/history`, `/timeline`, `/report.md`, `/audit/verify`, `/metrics` | review and audit views |
| GET | `/metrics` | metrics over all runs |
| POST | `/scenarios/{greenfield\|brownfield\|ambiguous}?mode=` | run a scenario, returns Markdown |

## 8. CLI

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--app.workflow.cli.scenario=all --server.port=0"
# writes target/workflow-scenarios/{greenfield,brownfield,ambiguous}.md, prints one verdict line each,
# exits 0 if every evidence check passed
```
