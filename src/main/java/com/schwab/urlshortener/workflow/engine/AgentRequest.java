package com.schwab.urlshortener.workflow.engine;

import com.schwab.urlshortener.agentic.model.ExecutionMode;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Everything a stage agent receives for one execution:
 * <ul>
 *   <li><b>intent and constraints</b>: the agent's task, with run-level constraints appended;</li>
 *   <li><b>acceptance criteria</b>: the exit-gate criteria its output will be judged against;</li>
 *   <li><b>repository context</b>: base commit, isolated workspace (if any), catalog size;</li>
 *   <li><b>approved upstream artifacts</b>: versioned refs to exactly the declared inputs, each the producing
 *       task's accepted output (and, for approval-gated producers, the approved version);</li>
 *   <li><b>allowed tools and limits</b>: enforced here, at the boundary, not on trust.</li>
 * </ul>
 *
 * Lineage: an agent can only read inputs it declared, and every read is
 * recorded. The inputs it actually read become the lineage edges of everything
 * it produces. Gate criteria read through {@link #peek}, which is not recorded.
 */
public class AgentRequest {

    public record RepositoryContext(String baseCommit, Path workspace, int catalogComponents) {
    }

    public record Limits(java.time.Duration timeout, long remainingTokens, double remainingCostUsd, long maxOutputTokensPerCall) {
    }

    private final Long runId;
    private final String taskKey;
    private final int attempt;
    private final String agentId;
    private final String title;
    private final String requirement;
    private final ExecutionMode mode;
    private final boolean fallback;
    private final String intent;
    private final List<String> constraints;
    private final List<String> acceptanceCriteria;
    private final RepositoryContext repository;
    private final ToolPolicy toolPolicy;
    private final Limits limits;
    private final Map<String, ArtifactRef> inputs;
    private final BiFunction<ArtifactRef, Class<?>, Object> loader;
    private final Map<String, Object> cache = new ConcurrentHashMap<>();
    private final Set<String> accessed = ConcurrentHashMap.newKeySet();

    public AgentRequest(Long runId, String taskKey, int attempt, String agentId, String title, String requirement,
                        ExecutionMode mode, boolean fallback, String intent, List<String> constraints,
                        List<String> acceptanceCriteria, RepositoryContext repository, ToolPolicy toolPolicy, Limits limits,
                        Map<String, ArtifactRef> inputs, BiFunction<ArtifactRef, Class<?>, Object> loader) {
        this.runId = runId;
        this.taskKey = taskKey;
        this.attempt = attempt;
        this.agentId = agentId;
        this.title = title;
        this.requirement = requirement;
        this.mode = mode;
        this.fallback = fallback;
        this.intent = intent;
        this.constraints = List.copyOf(constraints);
        this.acceptanceCriteria = List.copyOf(acceptanceCriteria);
        this.repository = repository;
        this.toolPolicy = toolPolicy;
        this.limits = limits;
        this.inputs = Map.copyOf(inputs);
        this.loader = loader;
    }

    public Long runId() {
        return runId;
    }

    public String taskKey() {
        return taskKey;
    }

    public int attempt() {
        return attempt;
    }

    /** {@code key@version} of the executing agent. */
    public String agentId() {
        return agentId;
    }

    public String title() {
        return title;
    }

    /**
     * The raw requirement text. Only available to agents that declare
     * {@link StageAgent#readsRequirement()}, because only those carry it in their
     * input fingerprint. Any other agent reading it would make reuse unsound.
     */
    public String requirement() {
        if (requirement == null) {
            throw new IllegalStateException("Agent '" + agentId + "' did not declare readsRequirement(); use the requirement-spec artifact");
        }
        return requirement;
    }

    public ExecutionMode mode() {
        return mode;
    }

    /** True when this execution is the defined fallback: the deterministic path after an LLM failure. */
    public boolean fallback() {
        return fallback;
    }

    public String intent() {
        return intent;
    }

    public List<String> constraints() {
        return constraints;
    }

    public List<String> acceptanceCriteria() {
        return acceptanceCriteria;
    }

    public RepositoryContext repository() {
        return repository;
    }

    public Optional<Path> workspace() {
        return Optional.ofNullable(repository.workspace());
    }

    public ToolPolicy toolPolicy() {
        return toolPolicy;
    }

    public Limits limits() {
        return limits;
    }

    public Map<String, ArtifactRef> inputRefs() {
        return inputs;
    }

    /** Throws {@link AgentFailures.ToolNotPermitted} unless the agent's policy allows {@code tool}. */
    public void requireTool(ToolPolicy.Tool tool) {
        if (!toolPolicy.allows(tool)) {
            throw new AgentFailures.ToolNotPermitted(agentId, tool);
        }
    }

    public <T> T input(String name, Class<T> type) {
        requireTool(ToolPolicy.Tool.READ_ARTIFACTS);
        T value = load(name, type);
        accessed.add(name);
        return value;
    }

    /** Reads a declared input WITHOUT recording it as used: a gate checking an input is verification, not production. */
    public <T> T peek(String name, Class<T> type) {
        return load(name, type);
    }

    public boolean hasInput(String name) {
        return inputs.containsKey(name);
    }

    private <T> T load(String name, Class<T> type) {
        ArtifactRef ref = inputs.get(name);
        if (ref == null) {
            throw new UndeclaredInputException(agentId, name, inputs.keySet());
        }
        return type.cast(cache.computeIfAbsent(name, n -> loader.apply(ref, type)));
    }

    /** Refs of the inputs actually read so far, in declaration order. */
    public List<ArtifactRef> accessedRefs() {
        Map<String, ArtifactRef> ordered = new LinkedHashMap<>(inputs);
        ordered.keySet().retainAll(accessed);
        return List.copyOf(ordered.values());
    }

    /** A copy of this request for the defined fallback: same inputs, deterministic path, no model tool. */
    public AgentRequest asFallback() {
        java.util.EnumSet<ToolPolicy.Tool> tools = toolPolicy.allowedTools().isEmpty()
                ? java.util.EnumSet.noneOf(ToolPolicy.Tool.class) : java.util.EnumSet.copyOf(toolPolicy.allowedTools());
        tools.remove(ToolPolicy.Tool.LLM_GENERATE);
        return new AgentRequest(runId, taskKey, attempt, agentId, title, requirement, mode, true, intent, constraints,
                acceptanceCriteria, repository, new ToolPolicy(tools), limits, inputs, loader);
    }

    /** Thrown when an agent reads an artifact it did not declare. That's a bug in the agent, not in the run. */
    public static class UndeclaredInputException extends IllegalStateException {
        public UndeclaredInputException(String agent, String name, Set<String> declared) {
            super("Agent '" + agent + "' read undeclared input '" + name + "' (declared: " + declared + ")");
        }
    }
}
