package com.schwab.urlshortener.workflow.graph;

import java.util.List;

/**
 * An explicit SDLC dependency graph. A run snapshots its definition as JSON at
 * creation, so a later change to the default graph never changes how an
 * existing run resumes.
 */
public record WorkflowDefinition(String name, int version, List<TaskNode> tasks) {

    public WorkflowDefinition {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    /**
     * The default graph. After implementation, three branches run in parallel
     * (tests, security, documentation), and release readiness waits on all three:
     *
     * <pre>
     * requirements → design → implementation ─┬─→ tests ────┐
     *                                         ├─→ security ─┼─→ release (approval)
     *                                         └─→ docs ─────┘
     * </pre>
     */
    public static WorkflowDefinition standard() {
        return new WorkflowDefinition("standard-sdlc", 1, List.of(
                new TaskNode("requirements", "requirement-analysis", List.of(), false),
                new TaskNode("design", "architecture-design", List.of("requirements"), false),
                new TaskNode("implementation", "implementation", List.of("design"), false),
                new TaskNode("tests", "test-generation-execution", List.of("implementation"), false),
                new TaskNode("security", "security-policy", List.of("implementation"), false),
                new TaskNode("docs", "documentation", List.of("implementation"), false),
                new TaskNode("release", "release-readiness", List.of("tests", "security", "docs"), true)));
    }
}
