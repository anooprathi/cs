package com.schwab.urlshortener.workflow.engine;

/**
 * How a run's TEST stage executes its test plan. It's chosen per run (CreateRun.testExecution) and defaults to
 * {@code app.workflow.test-runner.mode}.
 * <ul>
 *   <li>PLAN_VERIFICATION: checks the generated plan against the repository (targets exist, every criterion
 *       is covered). No product test runs, and the report says so.</li>
 *   <li>COMMAND: really runs the configured test command (e.g. {@code mvn -o test -Dtest=...}) as a child
 *       process in the run's isolated worktree with the patch applied. It targets the plan's existing test
 *       classes plus every test class the patch adds or changes.</li>
 * </ul>
 */
public enum TestExecution {
    PLAN_VERIFICATION,
    COMMAND
}
