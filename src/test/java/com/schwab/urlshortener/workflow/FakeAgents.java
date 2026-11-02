package com.schwab.urlshortener.workflow;

import com.schwab.urlshortener.workflow.engine.AgentFailures;
import com.schwab.urlshortener.workflow.engine.AgentRequest;
import com.schwab.urlshortener.workflow.engine.AgentResult;
import com.schwab.urlshortener.workflow.engine.ArtifactOrigin;
import com.schwab.urlshortener.workflow.engine.GateCriterion;
import com.schwab.urlshortener.workflow.engine.StageAgent;
import com.schwab.urlshortener.workflow.engine.ToolPolicy;
import com.schwab.urlshortener.workflow.graph.TaskNode;
import com.schwab.urlshortener.workflow.graph.WorkflowDefinition;
import com.schwab.urlshortener.workflow.graph.WorkflowStage;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Scriptable stage agents for engine tests. Four agents form a diamond:
 * <pre>
 *   fake-root (produces r) ─┬─▶ fake-left  (r → left) ─┬─▶ fake-join (left, right → joined)
 *                          └─▶ fake-right (r → right) ─┘
 * </pre>
 * Each test scripts behavior per task key through {@link Script}: what to
 * return, when to fail and how, and whether to block. Every invocation is
 * counted and its start and end recorded, so tests assert on what actually
 * executed rather than on what the engine reports.
 */
public final class FakeAgents {

    public record Content(String value) {
    }

    /** Per-test behavior and observations, shared by all fake agents. */
    public static final class Script {
        public final Map<String, Function<AgentRequest, AgentResult>> behavior = new ConcurrentHashMap<>();
        public final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();
        public final List<String> starts = Collections.synchronizedList(new ArrayList<>());
        public final List<String> ends = Collections.synchronizedList(new ArrayList<>());
        public final AtomicInteger concurrent = new AtomicInteger();
        public final AtomicInteger maxConcurrent = new AtomicInteger();
        public volatile boolean failExitGate;
        public volatile boolean failEntryGate;

        public void reset() {
            behavior.clear();
            invocations.clear();
            starts.clear();
            ends.clear();
            concurrent.set(0);
            maxConcurrent.set(0);
            failExitGate = false;
            failEntryGate = false;
        }

        public int count(String agentKey) {
            AtomicInteger n = invocations.get(agentKey);
            return n == null ? 0 : n.get();
        }
    }

    public static final Script SCRIPT = new Script();

    private FakeAgents() {
    }

    public static AgentResult ok(String name, String value) {
        return new AgentResult(AgentResult.Status.COMPLETED, "produced " + name, List.of(new AgentResult.Produced(name,
                new Content(value), ArtifactOrigin.DETERMINISTIC_DEMO, null)), List.of(), List.of(), List.of(),
                List.of(new AgentResult.Decision("emit " + value, "scripted")), null, null, AgentResult.Usage.NONE);
    }

    /** A diamond-graph definition over the fake agents; {@code approvalOnJoin} gates the join node. */
    public static WorkflowDefinition diamond(boolean approvalOnJoin) {
        return new WorkflowDefinition("fake-diamond", 1, List.of(
                new TaskNode("root", "fake-root", List.of(), false),
                new TaskNode("left", "fake-left", List.of("root"), false),
                new TaskNode("right", "fake-right", List.of("root"), false),
                new TaskNode("join", "fake-join", List.of("left", "right"), approvalOnJoin)));
    }

    static class Fake implements StageAgent {
        private final String key;
        private final List<String> consumes;
        private final String produces;
        private final ToolPolicy tools;

        Fake(String key, List<String> consumes, String produces, ToolPolicy tools) {
            this.key = key;
            this.consumes = consumes;
            this.produces = produces;
            this.tools = tools;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public WorkflowStage stage() {
            return WorkflowStage.IMPLEMENTATION;
        }

        @Override
        public String description() {
            return "fake " + key;
        }

        @Override
        public String intent() {
            return "test";
        }

        @Override
        public List<String> constraints() {
            return List.of();
        }

        @Override
        public List<String> consumes() {
            return consumes;
        }

        @Override
        public List<String> produces() {
            return List.of(produces);
        }

        @Override
        public boolean readsRequirement() {
            return key.equals("fake-root");
        }

        @Override
        public boolean supportsDeterministicFallback() {
            return true;
        }

        @Override
        public ToolPolicy toolPolicy() {
            return tools;
        }

        @Override
        public List<GateCriterion> entryCriteria() {
            return List.of(new GateCriterion("FAKE-ENTRY", "scripted entry gate",
                    in -> SCRIPT.failEntryGate && key.equals("fake-left") ? GateCriterion.Result.fail("scripted entry failure")
                            : GateCriterion.Result.pass("ok")));
        }

        @Override
        public List<GateCriterion> exitCriteria() {
            return List.of(new GateCriterion("FAKE-EXIT", "scripted exit gate",
                    in -> SCRIPT.failExitGate && key.equals("fake-left") ? GateCriterion.Result.fail("scripted exit failure")
                            : GateCriterion.Result.pass("ok")));
        }

        @Override
        public AgentResult execute(AgentRequest request) {
            SCRIPT.invocations.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            int now = SCRIPT.concurrent.incrementAndGet();
            SCRIPT.maxConcurrent.accumulateAndGet(now, Math::max);
            SCRIPT.starts.add(request.taskKey());
            try {
                consumes.forEach(c -> request.input(c, Content.class));
                Function<AgentRequest, AgentResult> b = SCRIPT.behavior.get(request.taskKey());
                return b != null ? b.apply(request) : ok(produces, request.taskKey() + "-out");
            } finally {
                SCRIPT.concurrent.decrementAndGet();
                SCRIPT.ends.add(request.taskKey());
            }
        }
    }

    /** Registers the four fake agents as Spring beans. */
    @TestConfiguration
    public static class Config {
        @Bean
        StageAgent fakeRoot() {
            return new Fake("fake-root", List.of(), "r", ToolPolicy.of(ToolPolicy.Tool.READ_ARTIFACTS));
        }

        @Bean
        StageAgent fakeLeft() {
            return new Fake("fake-left", List.of("r"), "left", ToolPolicy.of(ToolPolicy.Tool.READ_ARTIFACTS));
        }

        @Bean
        StageAgent fakeRight() {
            return new Fake("fake-right", List.of("r"), "right", ToolPolicy.of(ToolPolicy.Tool.READ_ARTIFACTS));
        }

        @Bean
        StageAgent fakeJoin() {
            return new Fake("fake-join", List.of("left", "right"), "joined", ToolPolicy.of(ToolPolicy.Tool.READ_ARTIFACTS));
        }
    }

    /** Behavior helpers. */
    public static Function<AgentRequest, AgentResult> transientFailures(int failures, String name) {
        return req -> {
            if (req.attempt() <= failures) {
                throw new AgentFailures.Transient("scripted transient failure on attempt " + req.attempt());
            }
            return ok(name, req.taskKey() + "-out");
        };
    }
}
