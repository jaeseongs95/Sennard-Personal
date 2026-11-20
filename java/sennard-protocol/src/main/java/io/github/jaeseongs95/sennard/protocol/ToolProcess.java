// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs OS tools. {@link #run(Request)} captures output in a file with a deadline and an optional output limit. This keeps an inherited
 * output handle from holding up the caller and prevents a full pipe from stopping the tool. A tool that overruns the deadline or limit
 * is killed with everything it started. {@link #startStdio(List, Path, Map)} provides the streams for a live host protocol, whose caller
 * controls the protocol deadline and owns the process lifetime.
 */
public final class ToolProcess {
    /** The poll step of the wait: how often the output size is looked at and how late a deadline can be noticed. */
    private static final long POLL_MILLIS = 25;
    private static final long REAP_SECONDS = 10;

    /** What happens to the tool's error stream. */
    public enum Stderr {
        /** Into the output, in the order the tool wrote it. */
        MERGE,
        /** Dropped. */
        DISCARD,
        /** This process's own error stream. */
        INHERIT
    }

    /** What to run. {@code directory} and {@code input} may be null; a null value in {@code environment} removes that variable from the child's. */
    public record Request(List<String> command, Path directory, Map<String, String> environment, byte[] input, Stderr stderr, Duration timeout, long maxOutputBytes,
            boolean inheritEnvironment) {
        /** No output limit. */
        public static final long UNLIMITED = Long.MAX_VALUE;

        /** Existing callers inherit their environment; session-targeted transports explicitly opt out below. */
        public Request(List<String> command, Path directory, Map<String, String> environment, byte[] input, Stderr stderr, Duration timeout, long maxOutputBytes) {
            this(command, directory, environment, input, stderr, timeout, maxOutputBytes, true);
        }

        public Request {
            if (command.isEmpty()) {
                throw new IllegalArgumentException("A command needs a program.");
            }
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("A command needs a deadline: " + timeout);
            }
            if (maxOutputBytes < 0) {
                throw new IllegalArgumentException("The output limit is not negative: " + maxOutputBytes);
            }
            command = List.copyOf(command);
            environment = Collections.unmodifiableMap(new HashMap<>(environment));
        }

        public static Request of(List<String> command, Stderr stderr, Duration timeout) {
            return new Request(command, null, Map.of(), null, stderr, timeout, UNLIMITED);
        }

        public Request inDirectory(Path newDirectory) {
            return new Request(command, newDirectory, environment, input, stderr, timeout, maxOutputBytes, inheritEnvironment);
        }

        public Request withEnvironment(Map<String, String> overrides) {
            return new Request(command, directory, overrides, input, stderr, timeout, maxOutputBytes, inheritEnvironment);
        }

        public Request withInput(byte[] bytes) {
            return new Request(command, directory, environment, bytes, stderr, timeout, maxOutputBytes, inheritEnvironment);
        }

        public Request limitedTo(long bytes) {
            return new Request(command, directory, environment, input, stderr, timeout, bytes, inheritEnvironment);
        }

        public Request withoutInheritedEnvironment() {
            return new Request(command, directory, environment, input, stderr, timeout, maxOutputBytes, false);
        }
    }

    /** How a run ended. */
    public sealed interface Outcome {
    }

    /** The tool ended by itself, with any exit code; {@code output} is all it wrote (and its error stream under {@link Stderr#MERGE}). */
    public record Finished(int exitCode, byte[] output) implements Outcome {
    }

    /** The tool was still running at the deadline and was killed. */
    public record TimedOut() implements Outcome {
    }

    /** The tool wrote more than the limit and was killed (or ended with more than it). Partial output is never returned. */
    public record Overflowed() implements Outcome {
    }

    /** An I/O failure with an explicit launch boundary. A started failure cannot be retried as a known non-send. */
    public static final class RunFailure extends IOException {
        private static final long serialVersionUID = 1L;
        private final boolean started;

        private RunFailure(boolean started, IOException cause) {
            super(started ? "Tool I/O failed after launch" : "Tool could not start", cause);
            this.started = started;
        }

        public boolean started() {
            return started;
        }
    }

    private ToolProcess() {
    }

    /** A host protocol that exchanges requests and replies while the process stays open. The caller owns its protocol deadline. */
    public static Stdio startStdio(List<String> command, Path directory, Map<String, String> environment) throws IOException {
        ProcessBuilder builder = builder(command, directory, environment, false, Stderr.DISCARD);
        return new Stdio(builder.start());
    }

    public static final class Stdio implements AutoCloseable {
        private final Process process;

        private Stdio(Process process) {
            this.process = process;
        }

        public InputStream output() {
            return process.getInputStream();
        }

        public OutputStream input() {
            return process.getOutputStream();
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        public int waitFor() throws InterruptedException {
            return process.waitFor();
        }

        @Override
        public void close() {
            if (process.isAlive()) {
                kill(process);
            }
        }
    }

    /**
     * Runs the tool to its end, the deadline or the limit.
     *
     * @throws IOException when the tool cannot be started (not on the path, not executable, no such directory) or its output cannot be read
     * @throws InterruptedException when the calling thread is interrupted; the tool and what it started are killed first
     */
    public static Outcome run(Request request) throws IOException, InterruptedException {
        Path output = Files.createTempFile("sennard-tool-", ".out");
        Path input = null;
        Process process = null;
        try {
            ProcessBuilder builder = builder(request.command(), request.directory(), request.environment(), request.inheritEnvironment(), request.stderr())
                    .redirectOutput(output.toFile());
            if (request.input() != null) {
                input = Files.createTempFile("sennard-tool-", ".in");
                Files.write(input, request.input());
                builder.redirectInput(input.toFile());
            }
            long deadline = System.nanoTime() + request.timeout().toNanos();
            process = builder.start();
            if (request.input() == null) {
                // No input: the tool sees the end of its input at once instead of waiting on a pipe nobody writes.
                process.getOutputStream().close();
            }
            Outcome outcome = await(process, output, request.maxOutputBytes(), deadline);
            if (outcome instanceof Finished finished) {
                return new Finished(finished.exitCode(), Files.readAllBytes(output));
            }
            return outcome;
        } catch (IOException failure) {
            throw new RunFailure(process != null, failure);
        } finally {
            if (process != null && process.isAlive()) {
                kill(process);
            }
            discard(output);
            if (input != null) {
                discard(input);
            }
        }
    }

    private static ProcessBuilder builder(List<String> command, Path directory, Map<String, String> environment, boolean inheritEnvironment, Stderr stderr) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (!inheritEnvironment) {
            builder.environment().clear();
        }
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        environment.forEach((name, value) -> {
            if (value == null) {
                builder.environment().remove(name);
            } else {
                builder.environment().put(name, value);
            }
        });
        switch (stderr) {
            case MERGE -> builder.redirectErrorStream(true);
            case DISCARD -> builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            case INHERIT -> builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        }
        return builder;
    }

    /** Waits in short steps so the output size is looked at while the tool runs. A Finished it returns has no output yet; the caller reads the file. */
    private static Outcome await(Process process, Path output, long limit, long deadline) throws IOException, InterruptedException {
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return process.isAlive() ? new TimedOut() : ended(process, output, limit);
            }
            if (process.waitFor(Math.min(POLL_MILLIS, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))), TimeUnit.MILLISECONDS)) {
                return ended(process, output, limit);
            }
            if (Files.size(output) > limit) {
                return new Overflowed();
            }
        }
    }

    private static Outcome ended(Process process, Path output, long limit) throws IOException {
        return Files.size(output) > limit ? new Overflowed() : new Finished(process.exitValue(), new byte[0]);
    }

    /** The tool and everything it started. Called from a finally block, so an interrupt while waiting is handed back to the caller's thread. */
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(REAP_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException again) {
            Thread.currentThread().interrupt();
        }
    }

    /** A file a grandchild still holds open cannot be deleted on Windows; it is left to the JVM's exit, not forgotten. */
    private static void discard(Path file) {
        if (!file.toFile().delete() && Files.exists(file)) {
            file.toFile().deleteOnExit();
        }
    }
}
