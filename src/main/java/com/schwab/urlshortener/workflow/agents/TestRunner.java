package com.schwab.urlshortener.workflow.agents;

import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.TestPlan;
import com.schwab.urlshortener.workflow.artifact.TestReport;
import com.schwab.urlshortener.workflow.engine.ArtifactOrigin;
import com.schwab.urlshortener.workflow.engine.ToolPolicy;

import java.nio.file.Path;
import java.util.List;

/**
 * The execution half of the TEST stage. The run's {@code TestExecution} picks the
 * implementation (see TestGenerationAgent). {@code workingTree} is the run's
 * isolated workspace with the approved patch applied, or the repository root when
 * workspaces are disabled. {@code patchTests} lists the test sources the patch
 * itself adds or changes; a runner that executes tests must include them.
 */
public interface TestRunner {

    String name();

    ArtifactOrigin origin();

    /** The tool this runner needs from the agent's policy. */
    ToolPolicy.Tool requiredTool();

    TestReport run(TestPlan plan, RequirementSpec spec, Path workingTree, List<String> patchTests);
}
