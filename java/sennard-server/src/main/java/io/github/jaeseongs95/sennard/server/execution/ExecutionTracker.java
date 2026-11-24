// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.execution.ExecutionRecords;
import io.github.jaeseongs95.sennard.app.execution.ExecutionRecords.NewExecution;
import io.github.jaeseongs95.sennard.app.execution.ExecutionStatus;
import io.github.jaeseongs95.sennard.app.execution.LogCompleteness;
import io.github.jaeseongs95.sennard.server.workflow.HostIdentity;

/**
 * Runs a program through the {@link ProcessRunner} and keeps its record ({@code sennard.execution}): the record is written as soon as the program was started (running, with the
 * process that was started) and closed when the program ends, whenever that is. It is the one place that joins the two; the runner knows no record and the records know no process.
 *
 * <p>The record is written after the start and not before: the process id and its start time are only known then, and a restarted server needs them to find the process again. A
 * program that ended before its record was written gets its record already closed. A caller that is inside a transaction is refused before anything is started
 * ({@link ExecutionRecords#requireOutsideTransaction}: the thread that notices the end of the program would not find a row that is not committed); a record that cannot be written for
 * another reason kills the program that was started and the failure is thrown: a program that runs without a record is one nobody can account for after a restart.
 *
 * <p>What the caller gets is a {@link RecordedExecution}: its end ({@link ExecutionHandle#ended}, {@link ExecutionHandle#await}, {@link ExecutionHandle#cancel}) is answered after the
 * record has been closed, so a caller that has seen the end and asks for the record finds it closed. The log files of that execution are not deleted by the pass of the log retention
 * that follows ({@link ExecutionLogRetention}): the caller was told where they are. That pass is not part of the closing: the closing is done when the record is closed, the pass runs
 * after it on a thread of its own, and none is started once the server is stopping.
 *
 * <p>The id of the execution is made here ({@link ExecutionIds#next()}) and handed to the runner: from before the runner is asked to start the program until the write of its record has
 * ended, the execution is in {@link ExecutionsBeingRecorded}. A program that ends in that time has no record and is not running, and the retention must not take its folder for one that
 * was never recorded; the id is protected before the runner makes the folder, so there is no moment in which the folder is there and the id is not. An execution that the runner refuses or
 * fails to start is not in it afterwards and has left nothing behind.
 *
 * <p>The order in which the server stops is: the runner ends every running program ({@link #PHASE} is just below the runner's), then this closes the records of the programs that
 * ended and waits for those closings, then the store closes. Otherwise a record closed after the stop returned would find the store gone, stay RUNNING and be LOST at the next start.
 * A program that is being started when the stop begins is either in the list of closings the stop waits for, or refused: the two are separated by a lock. The stop waits for that
 * lock, and then for the closings, both for what is left of the deadline of the stop ({@link StopDeadline}, shared with the runner, whose end of the programs came first and took
 * its part of it): a start whose write of the record is held up (the database does not answer) does not hold the stop of the server for ever; the stop says so in the log and goes
 * on, and a start that comes after the stop began is refused in any case. A record that is not closed by the deadline stays RUNNING, and {@link ExecutionRecovery} accounts for it at
 * the next start.
 */
@Component
public class ExecutionTracker implements SmartLifecycle {
    /** The lifecycle phase of this: the runner of the executions has the one above it, so the server's stop asks it first (a higher phase stops first). */
    public static final int PHASE = Integer.MAX_VALUE - 1;
    private static final Logger LOG = LoggerFactory.getLogger(ExecutionTracker.class);

    private final ProcessRunner runner;
    private final ExecutionRecords records;
    private final HostIdentity host;
    private final ExecutionLogStore logs;
    private final ExecutionLogRetention retention;
    private final ExecutionsBeingRecorded recording;
    private final StopDeadline stopDeadline;
    /** Where the pass of the retention after an end runs: not on the thread that closed the record. */
    private final Executor cleanups;
    /** The closings that have not finished: one for each running program that is recorded. */
    private final Set<CompletableFuture<Void>> closing = ConcurrentHashMap.newKeySet();
    /** Held (read) by a start from the start of the program to its place in {@link #closing}; held (write) by the stop for the moment it refuses new starts and takes the list. */
    private final ReadWriteLock starting = new ReentrantReadWriteLock();
    private volatile boolean active;
    private volatile boolean stopping;

    @Autowired
    public ExecutionTracker(ProcessRunner runner, ExecutionRecords records, HostIdentity host, ExecutionLogStore logs, ExecutionLogRetention retention, ExecutionsBeingRecorded recording,
            StopDeadline stopDeadline) {
        this(runner, records, host, logs, retention, recording, stopDeadline, command -> Thread.ofVirtual().name("log-retention").start(command));
    }

    /**
     * @param stopDeadline the deadline of the stop of the server that the stop waits within: for a start that is going on, and then for the closings of the records
     * @param cleanups where the pass of the log retention after an end is run
     */
    public ExecutionTracker(ProcessRunner runner, ExecutionRecords records, HostIdentity host, ExecutionLogStore logs, ExecutionLogRetention retention, ExecutionsBeingRecorded recording,
            StopDeadline stopDeadline, Executor cleanups) {
        this.runner = runner;
        this.records = records;
        this.host = host;
        this.logs = logs;
        this.retention = retention;
        this.recording = recording;
        this.stopDeadline = stopDeadline;
        this.cleanups = cleanups;
    }

    /**
     * Starts the program, records it and waits at most {@code wait} for its end.
     *
     * @return the execution after the wait: its end when it came in time, {@link ExecutionState#RUNNING} when not
     * @throws Refused when the runner did not take the execution, or the server is stopping; nothing was recorded
     * @throws org.springframework.transaction.IllegalTransactionStateException when the caller is inside a transaction; nothing was started
     * @throws InterruptedException when the wait is interrupted; the execution goes on and is recorded
     */
    public ExecutionSnapshot start(ExecutionOrigin origin, ExecutionRequest request, Duration wait) throws InterruptedException {
        return begin(origin, request).await(wait);
    }

    /**
     * Starts the program and records it, without waiting. The record is closed when the execution of the returned handle ends, and the handle's end is answered after that.
     *
     * @throws Refused when the runner did not take the execution, or the server is stopping
     * @throws IllegalArgumentException when the runner rejects the id it was given (an execution with that id is held by it, or its log folder is there already); nothing was recorded
     * @throws org.springframework.transaction.IllegalTransactionStateException when the caller is inside a transaction; nothing was started
     */
    public ExecutionHandle begin(ExecutionOrigin origin, ExecutionRequest request) {
        records.requireOutsideTransaction();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        ExecutionHandle handle;
        ExecutionSnapshot first;
        starting.readLock().lock();
        try {
            if (stopping) {
                throw Refused.stopping();
            }
            // the id is made here and protected before the runner is asked for anything: the folder of its log files is made by the runner, and a program that ends at once has a folder that
            // no record names and the runner does not name either (it is no longer running) until the write of its record has ended
            String id = ExecutionIds.next();
            recording.begin(id);
            try {
                handle = runner.start(id, request);
                first = handle.snapshot();
                try {
                    records.begin(newExecution(origin, request, first));
                } catch (RuntimeException notRecorded) {
                    handle.cancel();
                    throw notRecorded;
                }
            } finally {
                recording.end(id);
            }
            if (first.state() == ExecutionState.RUNNING) {
                closing.add(closed);
            }
        } finally {
            starting.readLock().unlock();
        }
        if (first.state() != ExecutionState.RUNNING) {
            return new RecordedExecution(handle, CompletableFuture.completedFuture(first));
        }
        CompletableFuture<ExecutionSnapshot> recorded = handle.ended().thenApply(this::close);
        // the closing is done when the record is closed; the pass of the retention is after it and is not waited for by it
        recorded.whenComplete((ended, failure) -> {
            closing.remove(closed);
            closed.complete(null);
            if (ended != null) {
                cleanUpAfter(ended);
            }
        });
        return new RecordedExecution(handle, recorded);
    }

    private NewExecution newExecution(ExecutionOrigin origin, ExecutionRequest request, ExecutionSnapshot first) {
        ProcessIdentity process = first.process();
        Long logBytes = first.state() == ExecutionState.RUNNING ? null : LogSizes.of(logs, first.id(), first.logLocation());
        return new NewExecution(first.id(), origin.runId(), origin.kind(), origin.requestedBy(), host.id(), request.argv(), request.workingDirectory().toString(), statusOf(first.state()),
                first.exitCode(), first.requestedAt(), first.endedAt(), first.logLocation(), LogCompleteness.of(first.logTruncated()), logBytes, process == null ? null : process.pid(),
                process == null ? null : process.startedAt());
    }

    /** Runs on the thread that ended the execution; a failure is logged, because nothing above it can be told. The record then stays RUNNING and is accounted for at the next start. */
    private ExecutionSnapshot close(ExecutionSnapshot ended) {
        try {
            Long logBytes = LogSizes.of(logs, ended.id(), ended.logLocation());
            boolean closed = records.finish(ended.id(), statusOf(ended.state()), ended.exitCode(), ended.endedAt(), LogCompleteness.of(ended.logTruncated()), logBytes);
            if (!closed) {
                LOG.warn("The record of execution {} was not running when it ended: it was closed by another.", ended.id());
            }
        } catch (RuntimeException notClosed) {
            LOG.error("The record of execution {} could not be closed as {}.", ended.id(), ended.state(), notClosed);
        }
        return ended;
    }

    /** After the record was closed: has the log files past their retention deleted, unless the server is stopping (a pass that began then would find the store going). */
    private void cleanUpAfter(ExecutionSnapshot ended) {
        if (!stopping) {
            cleanups.execute(() -> cleanUp(ended));
        }
    }

    /** Deletes the log files that are past their retention, except those of the execution that has just ended (the caller was told where they are). */
    private void cleanUp(ExecutionSnapshot ended) {
        try {
            retention.cleanUp(Set.of(ended.id()));
        } catch (RuntimeException notCleaned) {
            LOG.error("The log files past their retention could not be deleted after execution {} ended.", ended.id(), notCleaned);
        }
    }

    private static ExecutionStatus statusOf(ExecutionState state) {
        return switch (state) {
            case RUNNING -> ExecutionStatus.RUNNING;
            case EXITED -> ExecutionStatus.EXITED;
            case TIMED_OUT -> ExecutionStatus.TIMED_OUT;
            case KILLED -> ExecutionStatus.KILLED;
            case NOT_STARTED -> ExecutionStatus.NOT_STARTED;
        };
    }

    // ---- the server's life

    @Override
    public void start() {
        stopDeadline.reset();
        stopping = false;
        active = true;
    }

    /**
     * Refuses the starts that come after it, and waits (bounded by the deadline of the stop) for the records of the executions that have ended to be closed: the runner, which has the
     * phase above, has ended every program before this is asked. A start that is going on is let finish first (it holds the read lock until its closing is in the list), so the list is
     * complete when it is taken.
     */
    @Override
    public void stop() {
        // before the lock is asked for: a start that comes from now on is refused, whether or not the stop gets the lock; and a tracker that is no longer running is one that refuses
        stopping = true;
        active = false;
        stopDeadline.arm();
        CompletableFuture<?>[] open = closings();
        if (open.length == 0) {
            return;
        }
        Duration left = stopDeadline.remaining();
        if (!left.isZero()) {
            CompletableFuture.allOf(open).completeOnTimeout(null, left.toNanos(), TimeUnit.NANOSECONDS).join();
        }
        long unclosed = Arrays.stream(open).filter(closed -> !closed.isDone()).count();
        if (unclosed > 0) {
            LOG.error("{} execution record(s) were not closed within the deadline of the stop of the server; they stay RUNNING and are accounted for at the next start.", unclosed);
        }
    }

    /**
     * The closings that have not finished, taken when no start is going on. A start that holds the lock past the deadline of the stop (its record is not written: the database does not
     * answer) is not waited for any longer: that is logged, and the closings it has not added are not in the answer.
     */
    private CompletableFuture<?>[] closings() {
        boolean locked = false;
        try {
            locked = starting.writeLock().tryLock(stopDeadline.remaining().toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.error("The wait of the stop of the server for the starts that are going on was interrupted; the stop goes on, and a start that is going on may not be waited for.", interrupted);
        }
        if (!locked) {
            LOG.error("A start of an execution was still going on at the deadline of the stop of the server (the write of its record is held up); the stop goes on without waiting for it, "
                    + "and its record may stay RUNNING and be accounted for at the next start. Starts that come now are refused.");
            return closing.toArray(CompletableFuture<?>[]::new);
        }
        try {
            return closing.toArray(CompletableFuture<?>[]::new);
        } finally {
            starting.writeLock().unlock();
        }
    }

    @Override
    public boolean isRunning() {
        return active;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
