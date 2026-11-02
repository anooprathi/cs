package com.schwab.urlshortener.workflow.workspace;

import com.schwab.urlshortener.workflow.WorkflowProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Model-authored patches are untrusted: every path and size is checked before anything is written. */
class ChangeControlPolicyTest {

    private final ChangeControlPolicy policy = new ChangeControlPolicy(new WorkflowProperties(0, null, null, null, null, null, null, null,
            null, null, null));

    private static PatchProposal patch(String... pathsAndContent) {
        List<PatchProposal.FileChange> files = new java.util.ArrayList<>();
        for (int i = 0; i < pathsAndContent.length; i += 2) {
            files.add(new PatchProposal.FileChange(pathsAndContent[i], pathsAndContent[i + 1], "r"));
        }
        return new PatchProposal("s", files, List.of(), List.of());
    }

    @Test
    void allowedPaths_pass() {
        ChangeControlPolicy.Evaluation e = policy.evaluate(patch("docs/changes/a.md", "x", "src/main/java/com/x/Y.java", "class Y {}"));

        assertThat(e.passed()).isTrue();
        assertThat(e.protectedPaths()).isEmpty();
    }

    @Test
    void traversal_absolute_backslash_gitDir_andOutsideAllowlist_areBlocked() {
        ChangeControlPolicy.Evaluation e = policy.evaluate(patch(
                "../etc/passwd", "x", "/abs/file", "x", "C:/win/file", "x", "src\\main\\X.java", "x", ".git/config", "x",
                "scripts/deploy.sh", "x", "docs/./a.md", "x"));

        assertThat(e.passed()).isFalse();
        assertThat(String.join("\n", e.violations()))
                .contains("'..' segments are not allowed")
                .contains("absolute paths are not allowed")
                .contains("backslashes are not allowed")
                .contains("paths under .git are not allowed")
                .contains("'scripts/deploy.sh' is outside the allowed patch paths")
                .contains("empty or '.' segments");
    }

    @Test
    void protectedPaths_areAllowedButEscalated() {
        ChangeControlPolicy.Evaluation e = policy.evaluate(patch("src/main/java/com/schwab/urlshortener/security/SecurityConfig.java", "x"));

        assertThat(e.passed()).isTrue();
        assertThat(e.protectedPaths()).containsExactly("src/main/java/com/schwab/urlshortener/security/SecurityConfig.java");
    }

    @Test
    void secrets_size_count_duplicates_andEmptyPatches_areBlocked() {
        assertThat(policy.evaluate(patch("docs/a.md", "token usk_abcdefghijklmnopqrstuvwxyz0123")).violations())
                .anyMatch(v -> v.contains("credential-shaped"));
        assertThat(policy.evaluate(patch("docs/a.md", "x".repeat(200_001))).violations()).anyMatch(v -> v.contains("limit is 200000"));
        String[] many = new String[42];
        for (int i = 0; i < 21; i++) {
            many[2 * i] = "docs/f" + i + ".md";
            many[2 * i + 1] = "x";
        }
        assertThat(policy.evaluate(patch(many)).violations()).anyMatch(v -> v.contains("limit is 20"));
        assertThat(policy.evaluate(patch("docs/a.md", "x", "docs/a.md", "y")).violations()).anyMatch(v -> v.contains("more than once"));
        assertThat(policy.evaluate(new PatchProposal("s", List.of(), List.of(), List.of())).violations()).containsExactly("Patch contains no files.");
        assertThat(policy.evaluate(null).passed()).isFalse();
    }

    @Test
    void glob_doubleStarCrossesDirectories_singleStarDoesNot() {
        assertThat(ChangeControlPolicy.glob("docs/**").matcher("docs/a/b/c.md").matches()).isTrue();
        assertThat(ChangeControlPolicy.glob("docs/*.md").matcher("docs/a/b.md").matches()).isFalse();
        assertThat(ChangeControlPolicy.glob("README.md").matcher("README.md").matches()).isTrue();
        assertThat(ChangeControlPolicy.glob("README.md").matcher("READMExmd").matches()).isFalse();
    }
}
