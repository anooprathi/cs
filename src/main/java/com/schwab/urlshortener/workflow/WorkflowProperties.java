package com.schwab.urlshortener.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code app.workflow.*}: the orchestrator's execution, governance, and
 * safety configuration. Every value can also come from an environment variable
 * through Spring's relaxed binding (e.g. APP_WORKFLOW_RETRY_MAXATTEMPTS=5).
 *
 * Defaults live in the compact constructors and are always the conservative
 * choice. Examples: plan verification instead of running commands, bounded
 * parallelism, bounded retries, a token/cost budget, patches restricted to an
 * allowlist of paths.
 */
@ConfigurationProperties(prefix = "app.workflow")
public record WorkflowProperties(
        int maxParallelism,
        Boolean asyncExecution,
        String repositoryRoot,
        TestRunner testRunner,
        Policy policy,
        Retry retry,
        String fallback,
        Limits limits,
        Workspace workspace,
        Demo demo,
        Cli cli
) {

    public static final String FALLBACK_DETERMINISTIC_WITH_REVIEW = "deterministic-with-review";
    public static final String FALLBACK_FAIL = "fail";

    public WorkflowProperties {
        if (maxParallelism <= 0) {
            maxParallelism = 3;
        }
        if (asyncExecution == null) {
            asyncExecution = Boolean.TRUE;
        }
        if (repositoryRoot == null || repositoryRoot.isBlank()) {
            repositoryRoot = ".";
        }
        testRunner = testRunner == null ? new TestRunner(null, null, null) : testRunner;
        policy = policy == null ? new Policy(null, null, null, null, null, null, null, null, null) : policy;
        retry = retry == null ? new Retry(0, null, 0, null) : retry;
        if (fallback == null || fallback.isBlank()) {
            fallback = FALLBACK_DETERMINISTIC_WITH_REVIEW;
        }
        if (!fallback.equals(FALLBACK_DETERMINISTIC_WITH_REVIEW) && !fallback.equals(FALLBACK_FAIL)) {
            throw new IllegalArgumentException("app.workflow.fallback must be '" + FALLBACK_DETERMINISTIC_WITH_REVIEW + "' or '"
                    + FALLBACK_FAIL + "', was '" + fallback + "'");
        }
        limits = limits == null ? new Limits(null, 0, 0, 0, 0, 0) : limits;
        workspace = workspace == null ? new Workspace(null, null, null, null, null, null) : workspace;
        demo = demo == null ? new Demo(null) : demo;
        cli = cli == null ? new Cli(null) : cli;
    }

    /**
     * {@code mode=plan-verification} (default) checks the generated test plan
     * against the repository and does not run product tests. {@code mode=command}
     * runs {@code command} (no shell) in the run's isolated workspace. {@code {tests}}
     * becomes the plan's existing JUnit class names.
     */
    public record TestRunner(String mode, List<String> command, Duration timeout) {

        public static final String PLAN_VERIFICATION = "plan-verification";
        public static final String COMMAND = "command";

        public TestRunner {
            if (mode == null || mode.isBlank()) {
                mode = PLAN_VERIFICATION;
            }
            command = command == null ? List.of() : List.copyOf(command);
            if (timeout == null) {
                timeout = Duration.ofMinutes(10);
            }
        }
    }

    /**
     * Security and change-control policy.
     *
     * @param forbiddenTerms       requirement phrases that are out of product scope (POL-SCOPE, BLOCKER)
     * @param allowedPatchPaths    glob patterns a patch may touch; anything else is a BLOCKER
     * @param protectedPaths       globs that a patch MAY touch only with human approval of the implementation task
     * @param maxPatchFiles        upper bound on files in one patch
     * @param maxPatchBytes        upper bound on total new content in one patch
     * @param validationCommands   the ONLY commands the orchestrator will ever run on a patch, by name → argv (no shell)
     * @param patchValidation      which named validation commands run on every patch
     * @param clarifyAmbiguities   pause requirement analysis for human approval when it finds ambiguities
     * @param approvalRequiredTasks task keys that always need approval, in addition to the graph's own approval nodes
     */
    public record Policy(
            List<String> forbiddenTerms,
            List<String> allowedPatchPaths,
            List<String> protectedPaths,
            Integer maxPatchFiles,
            Integer maxPatchBytes,
            Map<String, List<String>> validationCommands,
            List<String> patchValidation,
            Boolean clarifyAmbiguities,
            List<String> approvalRequiredTasks
    ) {
        public Policy {
            forbiddenTerms = forbiddenTerms == null
                    ? List.of("payment processor", "charge the card", "credit card number", "card number", "process payments")
                    : List.copyOf(forbiddenTerms);
            allowedPatchPaths = allowedPatchPaths == null
                    ? List.of("docs/**", "src/main/java/**", "src/test/java/**", "README.md")
                    : List.copyOf(allowedPatchPaths);
            protectedPaths = protectedPaths == null
                    ? List.of("src/main/java/com/schwab/urlshortener/security/**", "src/main/resources/**", "pom.xml")
                    : List.copyOf(protectedPaths);
            if (maxPatchFiles == null || maxPatchFiles <= 0) {
                maxPatchFiles = 20;
            }
            if (maxPatchBytes == null || maxPatchBytes <= 0) {
                maxPatchBytes = 200_000;
            }
            validationCommands = validationCommands == null
                    ? Map.of("git-diff-check", List.of("git", "diff", "--cached", "--check"))
                    : Map.copyOf(validationCommands);
            patchValidation = patchValidation == null ? List.of("git-diff-check") : List.copyOf(patchValidation);
            if (clarifyAmbiguities == null) {
                clarifyAmbiguities = Boolean.TRUE;
            }
            approvalRequiredTasks = approvalRequiredTasks == null ? List.of() : List.copyOf(approvalRequiredTasks);
        }
    }

    /**
     * Bounded retry with exponential backoff, for transient failures ONLY
     * (timeouts, rate limits, overload, connection errors). Gate failures,
     * policy violations, rejected approvals, and agent logic failures are never
     * retried automatically.
     */
    public record Retry(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {
        public Retry {
            if (maxAttempts <= 0) {
                maxAttempts = 3;
            }
            if (initialBackoff == null) {
                initialBackoff = Duration.ofMillis(200);
            }
            if (multiplier < 1.0) {
                multiplier = 2.0;
            }
            if (maxBackoff == null) {
                maxBackoff = Duration.ofSeconds(5);
            }
        }

        public Duration backoffBefore(int nextAttempt) {
            double factor = Math.pow(multiplier, Math.max(0, nextAttempt - 2));
            long millis = (long) Math.min(maxBackoff.toMillis(), initialBackoff.toMillis() * factor);
            return Duration.ofMillis(millis);
        }
    }

    /**
     * Execution limits. Token and cost budgets are per run and apply to LLM calls only. Cost is an
     * ESTIMATE from configured per-million-token prices, not a billed amount.
     */
    public record Limits(Duration taskTimeout, long maxTokensPerRun, double maxCostUsdPerRun, double inputUsdPerMtok,
                         double outputUsdPerMtok, long maxOutputTokensPerCall) {
        public Limits {
            if (taskTimeout == null) {
                taskTimeout = Duration.ofMinutes(15);
            }
            if (maxTokensPerRun <= 0) {
                maxTokensPerRun = 500_000;
            }
            if (maxCostUsdPerRun <= 0) {
                maxCostUsdPerRun = 10.0;
            }
            if (inputUsdPerMtok <= 0) {
                inputUsdPerMtok = 5.0;
            }
            if (outputUsdPerMtok <= 0) {
                outputUsdPerMtok = 25.0;
            }
            if (maxOutputTokensPerCall <= 0) {
                maxOutputTokensPerCall = 16_000;
            }
        }
    }

    /**
     * Isolated workspaces. Patches are never applied to {@code sourceRepository}
     * itself. The orchestrator keeps a private sandbox clone of it under {@code root}
     * and creates one git worktree per run from the source's current HEAD commit.
     * Promotion creates a local branch in the SANDBOX clone, never in the source
     * repository, and nothing is ever pushed.
     */
    public record Workspace(Boolean enabled, String sourceRepository, String root, String gitCommand, Duration commandTimeout,
                            String namespace) {
        public Workspace {
            if (enabled == null) {
                enabled = Boolean.TRUE;
            }
            if (sourceRepository == null || sourceRepository.isBlank()) {
                sourceRepository = ".";
            }
            if (root == null || root.isBlank()) {
                root = System.getProperty("java.io.tmpdir") + "/url-shortener-workflows";
            }
            if (gitCommand == null || gitCommand.isBlank()) {
                gitCommand = "git";
            }
            if (commandTimeout == null) {
                commandTimeout = Duration.ofMinutes(2);
            }
            if (namespace == null || namespace.isBlank()) {
                // Run ids come from the database. With this prototype's in-memory H2 they restart at 1 on every boot, so
                // workspaces must not be shared across boots, or run 1's worktree and branches from yesterday would be
                // mistaken for today's run 1. A persistent-database deployment should set a stable namespace instead.
                namespace = "instance-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            }
            if (!namespace.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("app.workflow.workspace.namespace may contain only letters, digits, '.', '_' and '-'");
            }
        }
    }

    /**
     * Demo-only knobs. {@code simulatedLatency} makes DETERMINISTIC_DEMO agent steps take
     * visible time so parallel execution shows up in the timeline. It is labeled as
     * simulated wherever it applies.
     */
    public record Demo(Duration simulatedLatency) {
        public Demo {
            if (simulatedLatency == null) {
                simulatedLatency = Duration.ZERO;
            }
        }
    }

    /** {@code scenario=greenfield|brownfield|ambiguous|all} runs that scenario at startup, prints the result, and exits. */
    public record Cli(String scenario) {
    }
}
