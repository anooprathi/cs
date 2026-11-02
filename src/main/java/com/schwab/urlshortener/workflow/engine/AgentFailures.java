package com.schwab.urlshortener.workflow.engine;

/**
 * The failure taxonomy the engine's recovery policy is built on. How a failure
 * is classified decides what happens next:
 *
 * <table>
 *   <tr><th>Failure</th><th>Automatic retry</th><th>Fallback (LLM mode)</th></tr>
 *   <tr><td>{@link Transient} (timeout, rate limit, overload, connection)</td><td>yes, bounded with backoff</td><td>after retries are exhausted</td></tr>
 *   <tr><td>{@link AgentFailure} (refusal, unparseable or truncated output, agent reported FAILED)</td><td>no</td><td>yes</td></tr>
 *   <tr><td>{@link BudgetExceeded}</td><td>no</td><td>yes (deterministic path costs nothing)</td></tr>
 *   <tr><td>Gate failure / policy violation</td><td>no</td><td>no</td></tr>
 *   <tr><td>Approval rejected / missing</td><td>no</td><td>no</td></tr>
 *   <tr><td>Tool not permitted, any other exception (a bug)</td><td>no</td><td>no</td></tr>
 * </table>
 */
public final class AgentFailures {

    private AgentFailures() {
    }

    /** Worth retrying: the same request may succeed later. */
    public static class Transient extends RuntimeException {
        public Transient(String message, Throwable cause) {
            super(message, cause);
        }

        public Transient(String message) {
            super(message);
        }
    }

    /** The agent could not complete the task. Retrying the same input won't help. */
    public static class AgentFailure extends RuntimeException {
        public AgentFailure(String message, Throwable cause) {
            super(message, cause);
        }

        public AgentFailure(String message) {
            super(message);
        }
    }

    /** The run's token or cost budget is spent. */
    public static class BudgetExceeded extends AgentFailure {
        public BudgetExceeded(String message) {
            super(message);
        }
    }

    /** The agent tried to use a tool outside its policy. Treated as a bug, never retried or worked around. */
    public static class ToolNotPermitted extends IllegalStateException {
        public ToolNotPermitted(String agent, ToolPolicy.Tool tool) {
            super("Agent '" + agent + "' is not permitted to use tool " + tool);
        }
    }
}
