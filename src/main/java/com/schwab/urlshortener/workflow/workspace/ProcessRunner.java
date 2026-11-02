package com.schwab.urlshortener.workflow.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs one external command. This is the only place in the orchestrator that
 * starts a process. Properties every caller gets:
 * <ul>
 *   <li>argv via {@link ProcessBuilder}, no shell: arguments are never interpreted (no globbing, pipes, or
 *       {@code ;}).</li>
 *   <li>a hard timeout: the process tree is destroyed when it expires.</li>
 *   <li>bounded output capture: by default only the last {@value #OUTPUT_TAIL} characters are kept. A caller that
 *       needs the whole output (a diff) passes a larger limit and must check {@link Result#outputTruncated()}: a
 *       silently truncated diff would be recorded as the patch and could never be re-applied.</li>
 *   <li>optional stdin from a string, so a patch can be piped to {@code git apply} without a temp file.</li>
 * </ul>
 * On Windows a bare executable name with no {@code .exe} on PATH (e.g. {@code mvn})
 * is resolved to its {@code .cmd}/{@code .bat} launcher ({@link #resolveExecutable}).
 * Windows runs those through {@code cmd.exe}, so this is only safe because every
 * argument comes from configuration or is a sanitized identifier, never free text.
 *
 * Callers decide WHICH commands may run. Only configuration-defined commands
 * and fixed git invocations are ever passed here, never model output.
 */
public final class ProcessRunner {

    public static final int OUTPUT_TAIL = 4000;

    public record Result(List<String> argv, Integer exitCode, String output, boolean outputTruncated, boolean timedOut, String error,
                         long durationMs) {
        public boolean succeeded() {
            return exitCode != null && exitCode == 0;
        }
    }

    private ProcessRunner() {
    }

    public static Result run(List<String> argv, Path directory, Duration timeout, String stdin) {
        return run(argv, directory, timeout, stdin, OUTPUT_TAIL);
    }

    /** As {@link #run(List, Path, Duration, String)}, keeping up to {@code maxOutputChars} of output (the last ones). */
    public static Result run(List<String> argv, Path directory, Duration timeout, String stdin, int maxOutputChars) {
        long start = System.nanoTime();
        Process process;
        try {
            List<String> resolved = new java.util.ArrayList<>(argv);
            if (!resolved.isEmpty()) {
                resolved.set(0, resolveExecutable(resolved.get(0), System.getenv("PATH"), WINDOWS));
            }
            ProcessBuilder pb = new ProcessBuilder(resolved).redirectErrorStream(true);
            if (directory != null) {
                pb.directory(directory.toFile());
            }
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = pb.start();
        } catch (IOException e) {
            return new Result(List.copyOf(argv), null, "", false, false, "could not start: " + e.getMessage(), elapsed(start));
        }
        Tail tail = new Tail(process.getInputStream(), maxOutputChars);
        Thread reader = new Thread(tail, "workflow-process-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (stdin != null) {
                process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            process.getOutputStream().close();
        } catch (IOException e) {
            // the process may have exited early; its exit code will tell
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                reader.join(2000);
                return new Result(List.copyOf(argv), null, tail.text(), tail.truncated(), true, "timed out after " + timeout, elapsed(start));
            }
            reader.join(5000);
            return new Result(List.copyOf(argv), process.exitValue(), tail.text(), tail.truncated(), false, null, elapsed(start));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new Result(List.copyOf(argv), null, tail.text(), tail.truncated(), false, "interrupted", elapsed(start));
        }
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");

    /**
     * On Windows, maps a bare name like {@code mvn} to the first {@code mvn.cmd}/{@code mvn.bat} on PATH when
     * there is no {@code mvn.exe} (which ProcessBuilder would find itself). Anything else is returned unchanged.
     */
    public static String resolveExecutable(String executable, String path, boolean windows) {
        if (!windows || path == null || executable.contains("/") || executable.contains("\\") || executable.contains(".")) {
            return executable;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                if (java.nio.file.Files.isRegularFile(Path.of(dir, executable + ".exe"))) {
                    return executable;
                }
                for (String ext : List.of(".cmd", ".bat")) {
                    Path candidate = Path.of(dir, executable + ext);
                    if (java.nio.file.Files.isRegularFile(candidate)) {
                        return candidate.toString();
                    }
                }
            } catch (java.nio.file.InvalidPathException ignored) {
                // malformed PATH entry: skip it
            }
        }
        return executable;
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static final class Tail implements Runnable {
        private final InputStream in;
        private final int limit;
        private final StringBuilder buffer = new StringBuilder();
        private boolean truncated;

        Tail(InputStream in, int limit) {
            this.in = in;
            this.limit = limit;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[4096];
            try (InputStream stream = in) {
                int n;
                while ((n = stream.read(chunk)) != -1) {
                    synchronized (buffer) {
                        buffer.append(new String(chunk, 0, n, StandardCharsets.UTF_8));
                        if (buffer.length() > limit * 2L) {
                            buffer.delete(0, buffer.length() - limit);
                            truncated = true;
                        }
                    }
                }
            } catch (IOException ignored) {
                // stream closed when the process ended or was destroyed
            }
        }

        String text() {
            synchronized (buffer) {
                return buffer.length() <= limit ? buffer.toString() : buffer.substring(buffer.length() - limit);
            }
        }

        boolean truncated() {
            synchronized (buffer) {
                return truncated || buffer.length() > limit;
            }
        }
    }
}
