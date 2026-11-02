package com.schwab.urlshortener.workflow.workspace;

import java.util.List;

/**
 * The reviewable patch artifact produced by the IMPLEMENTATION stage: what
 * changed (per file), the unified diff against the base commit, which
 * change-control rules it met or broke, and the evidence from every approved
 * validation command run against it in the isolated workspace.
 *
 * The diff is the source of truth for workspace state. Later stages (and
 * promotion) re-apply exactly this diff to a clean worktree instead of trusting
 * whatever happens to be on disk.
 */
public record ChangePatch(
        String summary,
        String baseCommit,
        boolean applied,
        boolean workspaceEnabled,
        List<FileSummary> files,
        String unifiedDiff,
        String diffStat,
        List<String> policyViolations,
        List<String> protectedPathsTouched,
        List<CommandEvidence> validation,
        String notice
) {

    public enum Action {
        ADDED,
        MODIFIED
    }

    public record FileSummary(String path, Action action, int bytes, String rationale) {
    }

    public record CommandEvidence(String name, List<String> argv, Integer exitCode, boolean passed, boolean timedOut,
                                  long durationMs, String outputTail) {
    }

    public boolean validationPassed() {
        return validation.stream().allMatch(CommandEvidence::passed);
    }
}
