package com.schwab.urlshortener.workflow.workspace;

import com.schwab.urlshortener.workflow.WorkflowProperties;
import com.schwab.urlshortener.util.SecretPatterns;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Isolated workspaces for code changes, built on git worktrees, with the
 * source repository strictly read-only.
 *
 * <pre>
 *  source repository (app.workflow.workspace.source-repository)  ── read only: fetch HEAD
 *        │
 *        ▼
 *  {root}/{namespace}/sandbox.git   private bare clone owned by the orchestrator (namespace: one per boot by default)
 *        ├─ refs/workflow/base-{runId}   pinned base commit of each run
 *        ├─ refs/heads/workflow/run-{id}-v{n}   promoted patches (local branches only)
 *        └─ worktrees
 *  {root}/{namespace}/run-{runId}/  detached worktree of one run: the ONLY place patches are written
 * </pre>
 *
 * Rollback and compensation boundaries, stated exactly:
 * <ul>
 *   <li>{@link #reset}: discards every uncommitted change in the run's worktree (reset --hard + clean). This
 *       is the rollback for patch application.</li>
 *   <li>{@link #compensatePromotion}: deletes the local branch a promotion created in the sandbox clone,
 *       only if it still points at the recorded commit.</li>
 *   <li>Nothing is ever pushed, and the source repository's branches, index, and working tree are never
 *       touched, so there is nothing outside the sandbox to roll back. Effects that cannot be compensated
 *       (model calls already made and billed, time spent) are recorded, not reversed.</li>
 * </ul>
 */
@Component
@Slf4j
public class WorkspaceManager {

    public record Workspace(Long runId, Path directory, String baseCommit) {
    }

    public record Applied(String unifiedDiff, String diffStat, List<ChangePatch.FileSummary> files) {
    }

    public record Promotion(String branch, String commit) {
    }

    public static class WorkspaceException extends RuntimeException {
        public WorkspaceException(String message) {
            super(message);
        }
    }

    private static final Pattern BRANCH = Pattern.compile("workflow/run-\\d+-v\\d+");

    private final boolean enabled;
    private final Path source;
    private final Path root;
    private final String git;
    private final Duration timeout;
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();
    private final Object sandboxLock = new Object();
    private volatile Optional<String> unavailable;

    public WorkspaceManager(WorkflowProperties properties) {
        WorkflowProperties.Workspace w = properties.workspace();
        this.enabled = w.enabled();
        this.source = Path.of(w.sourceRepository()).toAbsolutePath().normalize();
        this.root = Path.of(w.root()).toAbsolutePath().normalize().resolve(w.namespace());
        this.git = w.gitCommand();
        this.timeout = w.commandTimeout();
    }

    public boolean enabled() {
        return enabled;
    }

    /** Empty if workspaces can be used; otherwise the reason they can't (disabled, no git, source not a repository). */
    public Optional<String> unavailableReason() {
        if (!enabled) {
            return Optional.of("workspaces disabled by configuration (app.workflow.workspace.enabled=false)");
        }
        Optional<String> cached = unavailable;
        if (cached == null) {
            ProcessRunner.Result version = ProcessRunner.run(List.of(git, "--version"), null, timeout, null);
            if (!version.succeeded()) {
                cached = Optional.of("git is not available ('" + git + " --version' failed)");
            } else {
                ProcessRunner.Result repo = ProcessRunner.run(List.of(git, "-C", source.toString(), "rev-parse", "--verify", "HEAD"), null, timeout, null);
                cached = repo.succeeded() ? Optional.empty()
                        : Optional.of("source repository " + source + " is not a git repository with at least one commit");
            }
            unavailable = cached;
        }
        return cached;
    }

    public Path root() {
        return root;
    }

    /** Opens the run's worktree, creating the sandbox clone and the worktree on first use. */
    public Workspace open(Long runId, String recordedBase) {
        unavailableReason().ifPresent(reason -> {
            throw new WorkspaceException(reason);
        });
        synchronized (lockFor(runId)) {
            Path sandbox = ensureSandbox();
            Path dir = root.resolve("run-" + runId);
            String base = recordedBase;
            if (base == null) {
                String ref = "refs/workflow/base-" + runId;
                git(null, "-C", sandbox.toString(), "fetch", "--quiet", "--no-tags", source.toString(), "+HEAD:" + ref);
                base = git(null, "-C", sandbox.toString(), "rev-parse", ref).output().trim();
            }
            if (!Files.isDirectory(dir.resolve(".git")) && !Files.isRegularFile(dir.resolve(".git"))) {
                git(null, "-C", sandbox.toString(), "worktree", "prune");
                git(null, "-C", sandbox.toString(), "worktree", "add", "--quiet", "--detach", dir.toString(), base);
            }
            return new Workspace(runId, dir, base);
        }
    }

    private Path ensureSandbox() {
        synchronized (sandboxLock) {
            Path sandbox = root.resolve("sandbox.git");
            if (!Files.isDirectory(sandbox)) {
                try {
                    Files.createDirectories(root);
                } catch (IOException e) {
                    throw new WorkspaceException("cannot create workspace root " + root + ": " + e.getMessage());
                }
                git(root, "clone", "--quiet", "--bare", "--no-hardlinks", source.toString(), sandbox.toString());
                // Byte-exact workspaces: without this, a machine-wide core.autocrlf=true (common on Windows) makes
                // checkout and 'git apply' rewrite line endings, so a re-applied patch would differ from the recorded diff.
                git(null, "-C", sandbox.toString(), "config", "core.autocrlf", "false");
            }
            return sandbox;
        }
    }

    /** Rollback: discard every uncommitted change in the run's worktree, returning it to the base commit. */
    public void reset(Workspace ws) {
        synchronized (lockFor(ws.runId())) {
            git(ws.directory(), "checkout", "--quiet", "--detach", ws.baseCommit());
            git(ws.directory(), "reset", "--quiet", "--hard", ws.baseCommit());
            git(ws.directory(), "clean", "-fdq");
        }
    }

    /**
     * Writes whole-file changes into the worktree (after resetting it) and stages
     * them. Paths must already have passed {@link ChangeControlPolicy}. This
     * re-checks that each resolved path stays inside the worktree, as a second
     * line of defense.
     */
    public Applied apply(Workspace ws, List<PatchProposal.FileChange> files) {
        synchronized (lockFor(ws.runId())) {
            reset(ws);
            List<ChangePatch.FileSummary> summaries = new ArrayList<>();
            for (PatchProposal.FileChange f : files) {
                Path target = ws.directory().resolve(f.path()).normalize();
                if (!target.startsWith(ws.directory()) || target.startsWith(ws.directory().resolve(".git"))) {
                    throw new WorkspaceException("refusing to write outside the workspace: " + f.path());
                }
                boolean existed = ProcessRunner.run(List.of(git, "cat-file", "-e", ws.baseCommit() + ":" + f.path()),
                        ws.directory(), timeout, null).succeeded();
                byte[] bytes = (f.content() == null ? "" : f.content()).getBytes(StandardCharsets.UTF_8);
                try {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.write(target, bytes);
                } catch (IOException e) {
                    throw new WorkspaceException("cannot write " + f.path() + ": " + e.getMessage());
                }
                summaries.add(new ChangePatch.FileSummary(f.path(), existed ? ChangePatch.Action.MODIFIED : ChangePatch.Action.ADDED,
                        bytes.length, f.rationale()));
            }
            git(ws.directory(), "add", "-A");
            return diff(ws, summaries);
        }
    }

    /** Resets the worktree and re-applies an exact, previously produced diff, so workspace state always derives from the artifact. */
    public void reapply(Workspace ws, String unifiedDiff) {
        synchronized (lockFor(ws.runId())) {
            reset(ws);
            if (unifiedDiff != null && !unifiedDiff.isBlank()) {
                ProcessRunner.Result r = ProcessRunner.run(List.of(git, "apply", "--index", "--whitespace=nowarn", "-"),
                        ws.directory(), timeout, unifiedDiff);
                if (!r.succeeded()) {
                    throw new WorkspaceException("patch no longer applies to base " + ws.baseCommit() + ": " + tail(r));
                }
            }
        }
    }

    /** Runs an approved command in the worktree. Callers pass only configuration-defined argv, never model output. */
    public ProcessRunner.Result run(Workspace ws, List<String> argv, Duration commandTimeout) {
        synchronized (lockFor(ws.runId())) {
            return ProcessRunner.run(argv, ws.directory(), commandTimeout, null);
        }
    }

    /**
     * Commits an exact diff onto a NEW local branch in the sandbox clone, then
     * returns the worktree to the base commit. Never pushes, and never touches
     * the source repository.
     */
    public Promotion promote(Workspace ws, String unifiedDiff, String branch, String message, String author) {
        if (!BRANCH.matcher(branch).matches()) {
            throw new WorkspaceException("refusing unexpected branch name " + branch);
        }
        synchronized (lockFor(ws.runId())) {
            reapply(ws, unifiedDiff);
            git(ws.directory(), "checkout", "--quiet", "-b", branch);
            git(ws.directory(), "-c", "user.name=" + author, "-c", "user.email=workflow-orchestrator@localhost",
                    "commit", "--quiet", "--no-verify", "-m", message);
            String commit = git(ws.directory(), "rev-parse", "HEAD").output().trim();
            git(ws.directory(), "checkout", "--quiet", "--detach", ws.baseCommit());
            return new Promotion(branch, commit);
        }
    }

    /** Compensation for {@link #promote}: deletes the local branch, but only if it still points at the commit we created. */
    public void compensatePromotion(Workspace ws, String branch, String expectedCommit) {
        synchronized (lockFor(ws.runId())) {
            Path sandbox = root.resolve("sandbox.git");
            ProcessRunner.Result current = ProcessRunner.run(List.of(git, "-C", sandbox.toString(), "rev-parse", "--verify",
                    "refs/heads/" + branch), null, timeout, null);
            if (!current.succeeded()) {
                throw new WorkspaceException("branch " + branch + " no longer exists; nothing to compensate");
            }
            if (!current.output().trim().equals(expectedCommit)) {
                throw new WorkspaceException("branch " + branch + " has moved since promotion; refusing to delete someone else's work");
            }
            git(null, "-C", sandbox.toString(), "branch", "--quiet", "-D", branch);
        }
    }

    public boolean branchExists(String branch) {
        Path sandbox = root.resolve("sandbox.git");
        return ProcessRunner.run(List.of(git, "-C", sandbox.toString(), "rev-parse", "--verify", "refs/heads/" + branch),
                null, timeout, null).succeeded();
    }

    /** Status of the worktree (porcelain), for evidence that a rollback left it clean. */
    public String status(Workspace ws) {
        return git(ws.directory(), "status", "--porcelain").output();
    }

    public void discard(Workspace ws) {
        synchronized (lockFor(ws.runId())) {
            Path sandbox = root.resolve("sandbox.git");
            git(null, "-C", sandbox.toString(), "worktree", "remove", "--force", ws.directory().toString());
        }
    }

    /** Full-output cap for a captured diff. Far above any patch change control allows (max-patch-bytes), so hitting it is a bug. */
    static final int MAX_DIFF_CHARS = 8 * 1024 * 1024;

    private Applied diff(Workspace ws, List<ChangePatch.FileSummary> files) {
        List<String> argv = List.of(git, "diff", "--cached", "--binary", "--no-color", ws.baseCommit());
        ProcessRunner.Result full = ProcessRunner.run(argv, ws.directory(), timeout, null, MAX_DIFF_CHARS);
        if (!full.succeeded() || full.outputTruncated()) {
            throw new WorkspaceException("git diff failed or exceeded " + MAX_DIFF_CHARS + " chars: " + tail(full));
        }
        String diff = full.output();
        String stat = git(ws.directory(), "diff", "--cached", "--stat", "--no-color", ws.baseCommit()).output();
        return new Applied(diff, stat.strip(), List.copyOf(files));
    }

    private ProcessRunner.Result git(Path dir, String... args) {
        List<String> argv = new ArrayList<>();
        argv.add(git);
        argv.addAll(List.of(args));
        ProcessRunner.Result r = ProcessRunner.run(argv, dir, timeout, null);
        if (!r.succeeded()) {
            throw new WorkspaceException("git " + String.join(" ", args) + " failed: " + tail(r));
        }
        return r;
    }

    private static String tail(ProcessRunner.Result r) {
        String text = r.error() != null ? r.error() : r.output();
        text = SecretPatterns.redact(text == null ? "" : text.strip());
        return text.length() <= 500 ? text : text.substring(text.length() - 500);
    }

    private Object lockFor(Long runId) {
        return locks.computeIfAbsent(runId, id -> new Object());
    }
}
