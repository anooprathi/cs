# CLAUDE.md

Guidance for Claude Code when working in `src/main/java/com/schwab/urlshortener/agentic/`.

The agentic SDLC orchestrator: requirement → REQUIREMENTS → IMPACT → PLAN → TESTS → RISKS → REVIEW, then a
human review decision. Design: `ARCHITECTURE.md` §8. Usage and the exact validation status: `README.md` §12.

**The non-negotiable invariant: real LLM output and deterministic demo output must never be confusable.**
- Mode is chosen in `SdlcOrchestrator.execute` and nowhere else. If you add a code path that substitutes
  `runDeterministic` output when an LLM call fails or is unavailable, you've broken the guarantee. LLM mode
  without an `LlmClient` bean is a `LlmUnavailableException` (503), and an LLM stage failure fails the run.
- Provenance (`ArtifactSource`, model, tokens, duration) is stamped by the orchestrator, never self-reported
  by an agent. REVIEW is always `DETERMINISTIC_GUARDRAIL`.
- Every rendered document starts with `MarkdownRenderer.banner(...)`, and every artifact with
  `provenance(...)`. Keep both if you add a new output format.
- Don't describe demo mode as "AI", "intelligent", or "analysis" anywhere, in docs or in output text.

**Adding or changing an agent.** Implement `SdlcAgent<T>` as a `@Component`. It's picked up via
`List<SdlcAgent<?>>` injection and ordered by `dependsOn()` (Kahn's algorithm in `SdlcOrchestrator.order`;
a duplicate stage, a missing dependency, or a cycle fails at startup). Keep both paths in the same class:
`runDeterministic` and the prompts. The output type must be a record in `model/`, because in LLM mode it is
passed to the SDK as the structured-output JSON schema, so its `@JsonPropertyDescription`s are the model's
instructions. Upstream artifacts go into prompts via `PromptSupport.artifactBlock`, and the requirement only
via `requirementBlock` (it carries the untrusted-data delimiters).

**The quality gate (`review/QualityGateReviewer`) must stay deterministic.** Don't route it through the LLM.
New rules get a `RULE_*` constant and a test in `QualityGateReviewerTest` that triggers it with a crafted
violation. BLOCKER means approval is refused (`SdlcRunService.review`), so reserve it for structural problems,
not taste.

**`agentic/component-catalog.json`** (resources) is how the orchestrator knows this codebase. When you add,
rename, or move a significant class elsewhere in the repo, update its entry. `ComponentCatalogTest` fails if a
listed `path` or `tests` file disappears, but it can't notice a missing entry. Keywords are matched at word
start; avoid generic words (a past bug: `count` matched "click count" and invented a billing impact). After
changing keywords, run `DeterministicAgentsTest`.

**LLM boundary.** Only `llm/AnthropicLlmClient` touches SDK types. `AgenticLlmConfig` creates beans only when
`app.agentic.llm.enabled=true`. The key comes from `ANTHROPIC_API_KEY` via `fromEnv()`, never a Spring
property. Never log prompt or response text. `AnthropicClient` has `close()` but is not `AutoCloseable`.
`AnthropicLlmClientTest` mocks the SDK. `AnthropicLiveSmokeTest` is the only test that calls the real API,
it's opt-in (`AGENTIC_LIVE_SMOKE=true`), and it costs money, so don't enable it in CI without the owner's say-so.

**Persistence.** `SdlcOrchestrator.execute` is deliberately not `@Transactional` (LLM runs can take minutes).
`SdlcRun` has `@Version` so concurrent reviews resolve to one winner and one 409. Artifacts are write-once.
