// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.execution.EnvironmentSpec;
import io.github.jaeseongs95.sennard.server.execution.ExecutionIds;
import io.github.jaeseongs95.sennard.server.execution.ExecutionHandle;
import io.github.jaeseongs95.sennard.server.execution.ExecutionRequest;
import io.github.jaeseongs95.sennard.server.execution.ExecutionSnapshot;
import io.github.jaeseongs95.sennard.server.execution.ExecutionTracker;
import io.github.jaeseongs95.sennard.server.execution.ProcessIdentity;
import io.github.jaeseongs95.sennard.server.execution.ProcessRunner;
import io.github.jaeseongs95.sennard.server.execution.Refused;
import io.github.jaeseongs95.sennard.server.execution.StopDeadline;

/**
 * The programs of executions, started from their argv with no shell, on this computer ({@link ProcessRunner}). Whoever can connect to the server's loopback port can ask for one
 * through the tools that use this runner: the guard filter checks the headers and the body size of a request and does not ask who connected (it is not a peer check), so a program
 * has the authority of the server's user: on the personal edition that is this PC's user, and the loopback-only listening address is what keeps others out. What the runner itself
 * bounds is what a program can take from the computer:
 * <ul>
 * <li>how many run at once ({@code sennard.run.max-concurrent}); one asked for beyond that is refused at once, not queued;</li>
 * <li>how much of the output is kept: the memory keeps the first and the last bytes of each stream and the whole output goes to a log file per stream in the state folder
 * ({@code executions/<id>/}), up to the limit of the request; a program that writes more is not stopped for it ({@link ProcessOutput});</li>
 * <li>the environment: none of the server's own but the variables the request names ({@link EnvironmentSpec}); there is no default;</li>
 * <li>what is left behind: every running program is tracked, so the server's stop ({@link SmartLifecycle}) kills them with what they started, a program that ends on its own has the
 * processes it started that are still alive killed, and one that is still running at its time limit is killed with what it started.</li>
 * </ul>
 *
 * <p>A process the command started and detached from it can still hold the pipes of the output open after the command ended. When the command has ended the runner kills the
 * processes it knows, then {@link ProcessOutput#finish()} takes what is in the pipes, waits at most a short grace for a process that keeps writing, and closes its end of them
 * and ends its readers: a process that still writes gets a broken pipe, so the execution does not wait for it, and no reader thread, pipe end or file of the execution is left when it
 * has ended. The output is finished once for every execution, whichever way it ends.
 *
 * <p>The processes beneath the child are found and ended by {@link ProcessTree}: it takes a snapshot of them as soon as the child is started and then every few hundred milliseconds
 * while it runs, keeps the handles (a process the child started and left running is found after the child ended, and so is what a process that lost its parent started after
 * that), and kills the tree in rounds until no process of it is alive. A process that lost its parent before a snapshot saw it (the shell of {@code sh -c 'sleep 300 &'} ends at once
 * and the process it started has no parent of the child's by the first snapshot; on Windows the middle process of a doubled {@code start /b} ends first) is not found and is not
 * killed: it goes on running after the execution, cut off from the output only. The runner has no handle on such a process short of a process group or a job object, which Java does
 * not give a command it starts through {@link ProcessBuilder}.
 *
 * <p>The runner keeps every running execution and the last {@code sennard.run.retained-finished} finished ones, so a caller can ask about an execution by its id after the call that
 * started it has answered.
 */
@Component
public class LocalProcessRunner implements ProcessRunner, SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(LocalProcessRunner.class);
    /** How long the end of the output streams is waited for after the command ended and what was found beneath it was killed, before the runner closes its end of them. */
    private static final Duration DRAIN_GRACE = Duration.ofSeconds(2);
    /** The folder of the state folder that holds a folder of log files for each execution. */
    static final String LOG_FOLDER = "executions";

    private final int maxConcurrent;
    private final int retainedFinished;
    private final Path logs;
    private final BatchFileArguments batchFiles;
    private final ProgramLocator locator;
    private final Clock clock;
    /** The deadline of the stop of the server, shared with the tracker of the records: the stop waits for the end of the programs within what is left of it. */
    private final StopDeadline stopDeadline;
    private final Semaphore permits;
    /** Every running execution and the most recent finished ones, by id. */
    private final Map<String, LocalExecution> executions = new ConcurrentHashMap<>();
    /** The ids of the finished executions, oldest first; guarded by itself. */
    private final Deque<String> finishedOrder = new ArrayDeque<>();
    /** The ids of the executions being started: an id is here before the folder of its log files is made, and until the execution is in {@link #executions}. */
    private final Set<String> starting = ConcurrentHashMap.newKeySet();
    private volatile boolean active;
    private volatile boolean stopped;

    public LocalProcessRunner(SennardProperties settings, PersonalProperties personal, BatchFileArguments batchFiles, ProgramLocator locator, Clock clock, StopDeadline stopDeadline) {
        this.maxConcurrent = settings.run().maxConcurrent();
        this.retainedFinished = settings.run().retainedFinished();
        this.logs = personal.stateDir().resolve(LOG_FOLDER);
        this.batchFiles = batchFiles;
        this.locator = locator;
        this.clock = clock;
        this.stopDeadline = stopDeadline;
        this.permits = new Semaphore(maxConcurrent);
    }

    // ---- the server's life

    @Override
    public void start() {
        stopDeadline.reset();
        active = true;
    }

    /**
     * Refuses new executions and kills every running program with what it started: all of them are asked to end first, and the end of all of them is waited for once, so the time the
     * stop takes does not add up over the executions. The wait, and the waits of the executions themselves (the kill of the tree, the output that is left), are for what is left of
     * the deadline of the stop ({@link StopDeadline}); what has not ended by then is logged and the stop goes on.
     */
    @Override
    public void stop() {
        // armed before the runner refuses: a start that sees it refuse and ends its execution then finds the deadline
        stopDeadline.arm();
        stopped = true;
        active = false;
        List<LocalExecution> live = executions.values().stream().filter(execution -> !execution.finished()).toList();
        if (live.isEmpty()) {
            return;
        }
        LOG.info("The server is stopping: killing {} running program(s) of executions and what they started.", live.size());
        live.forEach(LocalExecution::requestCancel);
        CompletableFuture<?>[] ends = live.stream().map(LocalExecution::ended).toArray(CompletableFuture<?>[]::new);
        try {
            CompletableFuture.allOf(ends).get(stopDeadline.remaining().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException late) {
            LOG.warn("Not every execution ended within the deadline of the stop of the server.", late);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.warn("The wait for the executions to end was interrupted; the stop of the server goes on.", interrupted);
        } catch (ExecutionException impossible) {
            // the end of an execution is only ever completed normally; this is a defect and is not made into an answer
            throw new IllegalStateException("The end of an execution failed", impossible);
        }
    }

    /** Above the phase of {@link ExecutionTracker}, so that the server's stop asks this first: the records of the programs it ended are closed after it has returned. */
    @Override
    public int getPhase() {
        return ExecutionTracker.PHASE + 1;
    }

    @Override
    public boolean isRunning() {
        return active;
    }

    // ---- an execution

    @Override
    public ExecutionHandle start(String id, ExecutionRequest request) {
        if (!ExecutionIds.isExecutionId(id)) {
            throw new IllegalArgumentException("Not written as the id of an execution: " + id);
        }
        if (stopped) {
            throw Refused.stopping();
        }
        batchFiles.requireSafe(request.argv());
        if (!permits.tryAcquire()) {
            throw Refused.tooManyRuns(maxConcurrent);
        }
        // the id is held before the folder of the log files is made, so that whoever lists the folders and then asks which executions run does not take this one for a stray; one that is
        // held already (being started, running, or kept after it ended) is not taken again
        if (!starting.add(id)) {
            permits.release();
            throw heldAlready(id);
        }
        try {
            if (executions.containsKey(id)) {
                permits.release();
                throw heldAlready(id);
            }
            return startHeld(id, request);
        } finally {
            starting.remove(id);
        }
    }

    private static IllegalArgumentException heldAlready(String id) {
        return new IllegalArgumentException("An execution with this id is held by the runner: " + id);
    }

    private ExecutionHandle startHeld(String id, ExecutionRequest request) {
        LocalExecution execution;
        try {
            execution = launch(id, request);
        } catch (RuntimeException unexpected) {
            permits.release();
            throw unexpected;
        }
        if (execution.finished()) {
            // not started: nothing will end it later
            permits.release();
            remember(execution);
            return execution;
        }
        executions.put(execution.id(), execution);
        Thread.ofVirtual().name("run-watch").start(execution::watch);
        if (stopped) {
            // stop() looked at the running executions before this one was in the map
            execution.cancel();
        }
        return execution;
    }

    @Override
    public Optional<ExecutionHandle> find(String executionId) {
        return Optional.ofNullable(executions.get(executionId));
    }

    @Override
    public Set<String> runningIds() {
        Set<String> running = new HashSet<>(starting);
        executions.forEach((id, execution) -> {
            if (!execution.finished()) {
                running.add(id);
            }
        });
        return Set.copyOf(running);
    }

    /** Makes the execution of {@code request}: the program started, or an execution that says why it was not. */
    private LocalExecution launch(String id, ExecutionRequest request) {
        Instant requestedAt = clock.instant();
        Path directory = request.workingDirectory();
        if (!Files.isDirectory(directory)) {
            return notStarted(id, requestedAt, "The working directory is not a folder: " + directory);
        }
        Path logLocation = logs.resolve(id);
        LogFile stdoutLog = null;
        LogFile stderrLog = null;
        try {
            Files.createDirectories(logs);
        } catch (IOException notMade) {
            // the log folder itself cannot be had (a file or a link that points nowhere is in its place, or it cannot be written): the program is not started, as the execution says
            LOG.warn("The log folder {} could not be made for execution {}.", logs, id, notMade);
            return notStarted(id, requestedAt, "The log files of the execution could not be made in " + logs + ": " + notMade);
        }
        try {
            // a folder that is there is another execution's (this id was used before, and the runner has let go of it): it is not taken, and not removed with a failed start
            Files.createDirectory(logLocation);
        } catch (FileAlreadyExistsException used) {
            throw new IllegalArgumentException("The log folder of an execution with this id is there already: " + logLocation, used);
        } catch (IOException notMade) {
            LOG.warn("The log folder of execution {} could not be made in {}.", id, logLocation, notMade);
            return notStarted(id, requestedAt, "The log files of the execution could not be made in " + logLocation + ": " + notMade);
        }
        try {
            stdoutLog = LogFile.create(logLocation.resolve(ExecutionSnapshot.STDOUT_LOG), request.logLimitBytes());
            stderrLog = LogFile.create(logLocation.resolve(ExecutionSnapshot.STDERR_LOG), request.logLimitBytes());
        } catch (IOException notMade) {
            LOG.warn("The log files of execution {} could not be made in {}.", id, logLocation, notMade);
            discard(logLocation, stdoutLog, stderrLog);
            return notStarted(id, requestedAt, "The log files of the execution could not be made in " + logLocation + ": " + notMade);
        }
        Process process;
        try {
            process = spawn(request);
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException notStarted) {
            LOG.info("The program of execution {} could not be started: {}", id, request.argv().getFirst(), notStarted);
            discard(logLocation, stdoutLog, stderrLog);
            return notStarted(id, requestedAt, "The program could not be started: " + notStarted);
        } catch (RuntimeException unexpected) {
            discard(logLocation, stdoutLog, stderrLog);
            throw unexpected;
        }
        try {
            closeInput(process);
            ProcessTree tree = ProcessTree.of(process);
            Optional<Instant> processStart = tree.startedAt();
            ProcessIdentity identity = processStart.isPresent() ? new ProcessIdentity(process.pid(), processStart.get()) : ProcessIdentity.withoutStartTime(process.pid());
            return new LocalExecution(id, requestedAt, clock, request.timeout(), process, tree, new ProcessOutput(process, DRAIN_GRACE, stdoutLog, stderrLog), identity, logLocation,
                    this::ended, stopDeadline);
        } catch (RuntimeException unexpected) {
            // a program that started must not be left running when what watches it could not be made
            process.destroyForcibly();
            discard(logLocation, stdoutLog, stderrLog);
            throw unexpected;
        }
    }

    private Process spawn(ExecutionRequest request) throws IOException {
        List<String> command = new ArrayList<>(request.argv());
        command.set(0, locator.locate(command.getFirst(), request.workingDirectory()));
        ProcessBuilder builder = new ProcessBuilder(command).directory(request.workingDirectory().toFile());
        EnvironmentSpec environment = request.environment();
        // read through the map of the builder, which matches names the way the operating system does
        Map<String, String> kept = new LinkedHashMap<>();
        for (String name : environment.inheritedNames()) {
            String value = builder.environment().get(name);
            if (value != null) {
                kept.put(name, value);
            }
        }
        builder.environment().clear();
        builder.environment().putAll(kept);
        builder.environment().putAll(environment.variables());
        return builder.start();
    }

    private LocalExecution notStarted(String id, Instant requestedAt, String failure) {
        return new LocalExecution(id, requestedAt, clock, failure);
    }

    /** Closes what was made for an execution that did not start, and removes it: no folder of logs stays for a program that never ran. */
    private static void discard(Path logLocation, LogFile stdoutLog, LogFile stderrLog) {
        Stream.of(stdoutLog, stderrLog).filter(file -> file != null).forEach(LogFile::close);
        try {
            Files.deleteIfExists(logLocation.resolve(ExecutionSnapshot.STDOUT_LOG));
            Files.deleteIfExists(logLocation.resolve(ExecutionSnapshot.STDERR_LOG));
            Files.deleteIfExists(logLocation);
        } catch (IOException notRemoved) {
            LOG.warn("The log folder {} of an execution that did not start could not be removed.", logLocation, notRemoved);
        }
    }

    /** The child reads end of input at once: a program that waits for input would otherwise wait for its limit. */
    private static void closeInput(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException alreadyClosed) {
            LOG.debug("The input of the child was already closed.", alreadyClosed);
        }
    }

    /** Told by an execution when it has ended: frees its place, and lets go of the oldest finished executions beyond the number kept. */
    private void ended(LocalExecution execution) {
        permits.release();
        remember(execution);
    }

    private void remember(LocalExecution execution) {
        executions.put(execution.id(), execution);
        synchronized (finishedOrder) {
            finishedOrder.addLast(execution.id());
            while (finishedOrder.size() > retainedFinished) {
                executions.remove(finishedOrder.removeFirst());
            }
        }
    }

}
