// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jaeseongs95.sennard.server.execution.ExecutionHandle;
import io.github.jaeseongs95.sennard.server.execution.ExecutionSnapshot;
import io.github.jaeseongs95.sennard.server.execution.ExecutionState;
import io.github.jaeseongs95.sennard.server.execution.ProcessIdentity;
import io.github.jaeseongs95.sennard.server.execution.StopDeadline;
import io.github.jaeseongs95.sennard.server.execution.StreamOutput;

/**
 * One execution of {@link LocalProcessRunner}: a program that was started, or one that could not be. The program is watched by one virtual thread, which ends it at its time limit,
 * lists the processes it starts, and when it has ended finishes its output and answers {@link #ended()}.
 *
 * <p>Whoever ends the program says why before it kills (first one wins: {@link Cause}); the watching thread reads that when the program is gone, so the state is what the first reason
 * says and a kill that came second changes nothing. The watching thread is the one place the end is made: it finishes the output once, builds the final snapshot once, tells the
 * runner ({@code onEnded}, which frees the place of the execution) and only then completes {@link #ended()}, so a caller that wakes on it finds the place free.
 */
final class LocalExecution implements ExecutionHandle {
    private static final Logger LOG = LoggerFactory.getLogger(LocalExecution.class);
    /** How often the processes the program started are listed while it runs; what is found is killed with it. */
    private static final long SAMPLE_MILLIS = 200;
    /** The longest a call waits for the end of an execution it ended: the program's kill, the drain of its output and the answer. */
    private static final Duration END_WAIT = Duration.ofSeconds(30);
    /** The longest wait {@link #await} takes as given; a longer one is a day. */
    private static final Duration MAX_WAIT = Duration.ofDays(1);

    /** Why a program was ended before it ended by itself. */
    private enum Cause {
        TIMED_OUT,
        KILLED
    }

    private final String id;
    private final Instant requestedAt;
    private final Clock clock;
    private final Duration timeout;
    private final Process process;
    private final ProcessTree child;
    private final ProcessOutput output;
    private final ProcessIdentity identity;
    private final Path logLocation;
    private final Consumer<LocalExecution> onEnded;
    /** The deadline of the stop of the server: the waits of the kill, of the output and of an ended execution are not longer than what is left of it while the server stops. */
    private final StopDeadline stop;
    private final AtomicReference<Cause> cause = new AtomicReference<>();
    private final CompletableFuture<ExecutionSnapshot> ended = new CompletableFuture<>();
    private volatile ExecutionSnapshot last;

    /** A program that is running; {@link #watch()} is what the runner then runs on a thread of its own. */
    LocalExecution(String id, Instant requestedAt, Clock clock, Duration timeout, Process process, ProcessTree child, ProcessOutput output, ProcessIdentity identity,
            Path logLocation, Consumer<LocalExecution> onEnded, StopDeadline stop) {
        this.id = id;
        this.requestedAt = requestedAt;
        this.clock = clock;
        this.timeout = timeout;
        this.process = process;
        this.child = child;
        this.output = output;
        this.identity = identity;
        this.logLocation = logLocation;
        this.onEnded = onEnded;
        this.stop = stop;
    }

    /** A program that was not started: already ended, with no process and no output. */
    LocalExecution(String id, Instant requestedAt, Clock clock, String failure) {
        this.id = id;
        this.requestedAt = requestedAt;
        this.clock = clock;
        this.timeout = Duration.ZERO;
        this.process = null;
        this.child = null;
        this.output = null;
        this.identity = null;
        this.logLocation = null;
        this.onEnded = null;
        this.stop = null;
        ExecutionSnapshot snapshot = new ExecutionSnapshot(id, ExecutionState.NOT_STARTED, null, requestedAt, clock.instant(), null, null, StreamOutput.EMPTY, StreamOutput.EMPTY, failure);
        this.last = snapshot;
        this.ended.complete(snapshot);
    }

    @Override
    public String id() {
        return id;
    }

    boolean finished() {
        return ended.isDone();
    }

    @Override
    public ExecutionSnapshot snapshot() {
        ExecutionSnapshot done = last;
        if (done != null) {
            return done;
        }
        return new ExecutionSnapshot(id, ExecutionState.RUNNING, null, requestedAt, null, identity, logLocation.toString(), outputOf(output.stdout()), outputOf(output.stderr()), null);
    }

    @Override
    public ExecutionSnapshot await(Duration wait) throws InterruptedException {
        Duration bounded = wait.isNegative() ? Duration.ZERO : wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait;
        try {
            // A copy that completes with null when the wait is over: the end is the answer when it came in time, and the execution as it is now when not.
            ExecutionSnapshot done = ended.copy().completeOnTimeout(null, bounded.toNanos(), TimeUnit.NANOSECONDS).get();
            return done != null ? done : snapshot();
        } catch (ExecutionException impossible) {
            // ended is only ever completed normally; this is a defect of this class and is not made into an answer
            throw new IllegalStateException("The end of execution " + id + " failed", impossible);
        }
    }

    @Override
    public CompletableFuture<ExecutionSnapshot> ended() {
        return ended;
    }

    @Override
    public ExecutionSnapshot cancel() {
        requestCancel();
        return awaitEnd();
    }

    /**
     * Asks for the end of the program and what it started, and does not wait: the watch of the execution finishes the kill of the tree and makes the end. The tree is listed before the
     * program is killed.
     */
    void requestCancel() {
        if (!finished() && process.isAlive() && cause.compareAndSet(null, Cause.KILLED)) {
            // ceiling: a program that ends by itself between the check of isAlive and the kill is still called KILLED (its exit code is lost); the window is a few instructions
            child.requestKill();
        }
    }

    /** Waits (bounded) for the end of an execution this call has just ended or that is ending. */
    private ExecutionSnapshot awaitEnd() {
        // join() does not stop at an interrupt of the caller: the kill is already done, and what is waited for is its answer, which is bounded.
        if (finished()) {
            // also an execution that was not started: it has no deadline of a stop to ask
            return ended.join();
        }
        Duration wait = stop.limit(END_WAIT);
        ExecutionSnapshot done = ended.copy().completeOnTimeout(null, wait.toNanos(), TimeUnit.NANOSECONDS).join();
        if (done == null) {
            LOG.warn("The execution {} did not end within {} after it was ended.", id, wait);
            return snapshot();
        }
        return done;
    }

    /** The watch of the program, run on one thread: from the first listing of what it started to the answer of {@link #ended()}. */
    void watch() {
        try {
            child.sample();
            waitForTheProgram();
            // The program is gone (it ended, or it was killed): whatever it started that is still alive goes too.
            child.kill(stop::limit);
        } catch (InterruptedException interrupted) {
            cause.compareAndSet(null, Cause.KILLED);
            child.kill(stop::limit);
            Thread.currentThread().interrupt();
        } finally {
            if (process.isAlive()) {
                // an unexpected failure of the watch must not leave the program running
                cause.compareAndSet(null, Cause.KILLED);
                child.kill(stop::limit);
            }
            end();
        }
    }

    /** Waits for the program to be gone: by itself, at its time limit (killed here), or killed by another. */
    private void waitForTheProgram() throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        long nextSample = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SAMPLE_MILLIS);
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                if (process.isAlive() && cause.compareAndSet(null, Cause.TIMED_OUT)) {
                    child.kill(stop::limit);
                }
                // another's kill (a cancel, the server's stop) may be going on: wait (bounded) until the program is gone; one that is still alive then is killed again by the end
                if (!process.waitFor(END_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    LOG.warn("The program of execution {} was still alive {} after the limit.", id, END_WAIT);
                }
                return;
            }
            if (process.waitFor(Math.min(SAMPLE_MILLIS, Math.max(1, TimeUnit.NANOSECONDS.toMillis(left))), TimeUnit.MILLISECONDS)) {
                return;
            }
            if (System.nanoTime() - nextSample >= 0) {
                child.sample();
                nextSample = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SAMPLE_MILLIS);
            }
        }
    }

    /** The one place the end is made, for every way out of the watch (an unexpected exception included). */
    private void end() {
        ExecutionSnapshot snapshot;
        try {
            // The output is finished once, whichever way the program ended. An interrupt that was set again does not cut the wait short (it is not interruptible).
            output.finish(stop::limit);
            Cause why = cause.get();
            ExecutionState state = why == Cause.TIMED_OUT ? ExecutionState.TIMED_OUT : why == Cause.KILLED ? ExecutionState.KILLED : ExecutionState.EXITED;
            Integer exitCode = state == ExecutionState.EXITED ? Integer.valueOf(process.exitValue()) : null;
            snapshot = new ExecutionSnapshot(id, state, exitCode, requestedAt, clock.instant(), identity, logLocation.toString(), outputOf(output.stdout()), outputOf(output.stderr()), null);
        } catch (RuntimeException unexpected) {
            // A defect must not leave the execution running for ever: it is ended as killed, with what is known, and the defect is logged.
            LOG.error("The end of execution {} failed; it is recorded as killed.", id, unexpected);
            snapshot = new ExecutionSnapshot(id, ExecutionState.KILLED, null, requestedAt, clock.instant(), identity, logLocation.toString(), StreamOutput.EMPTY, StreamOutput.EMPTY, null);
        }
        last = snapshot;
        try {
            onEnded.accept(this);
        } finally {
            ended.complete(snapshot);
        }
    }

    /** What the memory kept of a stream, as text; the two ends are decoded apart only when something is between them. */
    static StreamOutput outputOf(ProcessOutput.Kept kept) {
        long omitted = kept.omitted();
        if (omitted == 0) {
            byte[] whole = new byte[kept.head().length + kept.tail().length];
            System.arraycopy(kept.head(), 0, whole, 0, kept.head().length);
            System.arraycopy(kept.tail(), 0, whole, kept.head().length, kept.tail().length);
            return new StreamOutput(new String(whole, StandardCharsets.UTF_8), "", kept.total(), 0, kept.logTruncated());
        }
        return new StreamOutput(new String(kept.head(), StandardCharsets.UTF_8), new String(kept.tail(), StandardCharsets.UTF_8), kept.total(), omitted, kept.logTruncated());
    }
}
