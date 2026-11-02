# Limitations and trade-offs

This is a production-informed prototype. Everything below is deliberate, known, or both. Nothing here is hidden
elsewhere in the docs as a capability.

## Simulated or unimplemented: read this first

| Capability | Status |
|---|---|
| **AI analysis in the default mode** | **Not AI.** DETERMINISTIC_DEMO agents are keyword rules and templates. The orchestration, gates, policies, workspace, approvals, retries, audit, and metrics around them are real. |
| **Real LLM execution** | Implemented: Anthropic Java SDK, structured outputs, budget, retry mapping, fallback. Tested only against mocked or scripted clients. **Not executed against the live API** in this repository's verification. The opt-in live smoke test (`AnthropicLiveSmokeTest`) covers one agent call, and it was not run. |
| **Code generation in demo mode** | The demo "patch" is a generated **change-record document** under `docs/changes/`, not product code. It does flow through the real patch pipeline: change control, git worktree, validation commands, approval, promotion, compensation. Product-code patches require LLM mode. |
| **Test execution** | The default runner (`plan-verification`) checks the generated test plan against the working tree and **runs no product tests** (`productTestsExecuted=false`, and readiness check RR-6 says so). The `command` runner really executes a configured command in the worktree, validated with a real child process in tests. Running `mvn test` against a patched worktree was not exercised in verification, because it is slow and needs network access to Maven. |
| **Merging / deploying** | **Not implemented.** "Promotion" commits the approved patch to a *local branch in the orchestrator's private sandbox clone*. Nothing is pushed, merged into the source repository, or deployed. |
| **Injected faults** | The recoverable failure in the greenfield scenario is an injected transient fault (`demoFaults`), accepted only in DETERMINISTIC_DEMO and recorded on the run and in its audit trail. |
| **Simulated latency** | In the `dev` profile, demo agent steps sleep `app.workflow.demo.simulated-latency` (150 ms) so parallel overlap is visible in timelines. The value is recorded in each drive's `RUN_STARTED` details. |
| **Scripted approvals** | In the scenarios, approvals are made by `scenario-reviewer (scripted)` through the same version-bound decision API a person uses. |

## Platform limits

- **In-memory H2.** All workflow state, artifacts, approvals, and the audit log are lost on restart. That is why
  workspaces are namespaced per boot (`app.workflow.workspace.namespace`), so run ids that restart at 1 can't collide with
  yesterday's worktrees. Old namespaces accumulate under the workspace root and are not cleaned up automatically.
- **Single instance.**
  - The one-driver-per-run claim, the audit chain's per-run lock, and the budget ledger are all in-process.
  - Multiple instances would need a DB lease or row lock for run ownership, a serialized audit sequence (e.g. a DB
    sequence per run), and a shared ledger.
  - Startup recovery (RUNNING → INTERRUPTED) assumes no other live instance owns the run.
- **Synchronous scenario endpoint.** `POST /scenarios/{name}` runs a whole scenario in the request thread, which takes
  seconds in demo mode.

## Governance trade-offs

- **Reviewer identity is self-declared.** The operator credential is one shared key, so `reviewer` is a name the
  caller supplies. It is recorded faithfully, but it is not authenticated individually. A real deployment needs
  per-person identity (OIDC) before approvals mean "this person".
- **Audit integrity is tamper-evident, not tamper-proof.** The per-run hash chain detects edits by anyone who doesn't
  recompute later hashes. A database administrator can rewrite a whole chain, because it is not anchored externally
  (no signing, timestamping authority, or WORM storage).
- **Redaction is pattern-based**: tenant keys, Anthropic keys, AWS access keys, private-key blocks, inline passwords,
  and bearer tokens. Other secret shapes are not caught. Artifacts derived from a requirement that contains a secret
  store that text (POL-SECRET and change control block the run, but the stored artifact remains).
- **Fallback trades autonomy for availability.** `deterministic-with-review` keeps an LLM run moving with labeled
  rule-based output, and requires a human to approve it. Set `app.workflow.fallback=fail` where demo-quality output
  is never acceptable.
- **Approvals bind to exact versions.** That makes them strict. Re-running a task that produces byte-identical content
  under a new version id (after an explicit invalidation) needs a fresh approval, while plain reuse keeps the old one.

## Engine trade-offs

- **Fingerprints are artifact-granular.** A change anywhere in an input artifact invalidates every consumer, even if
  the consumer doesn't use the changed field. Field-level dependency tracking would preserve more work, at the cost
  of complexity.
- **Requirement-reading agents.** Only `requirement-analysis` and `security-policy` read the raw requirement; the others
  see it through the versioned spec. So a formatting-only amendment re-runs exactly those two stages, and a substantive
  amendment re-runs everything downstream of the spec.
- **Timeouts are cooperative at the agent level.** A timed-out attempt is cancelled with an interrupt. Agent code
  blocked in uninterruptible I/O can keep its thread busy until it returns, though its result is ignored.
- **Budget is check-then-call.** One in-flight call can overshoot the token budget by at most one response
  (`max-output-tokens-per-call`). Cost is an estimate from configured prices, not a bill.
- **Metrics are recomputed by scanning events** (O(events)). That is fine at prototype scale. Production would maintain
  them incrementally.
- **Retries are per drive.** `max-attempts` bounds automatic attempts within one drive. A manual resume grants a new
  budget, and attempt numbers keep counting across drives.

## Security notes

- Prompt injection is **reduced, not prevented**: delimiters, explicit "data, not instructions" system text, typed
  outputs, deterministic gates, no execution of model content, a path allowlist, and human approval before
  promotion. A model can still be manipulated into proposing a bad but policy-compliant patch. That is what review is for.
- LLM mode sends the requirement text, the component catalog, upstream artifacts, and (for patch authoring) redacted
  excerpts of impacted source files to the Anthropic API. It is off by default.
- Validation and test commands run with the application's own OS permissions inside the worktree. There is no
  container or sandbox around the process beyond the worktree directory, argv-only execution, and timeouts.
