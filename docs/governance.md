# Governance, failure recovery, audit, and metrics

## 1. Human approval checkpoints

A task stops in `WAITING_APPROVAL` after its exit gate passes when any of these apply. The reasons are combined and
shown as the task's `reviewReason`:

| Checkpoint | Trigger | Where |
|---|---|---|
| Graph checkpoint | node has `requiresApproval` (standard graph: `release`) | `WorkflowDefinition.standard()` |
| Policy checkpoint | task key listed in `app.workflow.policy.approval-required-tasks` | `WorkflowService.create` |
| Clarification | requirement analysis found ambiguities (with `clarify-ambiguities=true`) | `RequirementAnalysisAgent` |
| Protected paths | the patch touches `app.workflow.policy.protected-paths` | `ImplementationAgent` |
| Fallback output | the output came from the deterministic fallback after an LLM failure | `WorkflowEngine.reviewReason` |
| **Promotion (merge)** | promotion is a separate explicit human action, allowed only with a valid approved release | `WorkflowService.promote` |

**Binding.** A decision (`POST /runs/{id}/tasks/{task}/decision`) must name the version of every output the reviewer
looked at (`artifactVersions`). If any differs from the current output, the decision is refused with `409 Stale
review`. Each decision is stored as a `workflow_approval` row bound to the exact artifact id, version, and **content
hash**, together with the reviewer identity, comment, and timestamp. The `APPROVED` / `REJECTED` audit event carries the
same binding.

**Invalidation of approvals.** An approval is VALID only while its artifact version is the task's current output. It
becomes STALE, with a reason and timestamp and an `APPROVAL_STALE` event, when:
- the task produces a new version (re-run after a requirement or upstream change),
- the output is explicitly invalidated, or
- re-planning supersedes it.

Stale approvals are never deleted. When the same version is reused after re-planning, its approval stays VALID
(`WorkflowEngineTest#approvalSurvivesReplanning_whenTheApprovedVersionIsReused`).

**Promotion binds transitively.** The readiness report lists its `approvalSubject`: the exact
`change-patch@vN#hash`, `test-report`, and `policy-report` versions it vouches for. Promotion requires all of these:
- a VALID APPROVED approval of the current readiness version,
- that version's subject containing the current patch label,
- the caller naming that patch version, and
- `confirm=true`.

Promotion commits the patch to a local branch `workflow/run-{id}-v{n}` in the orchestrator's sandbox clone. It never
pushes, never merges into the source repository, and never deploys.

## 2. Configurable security and change-control policies

All policies are in `app.workflow.policy.*` (see `application.properties`) and can be overridden per environment or
through environment variables.

| Policy | Rule | Severity | Retry? |
|---|---|---|---|
| POL-SECRET | credential-shaped value in requirement, any artifact, or the patch diff | BLOCKER | never |
| POL-SCOPE | requirement contains a `forbidden-terms` phrase (default: payment processing) | BLOCKER | never |
| POL-AUTHZ | SECURITY-layer component changed without a HIGH risk naming it with a mitigation | BLOCKER | never |
| POL-PROTECTED / POL-TENANT / POL-PII | protected paths touched / tenant-scoped data / personal data | WARNING | n/a |
| Change control (`ChangeControlPolicy`) | path traversal, absolute, backslash, `.git/**`, outside `allowed-patch-paths`, duplicates, > `max-patch-files`, > `max-patch-bytes`, credential-shaped content | BLOCKER (nothing is written) | never |
| Validation commands | only names in `validation-commands`, run via `patch-validation` | gate | never |

## 3. Untrusted input and tool restriction

- **Model output** is parsed only into typed records (structured outputs). Nothing in it is executed. The patch schema
  (`PatchProposal`) can only express whole-file writes: there is no field for commands, deletions, or renames.
- **Repository content** given to the model (impacted files' current text) is wrapped in `<repository_file>` tags,
  redacted, size-capped, and declared as untrusted context in the system prompt.
- **Requirement text** is wrapped in delimiters and declared as data.
- **Tools** are enforced per agent (`ToolPolicy`, `AgentRequest.requireTool`). A violation fails the task and is never
  retried.
- **Commands**: no shell, ever (`ProcessRunner` uses argv). Only git, the named validation commands, and the
  configured test command run. Test-class arguments substituted into `{tests}` must be existing files with
  identifier-only names.
- **Paths**: patches are checked by change control first, then the workspace re-checks that every resolved path stays
  inside the worktree.
- **Limits**:
  - per-attempt timeout (`limits.task-timeout`, applied via the agent pool)
  - per-command timeout (`workspace.command-timeout`, test runner `timeout`)
  - per-run token and estimated-cost budget (`limits.max-tokens-per-run`, `max-cost-usd-per-run`)
  - per-call output cap (`max-output-tokens-per-call`)

## 4. Failure recovery

| Failure | Classification | Automatic retry | Fallback (LLM mode) | Compensation |
|---|---|---|---|---|
| timeout, HTTP 429/5xx, connection error | `Transient` | **yes**: up to `retry.max-attempts` per drive, exponential backoff (`initial-backoff × multiplier^(n-2)`, capped at `max-backoff`) | after retries are exhausted | worktree reset after each failed attempt |
| refusal, unparseable or truncated output, agent reports FAILED | `AgentFailure` | no | yes | worktree reset |
| token/cost budget spent | `BudgetExceeded` | no | yes | worktree reset |
| entry/exit gate failure, policy violation | gate | **never** | **never** | worktree reset (exit gate) |
| approval rejected | human | **never**: needs an amendment or invalidation, then resume | never | none |
| tool not permitted, unexpected exception | bug | never | never | worktree reset |

**Defined fallback path** (`app.workflow.fallback=deterministic-with-review`, the default):
- An LLM-mode agent that cannot complete is re-executed on its deterministic path (`AgentRequest.asFallback()`, which
  removes the LLM tool).
- The output is labeled `DETERMINISTIC_DEMO` with `fallback=true`, the event `TASK_FALLBACK` is recorded, and the task
  is gated on human approval.
- With `fallback=fail`, the task fails instead.
- Demo mode has no fallback.

**Safe-stop** (`POST /stop`) finishes in-flight tasks and starts nothing new. Retry backoff is interrupted, all work is
kept, and the run becomes STOPPED (resumable).

**Cancel** (`POST /cancel`) does the same, then marks unfinished tasks CANCELLED and rolls back the workspace. The run
becomes CANCELLED, and SUCCEEDED outputs are kept for resume.

## 5. Rollback and compensation boundaries

| Effect | Reversible? | Mechanism |
|---|---|---|
| Uncommitted changes in the run's worktree | **yes** | `WorkspaceManager.reset`: `reset --hard` + `clean -fd` to the base commit. Runs on failed attempts, cancel, and `POST /workspace/rollback`. Audited as `WORKSPACE_ROLLED_BACK`, with the worktree status after rollback |
| Local branch created by promotion in the sandbox clone | **yes**, if it still points at the promoted commit | `POST /promotion/compensate`: `git branch -D`. Refused if the branch moved, so someone else's work is never deleted |
| Workflow state (tasks, artifacts) | not "rolled back"; superseded | artifacts are immutable versions; invalidation plus re-execution supersedes them |
| Source repository | **nothing to reverse** | never written: worktrees live in a private sandbox clone |
| Pushes, merges into real branches, deployments | **not implemented** | the module never performs them |
| LLM calls already made (cost, data already sent to the API) | **no** | recorded (tokens, estimated cost), cannot be undone |
| Audit events | **no, by design** | append-only |

## 6. Audit

`AuditLog` is the only writer of `workflow_event`. It records:
- **identity:** run, task, attempt, agent@version, and actor (`engine`, `system:startup`, or a reviewer/operator name);
- **what happened:** event type, from → to state, versioned artifact refs (`name@vN#hash`), a message, and structured
  details (gate outcomes, retry delay, approval binding with reviewer and timestamp, agent decisions, evidence,
  assumptions, risks, usage);
- **mode and model:** execution mode and model.

| Covered | Event types |
|---|---|
| state transitions | `TASK_TRANSITION` (automatic for every task state change) |
| retries | `TASK_ATTEMPT_STARTED/FAILED`, `TASK_RETRY_SCHEDULED`, `TASK_RETRIES_EXHAUSTED` |
| fallback | `TASK_FALLBACK` |
| rollback | `WORKSPACE_ROLLED_BACK`, `PROMOTION_COMPENSATED` |
| safe-stop | `RUN_STOP_REQUESTED`, `RUN_STOPPED`, `RUN_CANCEL_REQUESTED`, `RUN_CANCELLED` |
| approvals | `APPROVAL_REQUESTED`, `APPROVED`, `REJECTED`, `APPROVAL_STALE` |
| validation | `ENTRY/EXIT_GATE_PASSED/FAILED`, `VALIDATION_COMMAND`, `AGENT_RESULT` |

**Redaction.** Every message, ref, and details payload passes through `SecretPatterns.redact` before it is hashed,
stored, or logged. Prompts and raw requirement text are never put into events: a requirement amendment records hashes
and the reason only.

**Append-only interface.** `WorkflowEventRepository` exposes only `save` and finders: no update, no delete. Every column
is `updatable=false`, and `@PreUpdate` throws.

**Integrity (tamper evidence).** Each event stores `hash = SHA-256(prevHash + canonical fields)` and a contiguous
per-run `sequence`. `GET /runs/{id}/audit/verify` recomputes the chain and reports the first broken link.
`WorkflowEngineTest#auditChain_isIntact_andDetectsTampering` edits a row with SQL and the verifier catches it.

**Limitations, stated plainly.**
- Anyone with direct database write access can rewrite a run's whole chain consistently, because it is not anchored
  outside the database. There is no external timestamping, no WORM storage, and no signing key.
- H2 is in-memory, so the audit log is lost on restart, along with everything else.
- Redaction is pattern-based: it catches the listed credential formats, not every possible secret.
- An event is committed in its own transaction, so a state write that subsequently failed can leave an event
  describing an attempted transition.
- The per-run lock that keeps the chain linear is in-process (single instance).

## 7. Metrics

Computed from durable data by `WorkflowMetricsService`: run rows plus the audit log. Exposed at `GET /metrics` (all runs)
and `GET /runs/{id}/metrics`. The engine also emits Micrometer counters (`workflow.task.retries`,
`workflow.task.retries_exhausted`, `workflow.task.fallbacks`, `workflow.task.reused`, `workflow.workspace.rollbacks`,
`workflow.runs.completed{outcome}`, `workflow.drive.duration`) on `/actuator/prometheus`. Those are per-process
conveniences. The definitions of record are these:

| Metric | Definition |
|---|---|
| **Run success rate** | `succeeded / terminal`, where terminal = runs whose **current** status is SUCCEEDED, FAILED, or CANCELLED. Open runs (CREATED, RUNNING, WAITING_APPROVAL, STOPPED, PAUSED, INTERRUPTED) are excluded and counted separately. A FAILED run later resumed to SUCCEEDED counts once, as SUCCEEDED. |
| **Retry frequency** | `TASK_RETRY_SCHEDULED events / TASK_ATTEMPT_STARTED events`: the fraction of agent invocations followed by an **automatic** retry. Manual resumes and fallback executions are not counted. |
| **Rollback frequency** | `runs with ≥1 WORKSPACE_ROLLED_BACK / runs that created a workspace`, plus the raw count of rollback events. |
| **End-to-end execution latency** | For SUCCEEDED runs: `completedAt − createdAt` in ms, reported as count, mean, p50, p95 (nearest rank), and max. **Includes** approval waits and time between resumes. `activeExecution` is the same statistic over the sum of each run's drive durations (`RUN_DRIVE_ENDED.durationMs`), which excludes idle and approval time. |
| **MTTR** | Per (run, task): an incident opens at the first failure signal while none is open (`TASK_ATTEMPT_FAILED`, or a transition to FAILED). It closes at the next transition of that task to SUCCEEDED, by retry, fallback, resume, reuse, or approval. MTTR = mean(close − open) over **closed** incidents only. Open incidents are reported as `unrecoveredIncidents`. |

Each definition string is also returned in the `definitions` field of the metrics response.
