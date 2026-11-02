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
 *   <li>bounded output capture: only the last {@value #OUTPUT_TAIL} characters are kept.</li>
 *   <li>optional stdin from a string, so a patch can be piped to {@code git apply} without a temp file.</li>
 * </ul>
 * Callers decide WHICH commands may run. Only configuration-defined commands
 * and fixed git invocations are ever passed here, never model output.
 */
public final class ProcessRunner {

    public static final int OUTPUT_TAIL = 4000;

    public record Result(List<String> argv, Integer exitCode, String output, boolean timedOut, String error, long durationMs) {
        public boolean succeeded() {
            return exitCode != null && exitCode == 0;
        }
    }

    private ProcessRunner() {
    }

    public static Result run(List<String> argv, Path directory, Duration timeout, String stdin) {
        long start = System.nanoTime();
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            if (directory != null) {
                pb.directory(directory.toFile());
            }
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = pb.start();
        } catch (IOException e) {
            return new Result(List.copyOf(argv), null, "", false, "could not start: " + e.getMessage(), elapsed(start));
        }
        Tail tail = new Tail(process.getInputStream());
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
                return new Result(List.copyOf(argv), null, tail.text(), true, "timed out after " + timeout, elapsed(start));
            }
            reader.join(5000);
            return new Result(List.copyOf(argv), process.exitValue(), tail.text(), false, null, elapsed(start));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new Result(List.copyOf(argv), null, tail.text(), false, "interrupted", elapsed(start));
        }
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static final class Tail implements Runnable {
        private final InputStream in;
        private final StringBuilder buffer = new StringBuilder();

        Tail(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[4096];
            try (InputStream stream = in) {
                int n;
                while ((n = stream.read(chunk)) != -1) {
                    synchronized (buffer) {
                        buffer.append(new String(chunk, 0, n, StandardCharsets.UTF_8));
                        if (buffer.length() > OUTPUT_TAIL * 2) {
                            buffer.delete(0, buffer.length() - OUTPUT_TAIL);
                        }
                    }
                }
            } catch (IOException ignored) {
                // stream closed when the process ended or was destroyed
            }
        }

        String text() {
            synchronized (buffer) {
                return buffer.length() <= OUTPUT_TAIL ? buffer.toString() : buffer.substring(buffer.length() - OUTPUT_TAIL);
            }
        }
    }
}
