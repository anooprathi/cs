package com.schwab.urlshortener.workflow.graph;

import com.schwab.urlshortener.workflow.graph.AgentSpecs.AgentSpec;
import com.schwab.urlshortener.workflow.graph.GraphValidationResult.ErrorCode;
import com.schwab.urlshortener.workflow.graph.GraphValidationResult.GraphError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GraphValidatorTest {

    private final GraphValidator validator = new GraphValidator();

    /** Agent specs mirroring the real seven agents' artifact contracts, plus a few for negative cases. */
    private final Map<String, AgentSpec> specs = Map.of(
            "requirement-analysis", spec("requirement-analysis", List.of(), List.of("requirement-spec")),
            "architecture-design", spec("architecture-design", List.of("requirement-spec"), List.of("architecture-design")),
            "implementation", spec("implementation", List.of("requirement-spec", "architecture-design"), List.of("implementation-plan", "change-patch")),
            "test-generation-execution", spec("test-generation-execution",
                    List.of("requirement-spec", "architecture-design", "implementation-plan", "change-patch"), List.of("test-plan", "test-report")),
            "security-policy", spec("security-policy", List.of("requirement-spec", "architecture-design", "implementation-plan", "change-patch"),
                    List.of("risk-assessment", "policy-report")),
            "documentation", spec("documentation", List.of("requirement-spec", "architecture-design", "implementation-plan"), List.of("documentation")),
            "release-readiness", spec("release-readiness", List.of("requirement-spec", "implementation-plan", "change-patch", "test-report",
                    "policy-report", "risk-assessment", "documentation"), List.of("readiness-report")),
            "dup-producer", spec("dup-producer", List.of(), List.of("requirement-spec")),
            "needs-ghost", spec("needs-ghost", List.of("ghost"), List.of("x")));

    private final AgentSpecs lookup = key -> Optional.ofNullable(specs.get(key));

    private static AgentSpec spec(String key, List<String> consumes, List<String> produces) {
        return new AgentSpec(key, WorkflowStage.IMPLEMENTATION, consumes, produces);
    }

    private static TaskNode node(String key, String agent, String... deps) {
        return new TaskNode(key, agent, List.of(deps), false);
    }

    private List<ErrorCode> codes(GraphValidationResult r) {
        return r.errors().stream().map(GraphError::code).toList();
    }

    @Test
    void standardGraph_isValid_withThreeWayParallelLayer() {
        GraphValidationResult r = validator.validate(WorkflowDefinition.standard(), lookup);

        assertThat(r.valid()).as(String.valueOf(r.errors())).isTrue();
        assertThat(r.parallelLayers()).containsExactly(List.of("requirements"), List.of("design"), List.of("implementation"),
                List.of("tests", "security", "docs"), List.of("release"));
        assertThat(r.topologicalOrder()).startsWith("requirements").endsWith("release");
        assertThat(r.producers()).containsEntry("change-patch", "implementation");
    }

    @Test
    void cycle_isRejected_withThePath() {
        WorkflowDefinition d = new WorkflowDefinition("c", 1, List.of(
                node("a", "requirement-analysis", "c"), node("b", "architecture-design", "a"), node("c", "documentation", "b")));

        GraphValidationResult r = validator.validate(d, lookup);

        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(e -> e.code() == ErrorCode.CYCLE && e.message().matches(".*a -> c -> b -> a.*|.*-> .* -> .* -> .*"));
        assertThat(r.topologicalOrder()).isEmpty();
    }

    @Test
    void selfUnknownAndDuplicateDependencies_areRejected() {
        WorkflowDefinition d = new WorkflowDefinition("bad", 1, List.of(
                node("a", "requirement-analysis", "a"),
                node("b", "architecture-design", "nowhere"),
                new TaskNode("c", "documentation", List.of("a", "a"), false)));

        assertThat(codes(validator.validate(d, lookup)))
                .contains(ErrorCode.SELF_DEPENDENCY, ErrorCode.UNKNOWN_DEPENDENCY, ErrorCode.DUPLICATE_DEPENDENCY);
    }

    @Test
    void duplicateKeys_blankKeys_unknownAgents_andEmptyGraphs_areRejected() {
        assertThat(codes(validator.validate(new WorkflowDefinition("e", 1, List.of()), lookup))).containsExactly(ErrorCode.EMPTY_GRAPH);
        WorkflowDefinition d = new WorkflowDefinition("bad", 1, List.of(
                node("a", "requirement-analysis"), node("a", "requirement-analysis"), node(" ", "requirement-analysis"), node("z", "no-such-agent")));

        assertThat(codes(validator.validate(d, lookup))).contains(ErrorCode.DUPLICATE_TASK, ErrorCode.BLANK_TASK_KEY, ErrorCode.UNKNOWN_AGENT);
    }

    @Test
    void consumingAnArtifactFromANonAncestor_isAnInvalidDependency() {
        // design consumes requirement-spec, but has no dependency path to its producer: they'd race.
        WorkflowDefinition d = new WorkflowDefinition("race", 1, List.of(
                node("requirements", "requirement-analysis"), node("design", "architecture-design")));

        GraphValidationResult r = validator.validate(d, lookup);

        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INPUT_NOT_UPSTREAM);
            assertThat(e.message()).contains("Add a dependency path from 'requirements' to 'design'");
        });
    }

    @Test
    void missingInputs_andDuplicateProducers_areRejected() {
        WorkflowDefinition d = new WorkflowDefinition("bad", 1, List.of(
                node("requirements", "requirement-analysis"), node("dup", "dup-producer"), node("ghost", "needs-ghost")));

        assertThat(codes(validator.validate(d, lookup))).contains(ErrorCode.DUPLICATE_PRODUCER, ErrorCode.MISSING_INPUT);
    }

    @Test
    void ancestorsAndDescendants_areTransitive() {
        Map<String, TaskNode> nodes = new java.util.LinkedHashMap<>();
        WorkflowDefinition.standard().tasks().forEach(n -> nodes.put(n.key(), n));

        assertThat(GraphValidator.ancestors(nodes).get("release")).contains("requirements", "design", "implementation", "tests", "security", "docs");
        assertThat(GraphValidator.descendants(nodes).get("implementation")).containsExactlyInAnyOrder("tests", "security", "docs", "release");
        assertThat(GraphValidator.descendants(nodes).get("docs")).containsExactly("release");
    }
}
