# Architecture

This repository is one Spring Boot 3.3 application with three layers that share infrastructure: the identity
model, the error envelope, JPA on H2, and configuration conventions.

| Layer | Package | What it is | Docs |
|---|---|---|---|
| URL shortener | `controller`, `service`, `tenant`, `billing`, `admin`, `ratelimit`, `security`, ... | The original product: multi-tenant short links, rate limiting, billing | `README.md` §1-11, `ARCHITECTURE.md` §1-7 |
| Agentic SDLC layer | `agentic` | Five reasoning agents (requirements, impact, planner, test designer, risk reviewer) behind a single-pass pipeline (`/api/v1/agentic`) | `README.md` §12, `ARCHITECTURE.md` §8 |
| **SDLC workflow orchestrator** | `workflow` | A separate orchestration module: an explicit 7-stage dependency graph with parallel scheduling, gates, persistent state, resume, versioned artifacts, lineage, governance, isolated git workspaces, audit, and metrics (`/api/v1/workflows`) | this file, `docs/orchestration.md`, `docs/governance.md` |

The workflow module reuses the agentic layer's agents as the generative step inside four of its seven stage
agents, rather than duplicating them. It adds no coupling in the other direction: nothing in `agentic` or
the URL shortener depends on `workflow`.

## Workflow module: packages

```
workflow/
├── graph/        WorkflowDefinition, TaskNode, GraphValidator          the explicit DAG and its validation
├── engine/       WorkflowEngine, WorkflowStore, StageAgent,             scheduling, state, gates, recovery
│                 AgentRequest/AgentResult, GateCriterion, TaskState,
│                 ToolPolicy, AgentFailures, Hashes, WorkflowArtifactRenderer
├── agents/       7 stage agents + AgentStageRunner, PolicyEngine,       the work behind each stage
│                 TestRunner (plan-verification | command), DemoChangeRecord
├── adapter/      ExecutionAdapter, DeterministicDemoAdapter,            demo vs real-LLM execution, budget
│                 LlmExecutionAdapter, AdapterRouter, BudgetLedger
├── workspace/    WorkspaceManager (git worktrees), ChangeControlPolicy,  isolated code changes
│                 PatchProposal, ChangePatch, ProcessRunner
├── audit/        AuditLog (append-only, hash-chained), SecretPatterns   audit and redaction
├── metrics/      WorkflowMetricsService                                 durable metrics
├── persistence/  WorkflowRun/Task/Artifact/LineageEdge/Approval/Event   JPA entities + repositories
├── scenario/     ScenarioService, ScenarioCli                           the three end-to-end demos
├── api/          WorkflowController, WorkflowRequests, WorkflowViews    REST API
└── WorkflowService, WorkflowReports, WorkflowConfig, WorkflowProperties
```

## Component view

```
            HTTP (X-Admin-Key, ROLE_ADMIN)                 CLI (--app.workflow.cli.scenario=...)
                      │                                                │
              WorkflowController                                  ScenarioCli → ScenarioService
                      └──────────────┬─────────────────────────────────┘
                                     ▼
                              WorkflowService ───────────── WorkflowReports / WorkflowMetricsService
                 (lifecycle, human checkpoints, promotion)               (timeline, summary, metrics)
                                     │ claim run, dispatch drive (driver pool)
                                     ▼
                              WorkflowEngine ──────────────────────────────────────────────┐
         graph snapshot → validate → prepare (resume/re-plan) → schedule rounds → finish     │
                                     │ submit READY tasks (task pool, ≤ max-parallelism)     │
                                     ▼                                                       │
       entry gate → attempt loop (timeout via agent pool, retry/backoff, fallback) → store   │
       versioned artifacts + lineage → exit gate → accept → SUCCEEDED | WAITING_APPROVAL     │
                                     │                                                       │
                StageAgent (7) ──── AgentStageRunner ──── AdapterRouter                      │
                    │                                      ├─ DeterministicDemoAdapter       │
                    │                                      └─ LlmExecutionAdapter ── LlmClient (Anthropic SDK)
                    ├─ PolicyEngine, TestRunner, DemoChangeRecord                            │
                    └─ WorkspaceManager ── ProcessRunner ── git (sandbox clone + worktrees)  │
                                                                                             ▼
             WorkflowStore ── JPA ── H2: workflow_run, workflow_task, workflow_artifact,
                                         workflow_lineage, workflow_approval, workflow_event
                         └── AuditLog (append-only, per-run SHA-256 chain, redaction)
```

## Data model

| Table | One row per | Key fields | Mutability |
|---|---|---|---|
| `workflow_run` | run | requirement text + hash + revision, execution mode, model, status, definition JSON snapshot, stop/cancel flags, base commit, workspace path, promotion | updated (with `@Version`) |
| `workflow_task` | graph node in a run | state, attempts, retries, reused, fallbackUsed, reviewReason, input fingerprint, `outputRefsJson` (current accepted versions), entry/exit gate JSON | updated (with `@Version`); every state change is audited |
| `workflow_artifact` | artifact version | name, version, content JSON + Markdown, content hash, input fingerprint, origin, adapter, model, fallback, agent result envelope, gates, accepted, invalidated, tokens/cost | immutable content; only `accepted` / `invalidated` / `gatesJson` flags change |
| `workflow_lineage` | (output, input) edge | output artifact id → input artifact id, version, hash | insert-only |
| `workflow_approval` | human decision on one artifact version | artifact id + version + hash, decision, reviewer, comment, decidedAt, status VALID/STALE, staleReason | insert, then VALID→STALE only |
| `workflow_event` | audit event | sequence, type, task, attempt, agent, from/to state, actor, mode, model, refs, message, details, prevHash, hash | **append-only** (no update/delete path; `@PreUpdate` throws) |

## Threads and transactions

- **Driver pool** (`workflowDriverExecutor`, 2-4 threads, bounded queue, 503 when full): one thread drives one run.
  With `app.workflow.async-execution=false` (the test profile) the request thread drives the run.
- **Task pool** (`workflowTaskExecutor`): task workers shared by all runs. Each run also caps its own in-flight tasks at
  `max-parallelism`.
- **Agent pool** (`workflowAgentExecutor`): runs each agent invocation so the worker can enforce the per-attempt timeout.
- **One driver per run**, enforced by an in-process claim (`WorkflowEngine.claim`). Every mutating human action claims
  the run too, so a decision can't interleave with a drive. This is correct for a single instance, and a documented
  limitation for several.
- **Short transactions**: `WorkflowStore` runs each state change in its own transaction and reloads the row first. The
  driver never holds a transaction across model calls or process execution. Acceptance of an output is atomic: artifacts are
  marked accepted and the task is moved to its success state in one transaction. Audit appends commit in their own
  transaction inside a per-run lock, so the hash chain cannot fork.

## Security boundaries

- `/api/v1/workflows/**` requires the operator identity (`X-Admin-Key` → `ROLE_ADMIN`). A tenant key gets 403, and no
  credential gets 401. This is one additive line in `SecurityConfig`.
- The source repository is read-only to the module. Patches are written only into a worktree of a private sandbox clone.
- Processes start only through `ProcessRunner` (argv, no shell, timeout, bounded output), and only for git, configured
  validation commands, or the configured test command.
- Model output and repository content are treated as untrusted data (details in `docs/governance.md`).
- The Anthropic API key is read by the SDK from `ANTHROPIC_API_KEY` only. It is never a Spring property, and it is never logged.
