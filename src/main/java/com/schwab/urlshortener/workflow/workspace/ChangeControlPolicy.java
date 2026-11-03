package com.schwab.urlshortener.workflow.workspace;

import com.schwab.urlshortener.workflow.WorkflowProperties;
import com.schwab.urlshortener.util.SecretPatterns;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic change-control rules for a proposed patch, evaluated BEFORE
 * anything is written. Proposals are untrusted (they may come from a model),
 * so this checks every path syntactically and against the configured
 * allowlist instead of trusting the proposal's own description.
 *
 * Rejected outright (BLOCKER, nothing is written): absolute paths, {@code ..}
 * segments, backslashes, paths under {@code .git}, paths outside
 * {@code allowed-patch-paths}, duplicate paths, empty file lists, too many
 * files or bytes, and credential-shaped content.
 *
 * Allowed but escalated: paths matching {@code protected-paths} (security
 * config, resources, pom.xml by default) are reported so the engine requires
 * human approval of the implementation task.
 */
@Component
public class ChangeControlPolicy {

    public record Evaluation(List<String> violations, List<String> protectedPaths) {
        public boolean passed() {
            return violations.isEmpty();
        }
    }

    private final List<Pattern> allowed;
    private final List<Pattern> protectedMatchers;
    private final List<String> allowedGlobs;
    private final int maxFiles;
    private final int maxBytes;

    public ChangeControlPolicy(WorkflowProperties properties) {
        WorkflowProperties.Policy p = properties.policy();
        this.allowedGlobs = p.allowedPatchPaths();
        this.allowed = p.allowedPatchPaths().stream().map(ChangeControlPolicy::glob).toList();
        this.protectedMatchers = p.protectedPaths().stream().map(ChangeControlPolicy::glob).toList();
        this.maxFiles = p.maxPatchFiles();
        this.maxBytes = p.maxPatchBytes();
    }

    /**
     * Portable glob over forward-slash paths (the JDK's PathMatcher is
     * platform-dependent): {@code **} matches across directories, {@code *} and
     * {@code ?} stay within one segment.
     */
    static Pattern glob(String pattern) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                re.append(".*");
                i++;
            } else if (c == '*') {
                re.append("[^/]*");
            } else if (c == '?') {
                re.append("[^/]");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString());
    }

    public Evaluation evaluate(PatchProposal proposal) {
        List<String> violations = new ArrayList<>();
        List<String> protectedTouched = new ArrayList<>();
        List<PatchProposal.FileChange> files = proposal == null || proposal.files() == null ? List.of() : proposal.files();
        if (files.isEmpty()) {
            violations.add("Patch contains no files.");
        }
        if (files.size() > maxFiles) {
            violations.add("Patch touches " + files.size() + " files; the limit is " + maxFiles + ".");
        }
        int totalBytes = 0;
        Set<String> seen = new HashSet<>();
        for (PatchProposal.FileChange f : files) {
            String path = f.path() == null ? "" : f.path();
            String problem = pathProblem(path);
            if (problem != null) {
                violations.add("'" + path + "': " + problem);
                continue;
            }
            if (!seen.add(path)) {
                violations.add("'" + path + "' appears more than once.");
            }
            if (allowed.stream().noneMatch(m -> m.matcher(path).matches())) {
                violations.add("'" + path + "' is outside the allowed patch paths " + allowedGlobs + ".");
            }
            if (protectedMatchers.stream().anyMatch(m -> m.matcher(path).matches())) {
                protectedTouched.add(path);
            }
            String content = f.content() == null ? "" : f.content();
            totalBytes += content.getBytes(StandardCharsets.UTF_8).length;
            if (SecretPatterns.containsSecret(content)) {
                violations.add("'" + path + "' contains a credential-shaped value.");
            }
        }
        if (totalBytes > maxBytes) {
            violations.add("Patch content is " + totalBytes + " bytes; the limit is " + maxBytes + ".");
        }
        return new Evaluation(List.copyOf(violations), List.copyOf(protectedTouched));
    }

    /** Syntactic path checks; null means the path is acceptable to match against the allowlist. */
    static String pathProblem(String path) {
        if (path.isBlank()) {
            return "empty path";
        }
        if (path.contains("\\")) {
            return "backslashes are not allowed; use forward slashes";
        }
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*") || path.startsWith("~")) {
            return "absolute paths are not allowed";
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                return "'..' segments are not allowed";
            }
            if (segment.isEmpty() || segment.equals(".")) {
                return "empty or '.' segments are not allowed";
            }
        }
        if (path.toLowerCase(Locale.ROOT).equals(".git") || path.toLowerCase(Locale.ROOT).startsWith(".git/")) {
            return "paths under .git are not allowed";
        }
        return null;
    }
}
