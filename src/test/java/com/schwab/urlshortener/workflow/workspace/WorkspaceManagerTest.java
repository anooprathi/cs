package com.schwab.urlshortener.workflow.workspace;

import com.schwab.urlshortener.workflow.WorkflowProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real git, real worktrees, against a throwaway source repository. Verifies
 * isolation (the source repository is never modified), rollback, exact-diff
 * re-application, promotion to a local branch, and compensation. Skipped (not
 * failed) only when git isn't installed.
 */
class WorkspaceManagerTest {

    @TempDir
    Path tmp;

    private Path source;
    private WorkspaceManager manager;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(ProcessRunner.run(List.of("git", "--version"), null, Duration.ofSeconds(20), null).succeeded(), "git not installed");
        source = tmp.resolve("source");
        Files.createDirectories(source.resolve("docs"));
        Files.writeString(source.resolve("README.md"), "hello\n");
        git(source, "init", "--quiet");
        git(source, "add", "-A");
        git(source, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "init");
        WorkflowProperties props = new WorkflowProperties(0, false, null, null, null, null, null, null,
                new WorkflowProperties.Workspace(true, source.toString(), tmp.resolve("ws").toString(), "git", Duration.ofSeconds(60), "test"),
                null, null);
        manager = new WorkspaceManager(props);
    }

    private static void git(Path dir, String... args) {
        List<String> argv = new java.util.ArrayList<>(List.of("git"));
        argv.addAll(List.of(args));
        ProcessRunner.Result r = ProcessRunner.run(argv, dir, Duration.ofSeconds(60), null);
        assertThat(r.succeeded()).as(r.output()).isTrue();
    }

    private static String sourceStatus(Path source) {
        return ProcessRunner.run(List.of("git", "status", "--porcelain"), source, Duration.ofSeconds(20), null).output();
    }

    @Test
    void apply_writesOnlyInTheWorktree_andProducesADiffAgainstBase() throws Exception {
        WorkspaceManager.Workspace ws = manager.open(1L, null);

        WorkspaceManager.Applied applied = manager.apply(ws, List.of(
                new PatchProposal.FileChange("docs/change.md", "# change\n", "new doc"),
                new PatchProposal.FileChange("README.md", "hello world\n", "edit")));

        assertThat(applied.unifiedDiff()).contains("+++ b/docs/change.md").contains("-hello").contains("+hello world");
        assertThat(applied.files()).extracting(ChangePatch.FileSummary::action)
                .containsExactly(ChangePatch.Action.ADDED, ChangePatch.Action.MODIFIED);
        assertThat(Files.readString(ws.directory().resolve("README.md"))).isEqualTo("hello world\n");
        assertThat(Files.readString(source.resolve("README.md"))).as("source repository untouched").isEqualTo("hello\n");
        assertThat(sourceStatus(source)).isBlank();
    }

    @Test
    void reset_rollsTheWorktreeBackToBase() throws Exception {
        WorkspaceManager.Workspace ws = manager.open(2L, null);
        manager.apply(ws, List.of(new PatchProposal.FileChange("docs/x.md", "x\n", "r")));

        manager.reset(ws);

        assertThat(Files.exists(ws.directory().resolve("docs/x.md"))).isFalse();
        assertThat(manager.status(ws)).isBlank();
    }

    @Test
    void reapply_restoresExactlyTheRecordedDiff() throws Exception {
        WorkspaceManager.Workspace ws = manager.open(3L, null);
        String diff = manager.apply(ws, List.of(new PatchProposal.FileChange("docs/y.md", "y\n", "r"))).unifiedDiff();
        manager.reset(ws);

        manager.reapply(ws, diff);

        assertThat(Files.readString(ws.directory().resolve("docs/y.md"))).isEqualTo("y\n");
    }

    /** Regression: diffs were captured through the 4,000-char output tail, so any real code patch re-applied as garbage. */
    @Test
    void reapply_worksForADiffFarLargerThanTheCommandOutputTail() throws Exception {
        WorkspaceManager.Workspace ws = manager.open(6L, null);
        String big = "line of generated content that makes this diff long\n".repeat(2_000);
        String diff = manager.apply(ws, List.of(new PatchProposal.FileChange("docs/big.md", big, "r"),
                new PatchProposal.FileChange("docs/after.md", "after\n", "r"))).unifiedDiff();
        assertThat(diff.length()).isGreaterThan(ProcessRunner.OUTPUT_TAIL * 10);
        assertThat(diff).contains("+++ b/docs/big.md", "+++ b/docs/after.md");
        manager.reset(ws);

        manager.reapply(ws, diff);

        assertThat(Files.readString(ws.directory().resolve("docs/big.md"))).isEqualTo(big);
        assertThat(Files.readString(ws.directory().resolve("docs/after.md"))).isEqualTo("after\n");
    }

    @Test
    void reopen_reusesTheSameWorktreeAndBase() {
        WorkspaceManager.Workspace first = manager.open(4L, null);

        WorkspaceManager.Workspace again = manager.open(4L, first.baseCommit());

        assertThat(again.directory()).isEqualTo(first.directory());
        assertThat(again.baseCommit()).isEqualTo(first.baseCommit());
    }

    @Test
    void promote_createsALocalBranchInTheSandbox_andCompensationDeletesIt() {
        WorkspaceManager.Workspace ws = manager.open(5L, null);
        String diff = manager.apply(ws, List.of(new PatchProposal.FileChange("docs/z.md", "z\n", "r"))).unifiedDiff();

        WorkspaceManager.Promotion p = manager.promote(ws, diff, "workflow/run-5-v1", "promote", "reviewer");

        assertThat(manager.branchExists("workflow/run-5-v1")).isTrue();
        assertThat(p.commit()).hasSize(40);
        assertThat(ProcessRunner.run(List.of("git", "branch", "--list", "workflow/*"), source, Duration.ofSeconds(20), null).output())
                .as("the source repository gets no branch").isBlank();

        manager.compensatePromotion(ws, p.branch(), p.commit());

        assertThat(manager.branchExists("workflow/run-5-v1")).isFalse();
        assertThatThrownBy(() -> manager.compensatePromotion(ws, p.branch(), p.commit()))
                .isInstanceOf(WorkspaceManager.WorkspaceException.class).hasMessageContaining("no longer exists");
    }

    @Test
    void promote_refusesUnexpectedBranchNames() {
        WorkspaceManager.Workspace ws = manager.open(6L, null);

        assertThatThrownBy(() -> manager.promote(ws, "", "main", "m", "r"))
                .isInstanceOf(WorkspaceManager.WorkspaceException.class).hasMessageContaining("unexpected branch name");
    }

    @Test
    void apply_refusesToWriteOutsideTheWorktree_evenIfAPathSlipsPastPolicy() {
        WorkspaceManager.Workspace ws = manager.open(7L, null);

        assertThatThrownBy(() -> manager.apply(ws, List.of(new PatchProposal.FileChange("../escape.md", "x", "r"))))
                .isInstanceOf(WorkspaceManager.WorkspaceException.class).hasMessageContaining("outside the workspace");
    }

    @Test
    void unavailable_whenSourceIsNotAGitRepository() {
        WorkflowProperties props = new WorkflowProperties(0, false, null, null, null, null, null, null,
                new WorkflowProperties.Workspace(true, tmp.resolve("not-a-repo").toString(), tmp.resolve("ws2").toString(), "git",
                        Duration.ofSeconds(20), "test"), null, null);

        assertThat(new WorkspaceManager(props).unavailableReason()).hasValueSatisfying(r -> assertThat(r).contains("not a git repository"));
    }
}
