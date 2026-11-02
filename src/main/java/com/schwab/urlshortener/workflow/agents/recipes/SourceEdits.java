package com.schwab.urlshortener.workflow.agents.recipes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Deterministic source editing over a working tree, used by demo code recipes.
 * Every edit is anchored: if an anchor isn't found exactly once, the edit throws
 * {@link RecipeNotApplicable} instead of guessing. A recipe therefore applies
 * cleanly to the code it was written against, or not at all. It never produces a
 * half-edited file.
 *
 * Files are read with line endings normalized to LF and written back as LF,
 * matching the repository's stored form.
 */
public final class SourceEdits {

    /** Thrown when the tree doesn't look like what the recipe expects. The agent then falls back to a change record. */
    public static class RecipeNotApplicable extends RuntimeException {
        public RecipeNotApplicable(String message) {
            super(message);
        }
    }

    private final Path root;
    private final Map<String, String> edited = new LinkedHashMap<>();

    public SourceEdits(Path root) {
        this.root = root;
    }

    /** Current content of {@code path}: the edited version if already edited, else the file on disk. */
    public String read(String path) {
        String current = edited.get(path);
        if (current != null) {
            return current;
        }
        Path file = root.resolve(path);
        if (!Files.isRegularFile(file)) {
            throw new RecipeNotApplicable("expected file not found: " + path);
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Replaces the single occurrence of {@code anchor} in {@code path}. */
    public SourceEdits replaceOnce(String path, String anchor, String replacement) {
        String content = read(path);
        int first = content.indexOf(anchor);
        if (first < 0 || content.indexOf(anchor, first + 1) >= 0) {
            throw new RecipeNotApplicable("anchor " + (first < 0 ? "not found" : "not unique") + " in " + path + ": "
                    + anchor.lines().findFirst().orElse(anchor).strip());
        }
        edited.put(path, content.substring(0, first) + replacement + content.substring(first + anchor.length()));
        return this;
    }

    /** Inserts {@code text} directly after the single occurrence of {@code anchor}. */
    public SourceEdits insertAfter(String path, String anchor, String text) {
        return replaceOnce(path, anchor, anchor + text);
    }

    public SourceEdits create(String path, String content) {
        if (Files.exists(root.resolve(path)) || edited.containsKey(path)) {
            throw new RecipeNotApplicable("file to create already exists: " + path);
        }
        edited.put(path, content);
        return this;
    }

    /**
     * Codemod: appends {@code argument} as a new last argument to every
     * {@code new <type>(...)} call in every .java file under {@code dir} (except
     * {@code excluded}). It's lexer-aware: occurrences inside string literals,
     * text blocks, char literals, and comments are left alone, and parentheses
     * inside them don't count. Nested calls (including nested calls of the same
     * type) are handled.
     *
     * @return the number of call sites changed
     */
    public int appendConstructorArgument(String dir, String type, String argument, List<String> excluded) {
        String marker = "new " + type + "(";
        int[] changed = {0};
        for (String path : javaFiles(dir)) {
            if (excluded.contains(path)) {
                continue;
            }
            String content = read(path);
            if (!content.contains(marker)) {
                continue;
            }
            int before = changed[0];
            String rewritten = rewrite(content, marker, argument, path, changed);
            if (changed[0] > before) {
                edited.put(path, rewritten);
            }
        }
        return changed[0];
    }

    private static String rewrite(String s, String marker, String argument, String path, int[] changed) {
        StringBuilder out = new StringBuilder(s.length() + 64);
        int i = 0;
        while (i < s.length()) {
            int end = skipNonCode(s, i);
            if (end != i) {
                out.append(s, i, end);
                i = end;
            } else if (s.startsWith(marker, i) && (i == 0 || !Character.isJavaIdentifierPart(s.charAt(i - 1)))) {
                int open = i + marker.length() - 1;
                int close = matchingParen(s, open, path);
                String args = rewrite(s.substring(open + 1, close), marker, argument, path, changed);
                out.append(s, i, open + 1).append(args.isBlank() ? argument : args + ", " + argument).append(')');
                changed[0]++;
                i = close + 1;
            } else {
                out.append(s.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /** If a comment or a string/char/text-block literal starts at {@code i}, returns the index just after it; otherwise {@code i}. */
    static int skipNonCode(String s, int i) {
        if (s.startsWith("//", i)) {
            int nl = s.indexOf('\n', i);
            return nl < 0 ? s.length() : nl;
        }
        if (s.startsWith("/*", i)) {
            int close = s.indexOf("*/", i + 2);
            return close < 0 ? s.length() : close + 2;
        }
        if (s.startsWith("\"\"\"", i)) {
            for (int j = i + 3; j < s.length(); j++) {
                if (s.charAt(j) == '\\') {
                    j++;
                } else if (s.startsWith("\"\"\"", j)) {
                    return j + 3;
                }
            }
            return s.length();
        }
        char c = s.charAt(i);
        if (c == '"' || c == '\'') {
            for (int j = i + 1; j < s.length(); j++) {
                char d = s.charAt(j);
                if (d == '\\') {
                    j++;
                } else if (d == c || d == '\n') {
                    return j + 1;
                }
            }
            return s.length();
        }
        return i;
    }

    private static int matchingParen(String s, int open, String path) {
        int depth = 0;
        int i = open;
        while (i < s.length()) {
            int end = skipNonCode(s, i);
            if (end != i) {
                i = end;
                continue;
            }
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
            i++;
        }
        throw new RecipeNotApplicable("unbalanced parentheses in " + path);
    }

    private List<String> javaFiles(String dir) {
        Path base = root.resolve(dir);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(base)) {
            List<String> paths = new ArrayList<>();
            walk.filter(p -> p.toString().endsWith(".java")).sorted()
                    .forEach(p -> paths.add(root.relativize(p).toString().replace('\\', '/')));
            return paths;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Edited files in edit order, path → full new content. */
    public Map<String, String> edits() {
        return new LinkedHashMap<>(edited);
    }
}
