// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * An execution of the {@link ExecutionTracker}: the runner's execution whose end is answered after its record has been closed. A caller that has seen the end (through
 * {@link #ended()}, {@link #await}, {@link #cancel()} or {@link #snapshot()}) and asks for the record finds it closed: until then the execution is shown as running, with the output
 * it has, as the record still says.
 */
final class RecordedExecution implements ExecutionHandle {
    /** The longest a call waits for the end of an execution it ended: the runner's own bound, for the kill, the drain of the output and the closing of the record. */
    private static final Duration END_WAIT = Duration.ofSeconds(30);
    /** The longest wait {@link #await} takes as given; a longer one is a day. */
    private static final Duration MAX_WAIT = Duration.ofDays(1);

    private final ExecutionHandle runner;
    private final CompletableFuture<ExecutionSnapshot> recorded;

    /**
     * @param runner the execution as the runner holds it
     * @param recorded completes with the final snapshot after the record was closed; it never completes exceptionally
     */
    RecordedExecution(ExecutionHandle runner, CompletableFuture<ExecutionSnapshot> recorded) {
        this.runner = runner;
        this.recorded = recorded;
    }

    @Override
    public String id() {
        return runner.id();
    }

    @Override
    public ExecutionSnapshot snapshot() {
        return asRecorded(runner.snapshot());
    }

    /** The runner's snapshot as the record has it: an end whose record is not closed yet is not shown (the execution is running, as the record says). */
    private ExecutionSnapshot asRecorded(ExecutionSnapshot now) {
        if (recorded.isDone() || !now.state().finished()) {
            return now;
        }
        return new ExecutionSnapshot(now.id(), ExecutionState.RUNNING, null, now.requestedAt(), null, now.process(), now.logLocation(), now.stdout(), now.stderr(), null);
    }

    @Override
    public ExecutionSnapshot await(Duration wait) throws InterruptedException {
        Duration bounded = wait.isNegative() ? Duration.ZERO : wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait;
        try {
            // A copy that completes with null when the wait is over: the end is the answer when it came in time, and the execution as it is now when not.
            ExecutionSnapshot done = recorded.copy().completeOnTimeout(null, bounded.toNanos(), TimeUnit.NANOSECONDS).get();
            return done != null ? done : asRecorded(runner.snapshot());
        } catch (ExecutionException impossible) {
            throw new IllegalStateException("The end of execution " + id() + " failed", impossible);
        }
    }

    @Override
    public CompletableFuture<ExecutionSnapshot> ended() {
        return recorded;
    }

    @Override
    public ExecutionSnapshot cancel() {
        ExecutionSnapshot ended = runner.cancel();
        if (ended.state() == ExecutionState.RUNNING) {
            return ended;
        }
        // the kill is done; what is waited for is the closing of the record, which is bounded
        ExecutionSnapshot done = recorded.copy().completeOnTimeout(null, END_WAIT.toNanos(), TimeUnit.NANOSECONDS).join();
        return done != null ? done : asRecorded(ended);
    }
}
