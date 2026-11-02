package com.schwab.urlshortener.workflow.agents;

import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.TestPlan;
import com.schwab.urlshortener.workflow.artifact.TestReport;
import com.schwab.urlshortener.workflow.engine.ArtifactOrigin;
import com.schwab.urlshortener.workflow.engine.ToolPolicy;

import java.nio.file.Path;

/**
 * The execution half of the TEST stage. Exactly one implementation is active,
 * selected by {@code app.workflow.test-runner.mode} (see WorkflowConfig).
 * {@code workingTree} is the run's isolated workspace with the approved patch
 * applied, or the repository root when workspaces are disabled.
 */
public interface TestRunner {

    String name();

    ArtifactOrigin origin();

    /** The tool this runner needs from the agent's policy. */
    ToolPolicy.Tool requiredTool();

    TestReport run(TestPlan plan, RequirementSpec spec, Path workingTree);
}
