// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static io.github.jaeseongs95.sennard.personal.RunnerFixture.JAVA;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.JAVA_HEAP;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.alive;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.awaitCondition;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.awaitFile;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.awaitPid;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.java;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.program;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.sennard.app.execution.ExecutionKind;
import io.github.jaeseongs95.sennard.app.execution.ExecutionLogCandidates;
import io.github.jaeseongs95.sennard.app.execution.ExecutionRecord;
import io.github.jaeseongs95.sennard.app.execution.ExecutionRecords;
import io.github.jaeseongs95.sennard.app.execution.ExecutionRecords.NewExecution;
import io.github.jaeseongs95.sennard.app.execution.ExecutionRepository;
import io.github.jaeseongs95.sennard.app.execution.ExecutionStatus;
import io.github.jaeseongs95.sennard.app.execution.LogCompleteness;
import io.github.jaeseongs95.sennard.personal.PersonalProperties;
import io.github.jaeseongs95.sennard.personal.ReleasedFolder;
import io.github.jaeseongs95.sennard.personal.RunnerFixture;
import io.github.jaeseongs95.sennard.personal.StoreStartTask;
import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.execution.ExecutionHandle;
import io.github.jaeseongs95.sennard.server.execution.ExecutionIds;
import io.github.jaeseongs95.sennard.server.execution.ExecutionLogRetention;
import io.github.jaeseongs95.sennard.server.execution.ExecutionLogStore;
import io.github.jaeseongs95.sennard.server.execution.ExecutionOrigin;
import io.github.jaeseongs95.sennard.server.execution.ExecutionRecovery;
import io.github.jaeseongs95.sennard.server.execution.ExecutionRequest;
import io.github.jaeseongs95.sennard.server.execution.ExecutionSnapshot;
import io.github.jaeseongs95.sennard.server.execution.ExecutionState;
import io.github.jaeseongs95.sennard.server.execution.ExecutionTracker;
import io.github.jaeseongs95.sennard.server.execution.ExecutionsBeingRecorded;
import io.github.jaeseongs95.sennard.server.execution.OrphanProcesses;
import io.github.jaeseongs95.sennard.server.execution.ProcessRunner;
import io.github.jaeseongs95.sennard.server.execution.Refused;
import io.github.jaeseongs95.sennard.server.execution.StopDeadline;
import io.github.jaeseongs95.sennard.server.execution.StreamOutput;
import io.github.jaeseongs95.sennard.server.workflow.HostIdentity;
import tools.jackson.databind.json.JsonMapper;

/**
 * The executions of the assembled server on the real database (PostgreSQL) with the real runner and real programs: the record of an execution is written when the program is started
 * and closed when it ends, a program that is not started has its record closed at once, an execution the runner refuses has none, and a restarted server accounts for the records
 * that were still running (the same use the start of the server makes: the {@code StoreStartTask} {@code executionRecovery}).
 *
 * <pre>
 * t1  an exit               the record has the run, kind, session, argv, working folder, process id and start time, status EXITED with the exit code, the end time, the log folder and the bytes
 *                           its files took; it is closed when the caller's wait answers (nothing is waited for)
 * t2  not started           the record is closed at once as NOT_STARTED, with no process
 * t3  running and a cancel  the wait ends with RUNNING and the record is RUNNING with the process; a cancel closes the record as KILLED
 * t4  a refusal             an execution refused for the limit has no record, the ones that run do
 * t5  a restart             a record whose same process is alive: the process is killed and the record is KILLED; whose process is gone, whose id has another start time, or that has no
 *                           process: LOST (with no end time: when it ended is not known), and the process of another start time is not touched; the start of the server runs this
 * t6  another installation  a RUNNING record of another server installation (host id) is not accounted for and its live process is not touched
 * t7  a survivor            a record whose process the finder reports as still alive after the kill stays RUNNING and is looked at again (the finder's answer is given; a real process that
 *                           cannot be killed cannot be made on demand)
 * t8  a transaction         a caller inside a transaction is refused before the runner is asked to start anything, and nothing is recorded; outside one the same call records
 * t8b no record             a record that cannot be written for another reason (a value the table cannot hold) ends the program that was started and records nothing
 * t9  the stop's order      the runner ends the programs, then the stop of the tracker waits for the closing of their records: a stop with nothing to wait for returns at once, and one with a
 *                           closing that is held up is seen waiting inside the stop, the record RUNNING, until the closing is let go; then the record is closed when it returns
 * t10 the end's order       what the caller is told of the end (its future, its wait, its snapshot) comes after the record is closed: while the closing is held up the execution is not yet
 *                           seen as ended and the record is RUNNING; when it is let go the record is closed at the moment the end is answered
 * t11 the kept log          the log files of the execution that has just ended are not deleted by the pass its end started, though the capacity is passed; the next pass deletes them
 * t12 a start and the stop  a stop that comes while a start is between the write of its record and its place in the list of closings waits for that closing (the stop is seen waiting while the
 *                           start is held there, and the record is closed when the stop returns); the order is the test's, by latches
 * t13 after the stop        a start that comes after the stop is refused (Refused STOPPING) and starts nothing
 * t14 being recorded        a program that ended before its record was written (the write is held up) has a folder that the runner does not name and no record names: a pass of the
 *                           retention does not delete it, and the record is written and closed after
 * t15 the stop's limit      a stop that finds a start held up (its record not written) waits for it at most the limit, logs that at ERROR and returns; a start that comes after is refused
 * t16 the pass is after     the pass of the retention that an end starts is handed over after the record is closed and is not part of the closing: while it is long the stop returns, and
 *                           no record is reported as not closed
 * t17 no pass at the stop   an execution that ends after the stop began starts no pass of the retention (the execution is a scripted one that the test ends on a thread of its own, which
 *                           has done all that follows the closing when it is joined: there is no pause to wait out)
 * t18 protected first       the tracker makes the id and protects it before the runner is asked to start; a program that ended at once and whose folder is there (scripted runner) keeps its
 *                           folder through a pass of the retention that comes inside {@code start} and one that comes right after it answers, and the same pass deletes it once its record is
 *                           written (the control), and so does a pass that comes just before the write of the record (the INSERT: a real write of the real records, run after a step
 *                           of the test). Putting the protection after the start, or ending it before the write of the record, fails it
 * t19 a refusal             an execution the runner refuses (Refused) or rejects (IllegalArgumentException) is not protected afterwards, has no record and its id is its own; a later start is taken
 * t20 one deadline          the runner and the tracker stop within one deadline (the real runner ends a program that does not end by itself, the closing of its record is held up): the
 *                           stop of the tracker finds nothing left of the deadline and returns at once with the record RUNNING and the ERROR in the log; the recovery of the next start
 *                           then closes the record (LOST: its process is gone)
 *
 * That a stop "has not returned yet" (t9, t12) is seen as an event, not by a pause: the stop's thread is parked in the tracker's wait for the starts or for the closings
 * ({@link Stopping}).
 * </pre>
 */
@Tag("business-db")
class ExecutionTrackingServerTest {
    private static final Duration LONG = Duration.ofMinutes(2);
    private static final String SESSION = "as1_EXECUTIONTESTSESSION0000";
    private static final String ENDS = """
            public class Ends {
                public static void main(String[] args) {
                    System.out.print("out-text");
                    System.exit(Integer.parseInt(args[0]));
                }
            }
            """;
    private static final String WAITER = """
            public class Waiter {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("started.txt"), "started");
                    Thread.sleep(600_000);
                }
            }
            """;
    private static final String SLEEPER = """
            public class Sleeper {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
                    Thread.sleep(600_000);
                }
            }
            """;
    /** Waits for the file go.txt and then ends with exit code 3: the test decides when. */
    private static final String GATE = """
            public class Gate {
                public static void main(String[] args) throws Exception {
                    while (!java.nio.file.Files.exists(java.nio.file.Path.of("go.txt"))) {
                        Thread.sleep(20);
                    }
                    System.exit(3);
                }
            }
            """;
    /** Writes 500 bytes and ends. */
    private static final String BIG = """
            public class Big {
                public static void main(String[] args) {
                    System.out.print("x".repeat(500));
                }
            }
            """;

    private static RunningServer server;

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources());
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static ExecutionRecords records() {
        return server.bean(ExecutionRecords.class);
    }

    private static ExecutionOrigin origin(String run) {
        return new ExecutionOrigin(run, ExecutionKind.OPERATION, SESSION);
    }

    private static String run() {
        return "run-" + UUID.randomUUID();
    }

    /** The record, once it is closed (the close is made by the thread that ended the execution, which may be a moment after a cancel through the runner's own handle returned). */
    private static ExecutionRecord closed(String id) throws InterruptedException {
        return SessionServerSupport.await(() -> records().find(id).filter(record -> record.status().finished()), "the record of " + id + " to be closed");
    }

    private static void sameInstant(Instant expected, Instant actual) {
        assertNotNull(actual);
        assertTrue(Duration.between(expected, actual).abs().toMillis() <= 1, expected + " and " + actual + " are the same moment to the millisecond");
    }

    /** A tracker over {@code runner} and {@code records} whose retention has the server's settings. */
    private static ExecutionTracker tracker(ProcessRunner runner, ExecutionRecords records) {
        return tracker(runner, records, server.bean(SennardProperties.class));
    }

    private static ExecutionTracker tracker(ProcessRunner runner, ExecutionRecords records, SennardProperties settings) {
        return tracker(runner, records, settings, new ExecutionsBeingRecorded(), Duration.ofSeconds(30), command -> Thread.ofVirtual().start(command));
    }

    /** A tracker whose retention passes are handed to {@code cleanups}, whose stop waits at most {@code closeWait}, and that tells {@code recording} what it is writing records of. */
    private static ExecutionTracker tracker(ProcessRunner runner, ExecutionRecords records, SennardProperties settings, ExecutionsBeingRecorded recording, Duration closeWait,
            Executor cleanups) {
        return tracker(runner, records, settings, recording, new StopDeadline(closeWait), cleanups);
    }

    /** As above, whose stop is within {@code stopDeadline}: a test that stops a runner too shares the one deadline between the two. */
    private static ExecutionTracker tracker(ProcessRunner runner, ExecutionRecords records, SennardProperties settings, ExecutionsBeingRecorded recording, StopDeadline stopDeadline,
            Executor cleanups) {
        ExecutionLogStore store = server.bean(ExecutionLogStore.class);
        HostIdentity host = server.bean(HostIdentity.class);
        Clock clock = server.bean(Clock.class);
        return new ExecutionTracker(runner, records, host, store, new ExecutionLogRetention(records, store, host, runner, recording, settings, clock), recording, stopDeadline, cleanups);
    }

    /** Holds the passes of the retention until the test runs them. */
    private static final class Queued implements Executor {
        final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        /** The pass that an end started (it is waited for: it is handed over after the end is answered). */
        Runnable next() throws InterruptedException {
            Runnable task = tasks.poll(20, TimeUnit.SECONDS);
            assertNotNull(task, "a pass of the retention was handed over");
            return task;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------ a scripted runner

    /**
     * A runner that starts no program. It makes the folder of the log files of an execution as the real one does (named by the id it is given, in the log folder of the state folder) and
     * answers an execution that the test ends. It names no running id (an execution that has ended is not one), so what keeps the folder of an execution that has ended from the
     * retention is the tracker's own protection and nothing of the runner's. The steps of the test are run at the two places where a caller of a runner is open to a pass of the retention:
     * inside {@code start}, after the folder is made and before the answer, and at the first thing the caller does with the answer.
     */
    private static final class ScriptedRunner implements ProcessRunner {
        private final Path logs = server.bean(PersonalProperties.class).stateDir().resolve("executions");
        private final ExecutionsBeingRecorded recording;
        private final Map<String, ScriptedExecution> byId = new java.util.concurrent.ConcurrentHashMap<>();
        final List<ScriptedExecution> started = new CopyOnWriteArrayList<>();
        /** The ids the tracker gave, in the order of the calls (also of the calls that were refused). */
        final List<String> ids = new CopyOnWriteArrayList<>();
        /** Whether the id was already being recorded (protected) when {@code start} was asked, for each call. */
        final List<Boolean> protectedWhenAsked = new CopyOnWriteArrayList<>();
        /** Run inside {@code start} with the id, after the folder was made and before it answers. */
        Consumer<String> insideStart = id -> {
        };
        /** Run with the id at the first call the caller makes on the execution that {@code start} answered. */
        Consumer<String> afterStart = id -> {
        };
        /** Thrown by {@code start} before anything is made, when set. */
        RuntimeException refusal;
        /** Whether the execution that is answered has ended already: a program that ended at once. */
        boolean endsAtOnce = true;

        /** @param recording what the tracker under test tells which executions it is writing the records of */
        ScriptedRunner(ExecutionsBeingRecorded recording) {
            this.recording = recording;
        }

        Path folderOf(String id) {
            return logs.resolve(id);
        }

        @Override
        public ExecutionHandle start(String id, ExecutionRequest request) {
            ids.add(id);
            protectedWhenAsked.add(recording.ids().contains(id));
            if (refusal != null) {
                throw refusal;
            }
            assertTrue(ExecutionIds.isExecutionId(id), "the tracker gives an id written as an execution id: " + id);
            Path folder = folderOf(id);
            try {
                Files.createDirectories(folder);
                Files.write(folder.resolve("stdout.log"), new byte[500]);
            } catch (IOException notMade) {
                throw new UncheckedIOException(notMade);
            }
            insideStart.accept(id);
            ScriptedExecution execution = new ScriptedExecution(id, folder, endsAtOnce, afterStart);
            byId.put(id, execution);
            started.add(execution);
            return execution;
        }

        @Override
        public Optional<ExecutionHandle> find(String executionId) {
            return Optional.ofNullable(byId.get(executionId));
        }

        @Override
        public Set<String> runningIds() {
            return Set.of();
        }
    }

    /** The execution a {@link ScriptedRunner} answers: it ends when the test says so, or has ended already. */
    private static final class ScriptedExecution implements ExecutionHandle {
        private final String id;
        private final Path folder;
        private final Instant requestedAt = Instant.now();
        private final CompletableFuture<ExecutionSnapshot> ended = new CompletableFuture<>();
        private final Consumer<String> afterStart;
        private final AtomicBoolean touched = new AtomicBoolean();
        private final AtomicBoolean ending = new AtomicBoolean();
        private volatile ExecutionSnapshot current;

        ScriptedExecution(String id, Path folder, boolean endsAtOnce, Consumer<String> afterStart) {
            this.id = id;
            this.folder = folder;
            this.afterStart = afterStart;
            this.current = snapshotOf(ExecutionState.RUNNING, null);
            if (endsAtOnce) {
                exit(0);
            }
        }

        private ExecutionSnapshot snapshotOf(ExecutionState state, Integer exitCode) {
            return new ExecutionSnapshot(id, state, exitCode, requestedAt, state.finished() ? Instant.now() : null, null, folder.toString(), StreamOutput.EMPTY, StreamOutput.EMPTY, null);
        }

        /** The first thing the caller does with the execution that was answered runs the step of the test. */
        private void touched() {
            if (touched.compareAndSet(false, true)) {
                afterStart.accept(id);
            }
        }

        /** The program ends with this exit code; what waits on the end (the tracker's closing of the record) runs on the calling thread. */
        void exit(int code) {
            end(ExecutionState.EXITED, code);
        }

        private void end(ExecutionState state, Integer exitCode) {
            if (ending.compareAndSet(false, true)) {
                current = snapshotOf(state, exitCode);
                ended.complete(current);
            }
        }

        @Override
        public String id() {
            touched();
            return id;
        }

        @Override
        public ExecutionSnapshot snapshot() {
            touched();
            return current;
        }

        @Override
        public ExecutionSnapshot await(Duration wait) throws InterruptedException {
            touched();
            try {
                return ended.get(wait.toNanos(), TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.TimeoutException notYet) {
                return current;
            } catch (java.util.concurrent.ExecutionException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        @Override
        public CompletableFuture<ExecutionSnapshot> ended() {
            touched();
            return ended;
        }

        @Override
        public ExecutionSnapshot cancel() {
            touched();
            end(ExecutionState.KILLED, null);
            return current;
        }
    }

    // ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- t1, t2

    @Test
    void theRecordOfAProgramThatEndsHasWhatItWasAndHowItEndedAndIsClosedWhenTheCallersWaitAnswers(@TempDir Path work) throws Exception {
        program(work, "Ends", ENDS);
        String run = run();
        ExecutionRequest request = java(work, LONG, "Ends", "5");

        ExecutionSnapshot done = server.bean(ExecutionTracker.class).start(origin(run), request, LONG);

        assertEquals(ExecutionState.EXITED, done.state());
        ExecutionRecord record = records().find(done.id()).orElseThrow();
        assertEquals(ExecutionStatus.EXITED, record.status(), "the end was answered after the record was closed: nothing is waited for here");
        assertEquals(run, record.runId());
        assertEquals(ExecutionKind.OPERATION, record.kind());
        assertEquals(SESSION, record.requestedBy());
        assertEquals(request.argv(), records().argvOf(record));
        assertEquals(work.toString(), record.cwd());
        assertEquals(5, record.exitCode());
        assertEquals(done.process().pid(), record.pid());
        sameInstant(done.process().startedAt(), record.processStartedAt());
        sameInstant(done.requestedAt(), record.startedAt());
        sameInstant(done.endedAt(), record.endedAt());
        assertEquals(done.logLocation(), record.logLocation());
        assertEquals(LogCompleteness.COMPLETE, record.logCompleteness());
        assertEquals(8L, record.logBytes(), "the 8 bytes of out-text in stdout.log and nothing in stderr.log");
        assertEquals(Files.size(Path.of(done.logLocation()).resolve("stdout.log")) + Files.size(Path.of(done.logLocation()).resolve("stderr.log")), record.logBytes());
        assertNull(record.logRemovedAt());
        assertNull(record.logUnreachableAt());
        assertEquals(List.of(record), records().ofRun(run));
    }

    @Test
    void aProgramThatCannotBeStartedHasItsRecordClosedAtOnceWithNoProcess(@TempDir Path work) throws Exception {
        String run = run();

        ExecutionSnapshot none = server.bean(ExecutionTracker.class).start(origin(run), request(List.of(work.resolve("no-such-program").toString()), work, LONG), LONG);

        assertEquals(ExecutionState.NOT_STARTED, none.state());
        ExecutionRecord record = records().find(none.id()).orElseThrow();
        assertEquals(ExecutionStatus.NOT_STARTED, record.status());
        assertNull(record.pid());
        assertNull(record.processStartedAt());
        assertNull(record.logLocation());
        assertNull(record.logBytes());
        assertNotNull(record.endedAt());
        assertNull(record.exitCode());
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------- t3, t4

    @Test
    void aRunningProgramHasARunningRecordWithItsProcessAndACancelClosesItAsKilled(@TempDir Path work) throws Exception {
        program(work, "Waiter", WAITER);
        String run = run();

        ExecutionSnapshot running = server.bean(ExecutionTracker.class).start(origin(run), java(work, LONG, "Waiter"), Duration.ofMillis(100));

        assertEquals(ExecutionState.RUNNING, running.state());
        ExecutionRecord record = records().find(running.id()).orElseThrow();
        assertEquals(ExecutionStatus.RUNNING, record.status(), "written when the program was started");
        assertEquals(running.process().pid(), record.pid());
        assertNull(record.endedAt());
        assertNull(record.logBytes(), "its files are still being written: no size yet");
        assertTrue(records().running(hostId()).stream().anyMatch(each -> each.id().equals(running.id())));

        awaitFile(work.resolve("started.txt"));
        ExecutionSnapshot killed = server.bean(ProcessRunner.class).find(running.id()).orElseThrow().cancel();

        assertEquals(ExecutionState.KILLED, killed.state());
        ExecutionRecord after = closed(running.id());
        assertEquals(ExecutionStatus.KILLED, after.status());
        assertNull(after.exitCode());
        assertNotNull(after.logBytes());
        assertFalse(alive(running.process().pid()));
        ReleasedFolder.await(work);
    }

    @Test
    void anExecutionTheRunnerRefusesHasNoRecordAndTheOnesThatRunDo(@TempDir Path work) throws Exception {
        program(work, "Waiter", WAITER);
        ExecutionTracker tracker = server.bean(ExecutionTracker.class);
        ProcessRunner runner = server.bean(ProcessRunner.class);
        String run = run();
        List<String> ids = new ArrayList<>();
        int limit = server.bean(SennardProperties.class).run().maxConcurrent();
        try {
            for (int taken = 0; taken < limit; taken++) {
                ids.add(tracker.start(origin(run), java(work, LONG, "Waiter"), Duration.ofMillis(50)).id());
            }

            Refused refused = assertThrows(Refused.class, () -> tracker.start(origin(run), java(work, LONG, "Waiter"), Duration.ofMillis(50)));

            assertEquals(Refused.Reason.TOO_MANY_RUNS, refused.reason());
            assertEquals(limit, records().ofRun(run).size(), "one record for each execution that was taken, none for the refused one");
        } finally {
            for (String id : ids) {
                runner.find(id).ifPresent(handle -> handle.cancel());
            }
        }
        for (String id : ids) {
            assertEquals(ExecutionStatus.KILLED, closed(id).status());
        }
        ReleasedFolder.await(work);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- t5

    private static Process startSleeper(Path work, String pidFile) throws IOException {
        return new ProcessBuilder(JAVA, JAVA_HEAP, "-cp", ".", "Sleeper", pidFile).directory(work.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static String hostId() {
        return server.bean(HostIdentity.class).id();
    }

    private static void recorded(String id, Long pid, Instant processStartedAt) {
        recordedFor(hostId(), id, pid, processStartedAt);
    }

    private static void recordedFor(String host, String id, Long pid, Instant processStartedAt) {
        records().begin(new NewExecution(id, "run-recovery", ExecutionKind.OPERATION, SESSION, host, List.of("java", "-version"), "/work", ExecutionStatus.RUNNING, null, Instant.now(), null,
                null, LogCompleteness.COMPLETE, null, pid, processStartedAt));
    }

    private static ExecutionRecovery recovery(OrphanProcesses orphans) {
        return new ExecutionRecovery(records(), orphans, server.bean(HostIdentity.class), server.bean(Clock.class), server.bean(ExecutionLogStore.class));
    }

    @Test
    void aRestartAccountsForTheRecordsThatWereStillRunning(@TempDir Path work) throws Exception {
        program(work, "Sleeper", SLEEPER);
        Process same = startSleeper(work, "same.pid");
        Process other = startSleeper(work, "other.pid");
        Process gone = startSleeper(work, "gone.pid");
        try {
            awaitPid(work.resolve("same.pid"));
            awaitPid(work.resolve("other.pid"));
            awaitPid(work.resolve("gone.pid"));
            Instant sameStart = same.toHandle().info().startInstant().orElseThrow();
            Instant otherStart = other.toHandle().info().startInstant().orElseThrow();
            Instant goneStart = gone.toHandle().info().startInstant().orElseThrow();
            gone.destroyForcibly();
            gone.waitFor(60, TimeUnit.SECONDS);
            String aliveId = UUID.randomUUID().toString();
            String goneId = UUID.randomUUID().toString();
            String mismatchId = UUID.randomUUID().toString();
            String noProcessId = UUID.randomUUID().toString();
            recorded(aliveId, same.pid(), sameStart);
            recorded(goneId, gone.pid(), goneStart);
            recorded(mismatchId, other.pid(), otherStart.minusSeconds(3600));
            recorded(noProcessId, null, null);

            ExecutionRecovery.Result result = server.bean(ExecutionRecovery.class).recover();

            assertEquals(1, result.killed());
            assertEquals(3, result.lost());
            assertEquals(0, result.stillRunning());
            assertEquals(ExecutionStatus.KILLED, records().find(aliveId).orElseThrow().status(), "the same process was alive: it is killed");
            assertTrue(same.waitFor(60, TimeUnit.SECONDS), "the process of the record is gone");
            for (String lost : List.of(goneId, mismatchId, noProcessId)) {
                ExecutionRecord record = records().find(lost).orElseThrow();
                assertEquals(ExecutionStatus.LOST, record.status(), "the result is not known and is not made up");
                assertNull(record.exitCode());
                assertNull(record.endedAt(), "when it ended is not known, and is not made up");
                assertEquals(LogCompleteness.TRUNCATED, record.logCompleteness());
            }
            assertTrue(other.isAlive(), "the process that has the id of a record but another start time is another process and was not touched");
            assertEquals(Optional.empty(), records().running(hostId()).stream().filter(record -> record.runId().equals("run-recovery")).findAny());
            assertEquals(new ExecutionRecovery.Result(0, 0, 0), server.bean(ExecutionRecovery.class).recover(), "a second recovery finds nothing running");
        } finally {
            same.destroyForcibly();
            other.destroyForcibly();
            gone.destroyForcibly();
            other.waitFor(60, TimeUnit.SECONDS);
        }
        ReleasedFolder.await(work);
    }

    @Test
    void aRestartDoesNotTouchTheRecordsOfAnotherServerInstallationThatShareTheDatabase(@TempDir Path work) throws Exception {
        program(work, "Sleeper", SLEEPER);
        Process theirs = startSleeper(work, "theirs.pid");
        try {
            awaitPid(work.resolve("theirs.pid"));
            String theirRecord = UUID.randomUUID().toString();
            String ownRecord = UUID.randomUUID().toString();
            // a record of another installation whose process id and start time are those of a live process: were it taken for an orphan of this server, the process would be killed
            recordedFor(UUID.randomUUID().toString(), theirRecord, theirs.pid(), theirs.toHandle().info().startInstant().orElseThrow());
            recorded(ownRecord, null, null);

            ExecutionRecovery.Result result = server.bean(ExecutionRecovery.class).recover();

            assertEquals(new ExecutionRecovery.Result(0, 1, 0), result, "only the record of this installation was accounted for");
            assertEquals(ExecutionStatus.LOST, records().find(ownRecord).orElseThrow().status());
            assertEquals(ExecutionStatus.RUNNING, records().find(theirRecord).orElseThrow().status(), "the record of another installation is still running");
            assertTrue(theirs.isAlive(), "and its process was not touched");
            assertTrue(records().running(hostId()).stream().noneMatch(record -> record.id().equals(theirRecord)));
            records().finish(theirRecord, ExecutionStatus.KILLED, null, Instant.now(), LogCompleteness.COMPLETE, null);
        } finally {
            theirs.destroyForcibly();
            theirs.waitFor(60, TimeUnit.SECONDS);
        }
        ReleasedFolder.await(work);
    }

    @Test
    void aRecordWhoseProcessSurvivedTheKillStaysRunningAndTheNextStartLooksAgain() {
        String id = UUID.randomUUID().toString();
        recorded(id, 4242L, Instant.parse("2026-01-01T00:00:00Z"));
        try {
            // the real finder cannot be made to fail to kill a process on demand: the port is answered "survived", and what the recovery does with that answer is what is tested
            ExecutionRecovery stubborn = recovery(process -> OrphanProcesses.Outcome.SURVIVED);

            assertEquals(new ExecutionRecovery.Result(0, 0, 1), stubborn.recover());
            assertEquals(ExecutionStatus.RUNNING, records().find(id).orElseThrow().status(), "it is not closed as ended: the process is alive");
            assertEquals(new ExecutionRecovery.Result(0, 0, 1), stubborn.recover(), "and it is looked at again at the next start");
        } finally {
            records().finish(id, ExecutionStatus.LOST, null, null, LogCompleteness.TRUNCATED, null);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------------------------------------------------- t8, t8b

    /** The real runner, and the handles it answered: what the program became is read from them. */
    private static final class RecordingRunner implements ProcessRunner {
        private final ProcessRunner real;
        private final List<ExecutionHandle> started = new ArrayList<>();

        RecordingRunner(ProcessRunner real) {
            this.real = real;
        }

        @Override
        public ExecutionHandle start(String id, ExecutionRequest request) {
            ExecutionHandle handle = real.start(id, request);
            started.add(handle);
            return handle;
        }

        @Override
        public Optional<ExecutionHandle> find(String executionId) {
            return real.find(executionId);
        }

        @Override
        public Set<String> runningIds() {
            return real.runningIds();
        }
    }

    @Test
    void aCallerInsideATransactionIsRefusedBeforeTheRunnerIsAskedToStartAnythingAndNothingIsRecorded(@TempDir Path work) throws Exception {
        program(work, "Waiter", WAITER);
        String run = run();
        RecordingRunner runner = new RecordingRunner(server.bean(ProcessRunner.class));
        ExecutionTracker tracker = tracker(runner, records());
        TransactionTemplate inTransaction = new TransactionTemplate(server.bean(PlatformTransactionManager.class));

        // the record may not be written from inside a transaction (the thread that notices the end would not find the row): the call is refused as it begins, not after the program ran
        assertThrows(IllegalTransactionStateException.class, () -> inTransaction.executeWithoutResult(status -> tracker.begin(origin(run), java(work, LONG, "Waiter"))));
        assertThrows(IllegalTransactionStateException.class, () -> inTransaction.executeWithoutResult(status -> {
            try {
                tracker.start(origin(run), java(work, LONG, "Waiter"), Duration.ofMillis(10));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));

        assertEquals(0, runner.started.size(), "no program was started: not even one that ended before the refusal");
        assertEquals(List.of(), records().ofRun(run), "and nothing was recorded");

        // outside a transaction the same call starts and records
        ExecutionHandle recorded = tracker.begin(origin(run), java(work, LONG, "Waiter"));
        assertEquals(1, runner.started.size());
        assertEquals(ExecutionStatus.RUNNING, records().find(recorded.id()).orElseThrow().status());
        recorded.cancel();
        assertEquals(ExecutionStatus.KILLED, records().find(recorded.id()).orElseThrow().status(), "the cancel was answered after the record was closed");
        ReleasedFolder.await(work);
    }

    @Test
    void aRecordThatCannotBeWrittenForAnotherReasonEndsTheProgramThatWasStartedAndNothingIsRecorded(@TempDir Path work) throws Exception {
        program(work, "Waiter", WAITER);
        String run = run();
        RecordingRunner runner = new RecordingRunner(server.bean(ProcessRunner.class));
        ExecutionTracker tracker = tracker(runner, records());
        // the column of the session's reference holds 255 characters: the database refuses the row
        ExecutionOrigin tooLong = new ExecutionOrigin(run, ExecutionKind.OPERATION, "x".repeat(300));

        RuntimeException refused = assertThrows(RuntimeException.class, () -> tracker.begin(tooLong, java(work, LONG, "Waiter")));

        assertFalse(refused instanceof IllegalTransactionStateException, "this is not the refusal of a transaction: " + refused);
        assertEquals(1, runner.started.size());
        ExecutionSnapshot ended = runner.started.getFirst().await(LONG);
        assertEquals(ExecutionState.KILLED, ended.state(), "the program that ran without a record was ended");
        assertFalse(alive(ended.process().pid()));
        assertEquals(List.of(), records().ofRun(run), "and nothing was recorded");
        ReleasedFolder.await(work);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------- held records

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException again) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** The real records, whose closing of a record is held up until the test lets it go. */
    private static class HeldEnd extends ExecutionRecords {
        final CountDownLatch closing = new CountDownLatch(1);
        final CountDownLatch letEndGo = new CountDownLatch(1);

        HeldEnd() {
            super(server.bean(ExecutionRepository.class), server.bean(JsonMapper.class), server.bean(ExecutionLogCandidates.class));
        }

        @Override
        public boolean finish(String id, ExecutionStatus status, Integer exitCode, Instant endedAt, LogCompleteness logCompleteness, Long logBytes) {
            closing.countDown();
            awaitUninterruptibly(letEndGo);
            return super.finish(id, status, exitCode, endedAt, logCompleteness, logBytes);
        }
    }

    /** As {@link HeldEnd}, and the write of a record is held up after the row is written, until the test lets it go. */
    private static final class HeldStartAndEnd extends HeldEnd {
        final CountDownLatch written = new CountDownLatch(1);
        final CountDownLatch letStartGo = new CountDownLatch(1);

        @Override
        public void begin(NewExecution execution) {
            super.begin(execution);
            written.countDown();
            awaitUninterruptibly(letStartGo);
        }
    }

    /** What the stop of a tracker waits for, as the stop's own two waits are told apart from outside. */
    private enum Waiting {
        /** The starts that are going on: the stop asks for the lock they hold ({@code ExecutionTracker.closings}). */
        STARTS,
        /** The closings of the records of the executions that ended: the stop joins them ({@code ExecutionTracker.stop}, after it has the list). */
        CLOSINGS
    }

    /**
     * The stop of a tracker on a thread of its own: the latch is counted down when the stop has returned.
     *
     * <p>That the stop "has not returned yet" is not told by a pause of some length after which it is still not back. The stop is seen to be waiting, as an event: its thread is parked
     * (WAITING or TIMED_WAITING) inside the tracker's method that waits for the thing named, which is read from the thread's own stack. What that guarantees: the stop has reached that
     * wait and is blocked in it, so it cannot have returned. What it does not: which lock or future it is parked on is not seen (the tracker's lock and the futures of the closings are
     * its own, not collaborators a test can replace), only the method of the tracker it is parked in.
     */
    private static final class Stopping {
        final CountDownLatch returned = new CountDownLatch(1);
        final Thread thread;

        Stopping(ExecutionTracker tracker) {
            this.thread = Thread.ofPlatform().start(() -> {
                tracker.stop();
                returned.countDown();
            });
        }

        /** Waits (the deadline of the test only) until the stop is parked in the wait for {@code what}, and asserts that it has not returned. */
        void awaitWaitingFor(Waiting what) throws InterruptedException {
            awaitCondition(() -> waitingFor() == what, "the stop to be waiting for " + what);
            assertEquals(1, returned.getCount(), "the stop is blocked in its wait for " + what + ": it has not returned");
        }

        /** The wait the stop is parked in; {@code null} when it is not parked in one of the tracker's (it runs, has not begun, or has returned). The state is read on both sides of the stack. */
        private Waiting waitingFor() {
            if (!parked(thread.getState())) {
                return null;
            }
            Waiting found = null;
            boolean inStop = false;
            for (StackTraceElement frame : thread.getStackTrace()) {
                if (frame.getClassName().equals(ExecutionTracker.class.getName())) {
                    inStop |= frame.getMethodName().equals("stop");
                    if (frame.getMethodName().equals("closings")) {
                        found = Waiting.STARTS;
                    }
                }
            }
            if (found == null && inStop) {
                found = Waiting.CLOSINGS;
            }
            return parked(thread.getState()) ? found : null;
        }

        private static boolean parked(Thread.State state) {
            return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------- t9..t13

    @Test
    void theStopOfTheServerEndsTheProgramsThenWaitsForTheClosingOfTheirRecordsAndAStopWithNothingToWaitForReturnsAtOnce(@TempDir Path work) throws Exception {
        program(work, "Sleeper", SLEEPER);
        String run = run();
        Path state = server.bean(PersonalProperties.class).stateDir();
        HeldEnd held = new HeldEnd();
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            // the control: with nothing to wait for the stop returns, and the way this test sees it (the latch of its return) is how the wait below is told from it
            Stopping idle = new Stopping(tracker(fixture.runner, held));
            assertTrue(idle.returned.await(2, TimeUnit.MINUTES), "a stop with no closing to wait for returns at once");

            ExecutionTracker tracker = tracker(fixture.runner, held);
            tracker.start();
            ExecutionHandle handle = tracker.begin(origin(run), java(work, LONG, "Sleeper", "p.pid"));
            long pid = awaitPid(work.resolve("p.pid"));
            assertTrue(fixture.runner.getPhase() > tracker.getPhase(), "the runner is asked to stop first");

            // what the server's stop does, in its order: the runner ends the programs, then the tracker, which waits for the closings of the ended ones
            fixture.runner.stop();
            assertTrue(held.closing.await(2, TimeUnit.MINUTES));
            assertFalse(alive(pid));
            assertEquals(ExecutionStatus.RUNNING, records().find(handle.id()).orElseThrow().status(), "the closing is going on: the record is not closed yet");
            Stopping stopping = new Stopping(tracker);
            stopping.awaitWaitingFor(Waiting.CLOSINGS); // the stop of the tracker waits: a closing is going on

            held.letEndGo.countDown();
            assertTrue(stopping.returned.await(2, TimeUnit.MINUTES), "and returns once the closing is let go");
            assertEquals(ExecutionStatus.KILLED, records().find(handle.id()).orElseThrow().status(), "closed when the stop of the tracker returned");
            assertFalse(tracker.isRunning());
        }
        ReleasedFolder.await(work);
    }

    @Test
    void theStopOfTheRunnerAndTheStopOfTheTrackerShareOneDeadlineAndARecordNotClosedByThenIsAccountedForByTheNextStart(@TempDir Path work) throws Exception {
        program(work, "Sleeper", SLEEPER);
        String run = run();
        Path state = server.bean(PersonalProperties.class).stateDir();
        HeldEnd held = new HeldEnd();
        // one deadline for both, as the server gives its two components: a program that does not end by itself, and the closing of its record is held up (the database does not answer)
        StopDeadline deadline = new StopDeadline(Duration.ofMillis(300));
        try (RunnerFixture fixture = new RunnerFixture(state, deadline); TrackerErrors errors = new TrackerErrors()) {
            ExecutionTracker tracker = tracker(fixture.runner, held, server.bean(SennardProperties.class), new ExecutionsBeingRecorded(), deadline, new Queued());
            tracker.start();
            ExecutionHandle handle = tracker.begin(origin(run), java(work, LONG, "Sleeper", "p.pid"));
            long pid = awaitPid(work.resolve("p.pid"));

            fixture.runner.stop(); // what the server's stop does first: it fixes the deadline and ends the program
            assertTrue(held.closing.await(2, TimeUnit.MINUTES), "the program ended and the closing of its record is held up");
            assertFalse(alive(pid));
            awaitCondition(() -> deadline.remaining().isZero(), "the deadline of the stop to have passed"); // the runner's part of the stop and the wait for it took the time there was

            // the stop of the tracker finds nothing left of the deadline: it does not wait for the closing (the 30 seconds it waited for before the test would end it)
            Stopping stopping = new Stopping(tracker);
            assertTrue(stopping.returned.await(10, TimeUnit.SECONDS), "the stop returned although the closing is held up: it waited for nothing");
            assertEquals(1, errors.messages().size(), errors.messages().toString());
            assertTrue(errors.messages().getFirst().contains("not closed within the deadline"), errors.messages().getFirst());
            assertEquals(ExecutionStatus.RUNNING, records().find(handle.id()).orElseThrow().status(), "the record was left RUNNING");

            // the start of the server that follows: the recovery finds the record of this installation still RUNNING, and its process gone
            ExecutionRecovery.Result recovered = server.bean(ExecutionRecovery.class).recover();

            assertTrue(recovered.lost() >= 1, "the record was accounted for: " + recovered);
            ExecutionRecord after = records().find(handle.id()).orElseThrow();
            assertEquals(ExecutionStatus.LOST, after.status(), "its process is gone and how it ended is not known");
            held.letEndGo.countDown(); // the closing that was held up goes on: it finds the record closed by another and changes nothing
            assertEquals(ExecutionStatus.LOST, closed(handle.id()).status());
        }
        ReleasedFolder.await(work);
    }

    @Test
    void whatTheCallerIsToldOfTheEndComesAfterTheRecordIsClosed(@TempDir Path work) throws Exception {
        program(work, "Gate", GATE);
        String run = run();
        HeldEnd held = new HeldEnd();
        ExecutionTracker tracker = tracker(server.bean(ProcessRunner.class), held);
        ExecutionHandle handle = tracker.begin(origin(run), java(work, LONG, "Gate"));
        CompletableFuture<ExecutionSnapshot> told = handle.ended();
        try {
            Files.writeString(work.resolve("go.txt"), "go");
            assertTrue(held.closing.await(2, TimeUnit.MINUTES), "the program ended and the closing of its record began");

            assertFalse(told.isDone(), "the end is not answered while the record is being closed");
            assertEquals(ExecutionState.RUNNING, handle.snapshot().state(), "nor shown by a snapshot");
            assertEquals(ExecutionState.RUNNING, handle.await(Duration.ZERO).state(), "nor by a wait that is over");
            assertEquals(ExecutionStatus.RUNNING, records().find(handle.id()).orElseThrow().status());
        } finally {
            held.letEndGo.countDown();
        }

        ExecutionSnapshot ended = told.get(2, TimeUnit.MINUTES);

        assertEquals(ExecutionState.EXITED, ended.state());
        assertEquals(3, ended.exitCode());
        assertEquals(ExecutionStatus.EXITED, records().find(handle.id()).orElseThrow().status(), "the record is closed at the moment the end is answered");
        assertEquals(3, records().find(handle.id()).orElseThrow().exitCode());
        assertEquals(ended, handle.snapshot());
        tracker.stop();
        ReleasedFolder.await(work);
    }

    @Test
    void theLogFilesOfTheExecutionThatHasJustEndedAreKeptByThePassItsEndStartedAndTheNextPassDeletesThem(@TempDir Path work) throws Exception {
        program(work, "Big", BIG);
        SennardProperties small = new Binder(new MapConfigurationPropertySource(Map.of("sennard.run.log-retention", "7d", "sennard.run.log-capacity", "100B"))).bindOrCreate("sennard",
                SennardProperties.class);
        ProcessRunner runner = server.bean(ProcessRunner.class);
        Queued cleanups = new Queued();
        ExecutionTracker tracker = tracker(runner, records(), small, new ExecutionsBeingRecorded(), Duration.ofSeconds(30), cleanups);

        ExecutionSnapshot done = tracker.start(origin(run()), java(work, LONG, "Big"), LONG);
        cleanups.next().run(); // the pass that the end started, run by the test
        tracker.stop();

        Path logFolder = Path.of(done.logLocation());
        assertEquals(500L, Files.size(logFolder.resolve("stdout.log")));
        assertTrue(Files.exists(logFolder.resolve("stdout.log")), "500 bytes with 100 allowed, and the log of the execution that has just ended is still there: the caller was told where it is");
        assertNull(records().find(done.id()).orElseThrow().logRemovedAt());
        assertEquals(500L, records().find(done.id()).orElseThrow().logBytes());

        new ExecutionLogRetention(records(), server.bean(ExecutionLogStore.class), server.bean(HostIdentity.class), runner, new ExecutionsBeingRecorded(), small, server.bean(Clock.class))
                .cleanUp();

        assertFalse(Files.exists(logFolder), "the next pass chooses it");
        assertNotNull(records().find(done.id()).orElseThrow().logRemovedAt());
        assertEquals(LogCompleteness.COMPLETE, records().find(done.id()).orElseThrow().logCompleteness());
    }

    @Test
    void aStopThatComesWhileAStartIsBetweenTheWriteOfItsRecordAndItsPlaceInTheListWaitsForThatClosing(@TempDir Path work) throws Exception {
        program(work, "Sleeper", SLEEPER);
        String run = run();
        Path state = server.bean(PersonalProperties.class).stateDir();
        HeldStartAndEnd held = new HeldStartAndEnd();
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            ExecutionTracker tracker = tracker(fixture.runner, held);
            tracker.start();
            AtomicReference<ExecutionHandle> began = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread beginning = Thread.ofPlatform().start(() -> {
                try {
                    began.set(tracker.begin(origin(run), java(work, LONG, "Sleeper", "p.pid")));
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            // the start is held after its record was written and before the closing of it is in the list the stop takes
            assertTrue(held.written.await(2, TimeUnit.MINUTES));
            long pid = awaitPid(work.resolve("p.pid"));
            assertEquals(1, records().ofRun(run).size(), "the record is written");
            fixture.runner.stop();
            assertFalse(alive(pid));

            Stopping stopping = new Stopping(tracker);
            stopping.awaitWaitingFor(Waiting.STARTS); // the stop does not take the list while the start is between its record and its place in it

            held.letStartGo.countDown();
            assertTrue(held.closing.await(2, TimeUnit.MINUTES), "the start has its place in the list and the program has ended: its record is being closed");
            stopping.awaitWaitingFor(Waiting.CLOSINGS); // now the stop waits for that closing
            assertEquals(ExecutionStatus.RUNNING, records().ofRun(run).getFirst().status());

            held.letEndGo.countDown();
            assertTrue(stopping.returned.await(2, TimeUnit.MINUTES));
            beginning.join();
            assertNull(failure.get());
            assertEquals(ExecutionStatus.KILLED, records().ofRun(run).getFirst().status(), "closed when the stop returned");
            assertEquals(ExecutionState.KILLED, began.get().ended().get(2, TimeUnit.MINUTES).state());
        }
        ReleasedFolder.await(work);
    }

    @Test
    void aStartThatComesAfterTheStopIsRefusedAndStartsNothing(@TempDir Path work) throws Exception {
        program(work, "Waiter", WAITER);
        String run = run();
        RecordingRunner runner = new RecordingRunner(server.bean(ProcessRunner.class));
        ExecutionTracker tracker = tracker(runner, records());
        tracker.stop();

        Refused refused = assertThrows(Refused.class, () -> tracker.begin(origin(run), java(work, LONG, "Waiter")));

        assertEquals(Refused.Reason.STOPPING, refused.reason());
        assertEquals(0, runner.started.size(), "nothing was started");
        assertEquals(List.of(), records().ofRun(run));

        tracker.start(); // the server's life goes on again: a start is taken
        ExecutionHandle again = tracker.begin(origin(run), java(work, LONG, "Waiter"));
        assertEquals(1, runner.started.size());
        again.cancel();
        assertEquals(ExecutionStatus.KILLED, records().find(again.id()).orElseThrow().status());
        ReleasedFolder.await(work);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------- t14..t17

    /** The real records, whose write of a record is held up before anything is written, until the test lets it go. */
    private static final class HeldBegin extends ExecutionRecords {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch letGo = new CountDownLatch(1);

        HeldBegin() {
            super(server.bean(ExecutionRepository.class), server.bean(JsonMapper.class), server.bean(ExecutionLogCandidates.class));
        }

        @Override
        public void begin(NewExecution execution) {
            entered.countDown();
            awaitUninterruptibly(letGo);
            super.begin(execution);
        }
    }

    /** The real records, whose write of a record runs a step of the test first: the last thing the tracker does before the record is written (the INSERT). */
    private static final class ObservedBegin extends ExecutionRecords {
        Consumer<String> beforeInsert = id -> {
        };

        ObservedBegin() {
            super(server.bean(ExecutionRepository.class), server.bean(JsonMapper.class), server.bean(ExecutionLogCandidates.class));
        }

        @Override
        public void begin(NewExecution execution) {
            beforeInsert.accept(execution.id());
            super.begin(execution);
        }
    }

    /** What the tracker logs at ERROR while the test runs. */
    private static final class TrackerErrors implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ExecutionTracker.class);
        private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

        TrackerErrors() {
            logged.start();
            logger.addAppender(logged);
        }

        List<String> messages() {
            return logged.list.stream().filter(event -> event.getLevel() == Level.ERROR).map(ILoggingEvent::getFormattedMessage).toList();
        }

        @Override
        public void close() {
            logger.detachAppender(logged);
            logged.stop();
        }
    }

    @Test
    void anExecutionThatEndsBeforeItsRecordIsWrittenIsNotTakenForAFolderNoRecordNamesAndItsFolderIsNotDeleted(@TempDir Path work) throws Exception {
        program(work, "Gate", GATE);
        SennardProperties settings = server.bean(SennardProperties.class);
        RecordingRunner runner = new RecordingRunner(server.bean(ProcessRunner.class));
        HeldBegin held = new HeldBegin();
        ExecutionsBeingRecorded recording = new ExecutionsBeingRecorded();
        ExecutionTracker tracker = tracker(runner, held, settings, recording, Duration.ofSeconds(30), new Queued());
        // a clock far ahead: the folder of every execution that is not protected is past its retention
        Clock far = Clock.fixed(server.bean(Clock.class).instant().plus(Duration.ofDays(3650)), java.time.ZoneOffset.UTC);
        ExecutionLogRetention retention = new ExecutionLogRetention(records(), server.bean(ExecutionLogStore.class), server.bean(HostIdentity.class), runner, recording, settings, far);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread beginning = Thread.ofPlatform().start(() -> {
            try {
                tracker.begin(origin(run()), java(work, LONG, "Gate"));
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        // the program was started and its record is not written: the write is held up
        assertTrue(held.entered.await(2, TimeUnit.MINUTES));
        ExecutionHandle started = runner.started.getFirst();
        try {
            Files.writeString(work.resolve("go.txt"), "go");
            assertEquals(ExecutionState.EXITED, started.ended().get(2, TimeUnit.MINUTES).state(), "the program has ended and its record is not written");
            Path folder = Path.of(started.snapshot().logLocation());
            assertEquals(Optional.empty(), records().find(started.id()));
            assertFalse(runner.runningIds().contains(started.id()), "the runner does not name it: it is not running");

            retention.cleanUp();

            assertTrue(Files.exists(folder), "the folder of an execution whose record is being written is not deleted, though no record names it and it is not running");
        } finally {
            held.letGo.countDown();
        }
        beginning.join();
        assertNull(failure.get());
        assertEquals(ExecutionStatus.EXITED, closed(started.id()).status(), "the record is written and closed after the pass");
        assertEquals(Set.of(), recording.ids(), "and the execution is no longer being recorded");
        tracker.stop();
        ReleasedFolder.await(work);
    }

    @Test
    void aStopThatFindsAStartHeldUpGivesUpWaitingForItAfterTheLimitSaysSoInTheLogAndLaterStartsAreStillRefused(@TempDir Path work) throws Exception {
        program(work, "Waiter", WAITER);
        RecordingRunner runner = new RecordingRunner(server.bean(ProcessRunner.class));
        HeldBegin held = new HeldBegin();
        ExecutionTracker tracker = tracker(runner, held, server.bean(SennardProperties.class), new ExecutionsBeingRecorded(), Duration.ofMillis(200), new Queued());
        AtomicReference<ExecutionHandle> began = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (TrackerErrors errors = new TrackerErrors()) {
            Thread beginning = Thread.ofPlatform().start(() -> {
                try {
                    began.set(tracker.begin(origin(run()), java(work, LONG, "Waiter")));
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            assertTrue(held.entered.await(2, TimeUnit.MINUTES), "the start is held up: it has the lock the stop asks for");
            try {
                Stopping stopping = new Stopping(tracker);

                assertTrue(stopping.returned.await(30, TimeUnit.SECONDS), "the stop does not wait for ever for a start that is held up");
                assertEquals(1, errors.messages().size(), errors.messages().toString());
                assertTrue(errors.messages().getFirst().contains("still going on"), errors.messages().getFirst());
                Refused refused = assertThrows(Refused.class, () -> tracker.begin(origin(run()), java(work, LONG, "Waiter")));
                assertEquals(Refused.Reason.STOPPING, refused.reason(), "a start that comes after the stop began is refused though the stop did not get the lock");
                assertEquals(1, runner.started.size(), "and starts nothing");
            } finally {
                held.letGo.countDown();
            }
            beginning.join();
            assertNull(failure.get());
            began.get().cancel();
            assertEquals(ExecutionStatus.KILLED, closed(began.get().id()).status());
        }
        ReleasedFolder.await(work);
    }

    @Test
    void thePassOfTheRetentionIsAfterTheClosingOfTheRecordAndTheStopDoesNotWaitForItNorLogsThatTheRecordWasNotClosed(@TempDir Path work) throws Exception {
        program(work, "Gate", GATE);
        SennardProperties settings = server.bean(SennardProperties.class);
        ProcessRunner runner = server.bean(ProcessRunner.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutionsBeingRecorded recording = new ExecutionsBeingRecorded();
        ExecutionLogRetention longPass = new ExecutionLogRetention(records(), server.bean(ExecutionLogStore.class), server.bean(HostIdentity.class), runner, recording, settings,
                server.bean(Clock.class)) {
            @Override
            public synchronized Result cleanUp(Set<String> keep) {
                entered.countDown();
                awaitUninterruptibly(release);
                return new Result(0, 0, 0);
            }
        };
        ExecutionTracker tracker = new ExecutionTracker(runner, records(), server.bean(HostIdentity.class), server.bean(ExecutionLogStore.class), longPass, recording,
                new StopDeadline(Duration.ofMillis(200)), command -> Thread.ofVirtual().start(command));
        try (TrackerErrors errors = new TrackerErrors()) {
            ExecutionHandle handle = tracker.begin(origin(run()), java(work, LONG, "Gate"));
            Files.writeString(work.resolve("go.txt"), "go");

            assertTrue(entered.await(2, TimeUnit.MINUTES), "the end started a pass of the retention, and it is long");
            assertEquals(ExecutionStatus.EXITED, records().find(handle.id()).orElseThrow().status(), "the record was closed before the pass began");
            tracker.stop();

            assertEquals(List.of(), errors.messages(), "the closing is done: no record is reported as not closed because a pass is long");
            release.countDown();
        } finally {
            release.countDown();
        }
        ReleasedFolder.await(work);
    }

    @Test
    void noPassIsStartedForAnEndThatComesAfterTheStopBegan(@TempDir Path work) throws Exception {
        HeldEnd held = new HeldEnd();
        Queued cleanups = new Queued();
        ExecutionsBeingRecorded recording = new ExecutionsBeingRecorded();
        ScriptedRunner runner = new ScriptedRunner(recording);
        runner.endsAtOnce = false;
        ExecutionTracker tracker = tracker(runner, held, server.bean(SennardProperties.class), recording, Duration.ofSeconds(30), cleanups);
        tracker.start();
        ExecutionHandle handle = tracker.begin(origin(run()), request(List.of("unused"), work, LONG));
        // the program ends on a thread of the test: the closing of the record, and whatever is handed over after it, run on that thread, so that its end is the end of all of it
        Thread ending = Thread.ofPlatform().start(() -> runner.started.getFirst().exit(0));
        assertTrue(held.closing.await(2, TimeUnit.MINUTES), "the program ended and its record is being closed");
        Stopping stopping = new Stopping(tracker);
        try {
            awaitCondition(() -> !tracker.isRunning(), "the stop to have begun");
        } finally {
            held.letEndGo.countDown();
        }

        assertTrue(stopping.returned.await(2, TimeUnit.MINUTES));
        ending.join();
        assertEquals(ExecutionState.EXITED, handle.ended().get(2, TimeUnit.MINUTES).state());
        assertEquals(List.of(), List.copyOf(cleanups.tasks), "the server is stopping: the end did not start a pass of the retention (the thread that ended it has done everything that follows the closing)");
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ t18, t19

    @Test
    void theFolderOfAProgramThatEndsAtOnceIsProtectedFromBeforeTheRunnerMakesItThroughItsAnswerUntilItsRecordIsWritten(@TempDir Path work) throws Exception {
        SennardProperties settings = server.bean(SennardProperties.class);
        ExecutionsBeingRecorded recording = new ExecutionsBeingRecorded();
        ScriptedRunner runner = new ScriptedRunner(recording);
        ObservedBegin observed = new ObservedBegin();
        ExecutionTracker tracker = tracker(runner, observed, settings, recording, Duration.ofSeconds(30), new Queued());
        // a clock far ahead: the folder of every execution that is not protected is past its retention, and the runner that the retention asks names none (the scripted one's are not running)
        Clock far = Clock.fixed(server.bean(Clock.class).instant().plus(Duration.ofDays(3650)), java.time.ZoneOffset.UTC);
        ExecutionLogRetention retention = new ExecutionLogRetention(records(), server.bean(ExecutionLogStore.class), server.bean(HostIdentity.class), server.bean(ProcessRunner.class), recording,
                settings, far);
        Map<String, Boolean> seen = new LinkedHashMap<>();
        // the folder exists in both places, and the program has ended: a pass of the retention that comes in either finds a folder that is not running and that no record names
        runner.insideStart = id -> {
            seen.put("the id is protected inside start", recording.ids().contains(id));
            retention.cleanUp();
            seen.put("the folder is there after a pass inside start", Files.exists(runner.folderOf(id)));
        };
        runner.afterStart = id -> {
            seen.put("the id is protected right after start answered", recording.ids().contains(id));
            retention.cleanUp();
            seen.put("the folder is there after a pass right after start answered", Files.exists(runner.folderOf(id)));
        };
        // the last point at which the protection has to hold: the write of the record, which is a real one, is about to begin
        observed.beforeInsert = id -> {
            seen.put("the id is protected just before the record is written", recording.ids().contains(id));
            retention.cleanUp();
            seen.put("the folder is there after a pass just before the record is written", Files.exists(runner.folderOf(id)));
        };

        ExecutionHandle handle = tracker.begin(origin(run()), request(List.of("unused"), work, LONG));

        assertEquals(List.of(true, true, true, true, true, true), List.copyOf(seen.values()), seen.toString());
        assertEquals(List.of(true), runner.protectedWhenAsked, "the id was protected when the runner was asked to start");
        assertEquals(Set.of(), recording.ids(), "and it is not protected any longer when the record is written");
        assertEquals(ExecutionStatus.EXITED, records().find(handle.id()).orElseThrow().status());
        // the control: once the record is written the folder is the retention's to judge, and a pass this far ahead deletes it: what kept it above was the protection and nothing else
        retention.cleanUp();
        assertFalse(Files.exists(runner.folderOf(handle.id())), "the same pass deletes it when it is not protected");
        tracker.stop();
    }

    @Test
    void anExecutionTheRunnerRefusesOrRejectsIsNotProtectedAfterwardsAndLeavesNoRecordAndALaterStartIsTaken(@TempDir Path work) throws Exception {
        ExecutionsBeingRecorded recording = new ExecutionsBeingRecorded();
        ScriptedRunner runner = new ScriptedRunner(recording);
        ExecutionTracker tracker = tracker(runner, records(), server.bean(SennardProperties.class), recording, Duration.ofSeconds(30), new Queued());
        String run = run();
        ExecutionRequest request = request(List.of("unused"), work, LONG);

        runner.refusal = Refused.tooManyRuns(1);
        Refused refused = assertThrows(Refused.class, () -> tracker.begin(origin(run), request));
        runner.refusal = new IllegalArgumentException("an id that the runner holds");
        assertThrows(IllegalArgumentException.class, () -> tracker.begin(origin(run), request));

        assertEquals(Refused.Reason.TOO_MANY_RUNS, refused.reason());
        assertEquals(List.of(true, true), runner.protectedWhenAsked, "each id was protected when the runner was asked");
        assertEquals(2, new java.util.HashSet<>(runner.ids).size(), "and each start has an id of its own");
        assertEquals(Set.of(), recording.ids(), "neither is protected afterwards");
        assertEquals(List.of(), records().ofRun(run), "and nothing was recorded");

        runner.refusal = null;
        ExecutionHandle taken = tracker.begin(origin(run), request);
        assertEquals(ExecutionStatus.EXITED, records().find(taken.id()).orElseThrow().status(), "a later start is taken");
        assertEquals(Set.of(), recording.ids());
        tracker.stop();
    }

    @Test
    void theStartOfTheServerRunsTheRecovery() {
        StoreStartTask task = server.context.getBean("executionRecovery", StoreStartTask.class);

        assertNotNull(task);
        task.beforeStoreReady(); // what the preparation of the store does: nothing is running, so it changes nothing and does not fail
    }
}
