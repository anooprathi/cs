package com.schwab.urlshortener.workflow.engine;

import java.util.EnumSet;
import java.util.Set;

/**
 * The tools a stage agent may use. Enforced at runtime by {@link AgentRequest#requireTool}:
 * every capability that touches something outside the agent (a model, the
 * workspace, a process) checks it first. A tool missing from the policy is a
 * hard failure, not a warning.
 *
 * Command execution is limited further. The only commands that can ever run
 * are the named validation commands in configuration
 * ({@code app.workflow.policy.validation-commands}) and the configured test
 * command. No agent, and no model, can supply a command line.
 */
public record ToolPolicy(Set<Tool> allowedTools) {

    public enum Tool {
        /** Read declared upstream artifacts. */
        READ_ARTIFACTS,
        /** Read the curated component catalog (repository context). */
        READ_COMPONENT_CATALOG,
        /** Check whether files exist in the repository (read-only). */
        READ_REPOSITORY_FILES,
        /** Call the language model (LLM mode only). */
        LLM_GENERATE,
        /** Write files inside the run's isolated workspace (never the source repository). */
        WORKSPACE_WRITE,
        /** Run a configured, named validation command in the workspace. */
        RUN_VALIDATION_COMMAND,
        /** Run the configured test command in the workspace. */
        RUN_TEST_COMMAND
    }

    public ToolPolicy {
        allowedTools = allowedTools == null || allowedTools.isEmpty() ? EnumSet.noneOf(Tool.class) : EnumSet.copyOf(allowedTools);
    }

    public static ToolPolicy of(Tool... tools) {
        return new ToolPolicy(tools.length == 0 ? EnumSet.noneOf(Tool.class) : EnumSet.of(tools[0], tools));
    }

    public boolean allows(Tool tool) {
        return allowedTools.contains(tool);
    }
}
