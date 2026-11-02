package com.schwab.urlshortener.workflow.workspace;

import java.util.List;

/**
 * The reviewable patch artifact produced by the IMPLEMENTATION stage: what
 * changed (per file), the unified diff against the base commit, which
 * change-control rules it met or broke, and the evidence from every approved
 * validation command run against it in the isolated workspace.
 *
 * {@code kind} says what the patch is. CODE means it changes product code and
 * tests under {@code src/}; DOCUMENT means a change record only (the demo
 * fallback when no code recipe matches). {@code author} names what wrote it: a
 * demo recipe id, "model", or "change-record".
 *
 * The diff is the source of truth for workspace state. Later stages (and
 * promotion) re-apply exactly this diff to a clean worktree instead of trusting
 * whatever happens to be on disk.
 */
public record ChangePatch(
        String summary,
        Kind kind,
        String author,
        String baseCommit,
        boolean applied,
        boolean workspaceEnabled,
        List<FileSummary> files,
        List<String> testFiles,
        String unifiedDiff,
        String diffStat,
        List<String> policyViolations,
        List<String> protectedPathsTouched,
        List<CommandEvidence> validation,
        String notice
) {

    public enum Kind {
        CODE,
        DOCUMENT
    }

    public enum Action {
        ADDED,
        MODIFIED
    }

    public record FileSummary(String path, Action action, int bytes, String rationale) {
    }

    public record CommandEvidence(String name, List<String> argv, Integer exitCode, boolean passed, boolean timedOut,
                                  long durationMs, String outputTail) {
    }

    public ChangePatch {
        files = files == null ? List.of() : List.copyOf(files);
        testFiles = testFiles == null ? List.of() : List.copyOf(testFiles);
        policyViolations = policyViolations == null ? List.of() : List.copyOf(policyViolations);
        protectedPathsTouched = protectedPathsTouched == null ? List.of() : List.copyOf(protectedPathsTouched);
        validation = validation == null ? List.of() : List.copyOf(validation);
    }

    public boolean validationPassed() {
        return validation.stream().allMatch(CommandEvidence::passed);
    }

    /** CODE if any file is under src/, otherwise DOCUMENT. */
    public static Kind kindOf(List<PatchProposal.FileChange> files) {
        return files.stream().anyMatch(f -> f.path() != null && f.path().startsWith("src/")) ? Kind.CODE : Kind.DOCUMENT;
    }

    /** Test sources the patch adds or changes (src/test/java/**.java). */
    public static List<String> testFilesOf(List<PatchProposal.FileChange> files) {
        return files.stream().map(PatchProposal.FileChange::path)
                .filter(p -> p != null && p.startsWith("src/test/java/") && p.endsWith(".java")).toList();
    }
}
