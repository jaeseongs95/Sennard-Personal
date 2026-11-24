// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static io.github.jaeseongs95.sennard.personal.RunnerFixture.awaitCondition;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.awaitFile;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.java;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.program;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import io.github.jaeseongs95.sennard.app.execution.ExecutionKind;
import io.github.jaeseongs95.sennard.app.execution.ExecutionRecord;
import io.github.jaeseongs95.sennard.app.execution.ExecutionRecords;
import io.github.jaeseongs95.sennard.app.execution.ExecutionRecords.NewExecution;
import io.github.jaeseongs95.sennard.app.execution.ExecutionStatus;
import io.github.jaeseongs95.sennard.app.execution.LogCompleteness;
import io.github.jaeseongs95.sennard.personal.PersonalProperties;
import io.github.jaeseongs95.sennard.personal.StoreReadyTask;
import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.execution.ExecutionHandle;
import io.github.jaeseongs95.sennard.server.execution.ExecutionIds;
import io.github.jaeseongs95.sennard.server.execution.ExecutionLogRetention;
import io.github.jaeseongs95.sennard.server.execution.ExecutionLogStore;
import io.github.jaeseongs95.sennard.server.execution.ExecutionsBeingRecorded;
import io.github.jaeseongs95.sennard.server.execution.ProcessRunner;
import io.github.jaeseongs95.sennard.server.workflow.HostIdentity;

/**
 * The retention of the log files of executions on the assembled server: the real records in the real database, the real log folders of the server's state folder, the real
 * {@code LocalExecutionLogs}; the time is a clock the test moves (no sleep, no wall clock), the age of a folder that no record names is the modification time the test sets on it.
 * The settings are small ones (7 days, 100 bytes).
 *
 * <pre>
 * g1  the age          the log files of an execution that ended 7 days ago or longer ago are deleted and its record says when (log_removed_at); its other fields, the log completeness
 *                      (TRUNCATED stays TRUNCATED) among them, stay; a younger one is untouched
 * g2  the capacity     while the log files together take more than the capacity the ones that ended longest ago go, and no more than needed
 * g3  running          a running execution's files are never deleted, however old, and they count in the total, so ended ones go for them
 * g4  other servers    the records of another server installation are never looked at: their files are not deleted and their records not changed, and they take nothing of the capacity
 * g5  a failure        a folder that cannot be deleted is counted and logged, its record is left as it is, the others are deleted, and the next pass finishes it
 * g6  no end time      a LOST record (no end time) counts its age from when it began
 * g7  the start        the store-ready task of the server runs the pass, and a pass with nothing to do changes nothing (the folders and the records are as they were)
 * a1  no record        a folder that no record names is deleted by the age the file system gives it (the same edge), and a younger one is not
 * a2  one order        such folders count in the total and are chosen among the folders of records by the one order of age, whichever of them is older
 * a3  running here     a folder of an execution that the runner runs now, which has no record (a skill's run), is never deleted, however old, and counts; once it ended it is deleted like the others
 * a4  not followed     a link in the log folder is neither listed nor followed: what it points to is not touched
 * d1  no measuring     the folders of records are not measured: their sizes are the ones noted when they ended
 * d2  the noted bytes  what the record notes decides the total, not what a file takes now
 * d3  the pages        records are read a page at a time (a page of 2 here): every old one is deleted, and a failure of one does not stop the pages after it
 * d4  no size          a record that notes no size is measured once in the pass and counts by what it takes
 * e1  one failure      a failure of the store for one record (a location that is no path) is logged and counted, the others are deleted, and the next pass finishes it
 * e2  out of reach     a record whose folder is not in this server's log folder (or is no path) is noted out of reach (log_unreachable_at), once: its files are not touched, its completeness
 *                      stays, the next pass does not look at it again (nothing is asked of the store about it)
 * e3  undecided        a record whose folder the store cannot judge now (its answer is Undecided, not "not this server's") is not noted out of reach and its files are not deleted: one WARN
 *                      line names it (no stack), and the next pass, which can judge it, deletes it
 * g8  the kept         the execution named as just ended is not deleted by the pass that its end started, even when it is the one to go; it counts, and the next pass chooses it
 * a5  a junction      a Windows junction in the place of a folder of the log folder (the JDK does not call it a link) is neither listed nor followed either (a4 is the symbolic link; each is
 *                     run on the system that lets a test make it)
 * a6  not an id       a folder of the log folder whose name is not an execution id was not made by this server: it is neither judged nor counted, however old and big
 * a7  the order       the folders are listed before the runner is asked what runs: an execution that starts between the two is not taken for a folder no record names (the store double
 *                     starts one inside the listing)
 * d5  noted           a record that notes no size is measured once and the size is written into its record: the next pass does not measure it, and the sum has it; one that cannot be
 *                     measured is not noted as 0
 * </pre>
 */
@Tag("business-db")
class ExecutionRetentionServerTest {
    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    private static final String SESSION = "as1_RETENTIONTESTSESSION000";
    private static final Duration LONG = Duration.ofMinutes(2);
    /** Prints 300 bytes, says it runs (started.txt), and waits for go.txt. */
    private static final String CHATTY = """
            public class Chatty {
                public static void main(String[] args) throws Exception {
                    System.out.print("x".repeat(300));
                    System.out.flush();
                    java.nio.file.Files.writeString(java.nio.file.Path.of("started.txt"), "started");
                    while (!java.nio.file.Files.exists(java.nio.file.Path.of("go.txt"))) {
                        Thread.sleep(20);
                    }
                }
            }
            """;
    private static RunningServer server;

    /** A clock the test moves. */
    private static final class MovingClock extends Clock {
        private Instant now = T0;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** The real store with its calls written down, and a failure that a test can ask for: a location for which {@link #delete} throws what a location that is no path throws. */
    private static final class WatchedStore implements ExecutionLogStore {
        private final ExecutionLogStore real;
        final List<String> sized = new ArrayList<>();
        final List<String> measured = new ArrayList<>();
        final List<String> asked = new ArrayList<>();
        final Set<String> faulty = new HashSet<>();
        /** The locations whose ownership cannot be told now (the real path cannot be had): {@code Undecided}, whatever the real store says. */
        final Set<String> undecided = new HashSet<>();
        /** The locations whose size cannot be had (an IOException of the store). */
        final Set<String> unmeasurable = new HashSet<>();
        /** What happens when the folders are asked for, before they are listed. */
        Runnable beforeFolders = () -> {
        };

        WatchedStore(ExecutionLogStore real) {
            this.real = real;
        }

        @Override
        public List<Folder> folders() throws IOException {
            beforeFolders.run();
            return real.folders();
        }

        @Override
        public Ownership ownershipOf(String logLocation) {
            asked.add(logLocation);
            if (undecided.contains(logLocation)) {
                return new Ownership.Undecided(new IOException("the real path of " + logLocation + " cannot be had now"));
            }
            return real.ownershipOf(logLocation);
        }

        @Override
        public long sizeOf(String logLocation) throws IOException {
            sized.add(logLocation);
            if (unmeasurable.contains(logLocation)) {
                throw new IOException("the size of " + logLocation + " cannot be had");
            }
            return real.sizeOf(logLocation);
        }

        @Override
        public Measure measure(String logLocation) throws IOException {
            measured.add(logLocation);
            return real.measure(logLocation);
        }

        @Override
        public void delete(String logLocation) throws IOException {
            if (faulty.contains(logLocation)) {
                throw new InvalidPathException(logLocation, "a failure of the store that is not an IOException");
            }
            real.delete(logLocation);
        }
    }

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources());
        // what other test classes left in the state folder's log folder would count in the totals below: start from an empty one
        sweep(new MovingClock());
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static ExecutionRecords records() {
        return server.bean(ExecutionRecords.class);
    }

    private static String hostId() {
        return server.bean(HostIdentity.class).id();
    }

    private static Path logs() {
        return server.bean(PersonalProperties.class).stateDir().resolve("executions");
    }

    private static ExecutionLogRetention retention(MovingClock clock) {
        return retention(clock, server.bean(ExecutionLogStore.class), ExecutionLogRetention.PAGE);
    }

    private static ExecutionLogRetention retention(MovingClock clock, ExecutionLogStore store, int page) {
        SennardProperties settings = new Binder(new MapConfigurationPropertySource(Map.of("sennard.run.log-retention", "7d", "sennard.run.log-capacity", "100B"))).bindOrCreate("sennard",
                SennardProperties.class);
        return new ExecutionLogRetention(records(), store, server.bean(HostIdentity.class), server.bean(ProcessRunner.class), new ExecutionsBeingRecorded(), settings, clock, page);
    }

    /** A record of {@code host} with a log folder of {@code bytes} bytes (the size it notes); ended at {@code endedAt}, or running when that is null. */
    private static String execution(String host, Instant startedAt, Instant endedAt, long bytes) throws IOException {
        return execution(host, startedAt, endedAt, bytes, bytes, LogCompleteness.COMPLETE);
    }

    /** As {@link #execution(String, Instant, Instant, long)}, with a folder of {@code fileBytes} bytes and the size {@code notedBytes} (null: none) and completeness noted when it ended. */
    private static String execution(String host, Instant startedAt, Instant endedAt, long fileBytes, Long notedBytes, LogCompleteness completeness) throws IOException {
        String id = UUID.randomUUID().toString();
        Path folder = Files.createDirectories(logs().resolve(id));
        Files.write(folder.resolve("stdout.log"), new byte[(int) fileBytes]);
        recordAt(host, id, folder.toString(), startedAt, endedAt, notedBytes, completeness);
        return id;
    }

    private static void recordAt(String host, String id, String location, Instant startedAt, Instant endedAt, Long notedBytes, LogCompleteness completeness) {
        records().begin(new NewExecution(id, "run-retention", ExecutionKind.OPERATION, SESSION, host, List.of("java"), "/work", ExecutionStatus.RUNNING, null, startedAt, null, location,
                LogCompleteness.COMPLETE, null, null, null));
        if (endedAt != null) {
            records().finish(id, ExecutionStatus.EXITED, 0, endedAt, completeness, notedBytes);
        }
    }

    /** A folder that no record names: {@code bytes} bytes in it, and its files and itself last written at {@code modified}. */
    private static String unrecorded(Instant modified, long bytes) throws IOException {
        String id = UUID.randomUUID().toString();
        Path folder = Files.createDirectories(logs().resolve(id));
        Path file = Files.write(folder.resolve("stdout.log"), new byte[(int) bytes]);
        Files.setLastModifiedTime(file, FileTime.from(modified));
        Files.setLastModifiedTime(folder, FileTime.from(modified));
        return id;
    }

    private static boolean filesExist(String id) {
        return Files.exists(logs().resolve(id));
    }

    private static ExecutionRecord record(String id) {
        return records().find(id).orElseThrow();
    }

    /** Ends what the test made so that the next test starts clean: the records are closed and the folders deleted by the retention itself (far in the future: ten years after whatever the clock says, so that a folder the test dated ahead goes too). */
    private static void sweep(MovingClock clock) {
        clock.now = clock.now.plus(Duration.ofDays(3650));
        retention(clock).cleanUp();
    }

    @Test
    void theLogFilesOfAnExecutionThatEndedTheRetentionAgoAreDeletedAndItsRecordSaysWhenAndNothingElseOfItChanges() throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        String old = execution(hostId(), clock.now.minus(Duration.ofDays(9)), clock.now.minus(Duration.ofDays(8)), 10, 10L, LogCompleteness.TRUNCATED);
        String edge = execution(hostId(), clock.now.minus(Duration.ofDays(8)), clock.now.minus(Duration.ofDays(7)), 10);
        String young = execution(hostId(), clock.now.minus(Duration.ofDays(2)), clock.now.minus(Duration.ofDays(1)), 10);
        ExecutionRecord before = record(old);

        ExecutionLogRetention.Result result = retention(clock).cleanUp();

        assertEquals(new ExecutionLogRetention.Result(2, 0, 0), result);
        assertFalse(filesExist(old));
        assertFalse(filesExist(edge), "the edge belongs to the deletion");
        assertTrue(filesExist(young));
        assertEquals(clock.now, record(old).logRemovedAt());
        assertEquals(LogCompleteness.TRUNCATED, record(old).logCompleteness(), "what the files held when it ended is not overwritten by their deletion");
        assertEquals(new ExecutionRecord(before.id(), before.runId(), before.kind(), before.requestedBy(), before.hostId(), before.argv(), before.cwd(), before.status(), before.exitCode(),
                before.startedAt(), before.endedAt(), before.logLocation(), LogCompleteness.TRUNCATED, before.logBytes(), clock.now, null, before.pid(), before.processStartedAt()), record(old),
                "the record stays as it was, but for the time of the deletion");
        assertNull(record(young).logRemovedAt());
        assertEquals(new ExecutionLogRetention.Result(0, 0, 0), retention(clock).cleanUp(), "a second pass has nothing to do");
        sweep(clock);
    }

    @Test
    void overTheCapacityTheOnesThatEndedLongestAgoGoAndNoMoreThanNeeded() throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        String a = execution(hostId(), clock.now.minus(Duration.ofHours(5)), clock.now.minus(Duration.ofHours(4)), 40);
        String b = execution(hostId(), clock.now.minus(Duration.ofHours(4)), clock.now.minus(Duration.ofHours(3)), 40);
        String c = execution(hostId(), clock.now.minus(Duration.ofHours(3)), clock.now.minus(Duration.ofHours(2)), 40);

        assertEquals(new ExecutionLogRetention.Result(1, 0, 0), retention(clock).cleanUp(), "120 bytes with 100 allowed: one goes");

        assertFalse(filesExist(a), "the one that ended longest ago");
        assertTrue(filesExist(b) && filesExist(c));
        assertNotNull(record(a).logRemovedAt());
        assertNull(record(b).logRemovedAt());
        sweep(clock);
    }

    @Test
    void aRunningExecutionsFilesAreNeverDeletedHoweverOldAndTheyCountInTheTotal() throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        String running = execution(hostId(), clock.now.minus(Duration.ofDays(30)), null, 80);
        String ended = execution(hostId(), clock.now.minus(Duration.ofHours(5)), clock.now.minus(Duration.ofHours(4)), 40);

        ExecutionLogRetention.Result result = retention(clock).cleanUp();

        assertEquals(new ExecutionLogRetention.Result(1, 0, 0), result, "80 running and 40 ended are over 100: the ended one goes, the running one is not a candidate");
        assertTrue(filesExist(running), "thirty days old, over the capacity, and still not deleted: it is running");
        assertEquals(ExecutionStatus.RUNNING, record(running).status());
        assertFalse(filesExist(ended));
        records().finish(running, ExecutionStatus.KILLED, null, clock.now, LogCompleteness.COMPLETE, 80L);
        sweep(clock);
    }

    @Test
    void theRecordsOfAnotherServerInstallationAreNeverLookedAtAndTakeNothingOfTheCapacity(@TempDir Path elsewhere) throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        // a record of another installation, whose files are in another computer's state folder: here they are in a folder of the test
        String theirs = UUID.randomUUID().toString();
        Path theirFolder = Files.createDirectories(elsewhere.resolve(theirs));
        Files.write(theirFolder.resolve("stdout.log"), new byte[500]);
        recordAt(UUID.randomUUID().toString(), theirs, theirFolder.toString(), clock.now.minus(Duration.ofDays(40)), clock.now.minus(Duration.ofDays(39)), 500L, LogCompleteness.COMPLETE);
        ExecutionRecord before = record(theirs);

        assertEquals(new ExecutionLogRetention.Result(0, 0, 0), retention(clock).cleanUp());

        assertTrue(Files.exists(theirFolder.resolve("stdout.log")), "old and big, and another installation's: not this server's to delete");
        assertEquals(before, record(theirs), "and its record is not changed");
        // not its to count either: it takes nothing of this server's capacity
        String own = execution(hostId(), clock.now.minus(Duration.ofHours(3)), clock.now.minus(Duration.ofHours(2)), 90);
        assertEquals(new ExecutionLogRetention.Result(0, 0, 0), retention(clock).cleanUp());
        assertTrue(filesExist(own));
        sweep(clock);
    }

    @Test
    void aFolderThatCannotBeDeletedIsCountedAndItsRecordIsLeftAndTheNextPassFinishesIt() throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        String stuck = execution(hostId(), clock.now.minus(Duration.ofDays(10)), clock.now.minus(Duration.ofDays(9)), 10);
        String fine = execution(hostId(), clock.now.minus(Duration.ofDays(10)), clock.now.minus(Duration.ofDays(9)), 10);
        Path blocker = Files.createDirectories(logs().resolve(stuck).resolve("not-a-log").resolve("deeper"));

        ExecutionLogRetention.Result first = retention(clock).cleanUp();

        assertEquals(new ExecutionLogRetention.Result(1, 1, 0), first);
        assertFalse(filesExist(fine), "the other one was deleted");
        assertTrue(filesExist(stuck));
        assertNull(record(stuck).logRemovedAt(), "the record does not say deleted: it was not");

        Files.delete(blocker);
        Files.delete(blocker.getParent());
        assertEquals(new ExecutionLogRetention.Result(1, 0, 0), retention(clock).cleanUp(), "the next pass chooses it again and finishes");
        assertFalse(filesExist(stuck));
        assertNotNull(record(stuck).logRemovedAt());
        sweep(clock);
    }

    @Test
    void aLostRecordHasNoEndTimeAndCountsItsAgeFromWhenItBegan() throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        String lost = execution(hostId(), clock.now.minus(Duration.ofDays(8)), null, 10);
        records().finish(lost, ExecutionStatus.LOST, null, null, LogCompleteness.TRUNCATED, 10L);
        assertNull(record(lost).endedAt());

        assertEquals(new ExecutionLogRetention.Result(1, 0, 0), retention(clock).cleanUp());

        assertFalse(filesExist(lost));
        assertNotNull(record(lost).logRemovedAt());
        assertEquals(LogCompleteness.TRUNCATED, record(lost).logCompleteness());
        sweep(clock);
    }

    @Test
    void theStoreReadyTaskOfTheServerRunsThePassAndAPassWithNothingToDoChangesNothing() throws Exception {
        StoreReadyTask task = server.context.getBean("executionLogRetention", StoreReadyTask.class);
        Instant now = server.bean(Clock.class).instant();
        String recorded = execution(hostId(), now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)), 10);
        String stray = unrecorded(now.minus(Duration.ofHours(1)), 10);
        ExecutionRecord before = record(recorded);
        Set<String> foldersBefore = folderNames();
        assertTrue(foldersBefore.containsAll(Set.of(recorded, stray)));

        task.whenStoreReady();

        assertEquals(foldersBefore, folderNames(), "no folder was deleted");
        assertEquals(before, record(recorded), "and no record was changed");
        assertNull(record(recorded).logRemovedAt());
        assertNull(record(recorded).logUnreachableAt());
        sweep(new MovingClock());
    }

    private static Set<String> folderNames() throws IOException {
        try (var folders = Files.list(logs())) {
            return new TreeSet<>(folders.map(folder -> folder.getFileName().toString()).toList());
        }
    }

    // ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- a

    @Test
    void aFolderThatNoRecordNamesIsDeletedByTheAgeTheFileSystemGivesItAndNoRecordIsTouched() throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        String old = unrecorded(clock.now.minus(Duration.ofDays(8)), 10);
        String edge = unrecorded(clock.now.minus(Duration.ofDays(7)), 10);
        String young = unrecorded(clock.now.minus(Duration.ofDays(1)), 10);
        String recorded = execution(hostId(), clock.now.minus(Duration.ofDays(2)), clock.now.minus(Duration.ofDays(1)), 10);
        ExecutionRecord before = record(recorded);

        assertEquals(new ExecutionLogRetention.Result(2, 0, 0), retention(clock).cleanUp());

        assertFalse(filesExist(old));
        assertFalse(filesExist(edge), "the edge belongs to the deletion");
        assertTrue(filesExist(young));
        assertEquals(before, record(recorded));
        assertEquals(new ExecutionLogRetention.Result(0, 0, 0), retention(clock).cleanUp(), "a second pass has nothing to do");
        sweep(clock);
    }

    @Test
    void foldersThatNoRecordNamesCountInTheTotalAndAreChosenAmongTheOthersByTheOneOrderOfAge() throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        // the folder without a record is the older: it goes
        String recorded = execution(hostId(), clock.now.minus(Duration.ofHours(3)), clock.now.minus(Duration.ofHours(2)), 60);
        String stray = unrecorded(clock.now.minus(Duration.ofHours(5)), 60);

        assertEquals(new ExecutionLogRetention.Result(1, 0, 0), retention(clock).cleanUp(), "120 bytes with 100 allowed");

        assertFalse(filesExist(stray));
        assertTrue(filesExist(recorded));
        assertNull(record(recorded).logRemovedAt());
        sweep(clock);

        // the record is the older: it goes, and says so
        String olderRecorded = execution(hostId(), clock.now.minus(Duration.ofHours(7)), clock.now.minus(Duration.ofHours(6)), 60);
        String newerStray = unrecorded(clock.now.minus(Duration.ofHours(1)), 60);

        assertEquals(new ExecutionLogRetention.Result(1, 0, 0), retention(clock).cleanUp());

        assertFalse(filesExist(olderRecorded));
        assertNotNull(record(olderRecorded).logRemovedAt());
        assertTrue(filesExist(newerStray));
        sweep(clock);
    }

    @Test
    void aFolderOfAnExecutionThatRunsHereAndHasNoRecordIsNeverDeletedAndCountsAndWhenItEndedItGoesLikeTheOthers(@TempDir Path work) throws Exception {
        program(work, "Chatty", CHATTY);
        MovingClock clock = new MovingClock();
        // far ahead: whatever the file system says of the folder of the running execution is older than the retention
        clock.now = T0.plus(Duration.ofDays(3650));
        ProcessRunner runner = server.bean(ProcessRunner.class);
        ExecutionHandle live = runner.start(ExecutionIds.next(), java(work, LONG, "Chatty"));
        try {
            awaitFile(work.resolve("started.txt"));
            Path out = logs().resolve(live.id()).resolve("stdout.log");
            awaitCondition(() -> sizeOf(out) >= 300, "the output of the running execution in its log file");
            assertTrue(runner.runningIds().contains(live.id()));
            String ended = execution(hostId(), clock.now.minus(Duration.ofHours(5)), clock.now.minus(Duration.ofHours(4)), 40);

            // a running execution's 300 bytes and 40 ended ones are over 100: the ended one goes; the running one has no record and its folder is old by the clock, and stays
            assertEquals(new ExecutionLogRetention.Result(1, 0, 0), retention(clock).cleanUp());

            assertTrue(filesExist(live.id()), "a folder of an execution that runs here is not deleted, with a record or without");
            assertFalse(filesExist(ended));
        } finally {
            Files.writeString(work.resolve("go.txt"), "go");
        }
        assertTrue(live.await(LONG).state().finished());

        assertEquals(new ExecutionLogRetention.Result(1, 0, 0), retention(clock).cleanUp(), "it ended: its folder, which no record names, is judged by the age of its files");
        assertFalse(filesExist(live.id()));
    }

    /** Makes the store fail for the folder of an execution, by whichever way its location is spelled (a record's, or the listing's). */
    private static void fail(WatchedStore store, String id) {
        store.faulty.add(logs().resolve(id).toString());
        store.faulty.add(logs().toAbsolutePath().normalize().resolve(id).toString());
    }

    private static long sizeOf(Path file) {
        try {
            return Files.exists(file) ? Files.size(file) : 0;
        } catch (IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    @Test
    void aLinkInTheLogFolderIsNeitherListedNorFollowedAndWhatItPointsToIsNotTouched(@TempDir Path elsewhere) throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(3650));
        Path precious = Files.writeString(elsewhere.resolve("precious.txt"), "precious");
        Path link = logs().resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectories(logs());
            Files.createSymbolicLink(link, elsewhere);
        } catch (UnsupportedOperationException | IOException noLinks) {
            assumeTrue(false, "this system lets this user make no link: " + noLinks);
        }
        try {
            assertEquals(new ExecutionLogRetention.Result(0, 0, 0), retention(clock).cleanUp());

            assertTrue(Files.exists(precious), "what the link points to is not touched");
            assertTrue(Files.isSymbolicLink(link), "and the link is left as it is");
        } finally {
            Files.deleteIfExists(link);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- d

    @Test
    void theFoldersOfRecordsAreNotMeasuredTheirSizesAreTheOnesNotedWhenTheyEnded() throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        for (int count = 0; count < 3; count++) {
            execution(hostId(), clock.now.minus(Duration.ofHours(5)), clock.now.minus(Duration.ofHours(4)), 10);
        }
        WatchedStore store = new WatchedStore(server.bean(ExecutionLogStore.class));

        assertEquals(new ExecutionLogRetention.Result(0, 0, 0), retention(clock, store, ExecutionLogRetention.PAGE).cleanUp());

        assertEquals(List.of(), store.sized, "no folder of a record was measured");
        assertEquals(List.of(), store.measured, "and none was looked at for its age");
        sweep(clock);
    }

    @Test
    void whatTheRecordNotesDecidesTheTotalAndNotWhatAFileTakesNow() throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        // each file takes 1 byte, each record notes 60: the total by the records is 120, over 100
        String first = execution(hostId(), clock.now.minus(Duration.ofHours(5)), clock.now.minus(Duration.ofHours(4)), 1, 60L, LogCompleteness.COMPLETE);
        String second = execution(hostId(), clock.now.minus(Duration.ofHours(3)), clock.now.minus(Duration.ofHours(2)), 1, 60L, LogCompleteness.COMPLETE);

        assertEquals(new ExecutionLogRetention.Result(1, 0, 0), retention(clock).cleanUp());

        assertFalse(filesExist(first));
        assertTrue(filesExist(second));
        sweep(clock);
    }

    @Test
    void recordsAreReadAPageAtATimeEveryOldOneIsDeletedAndAFailureOfOneDoesNotStopThePagesAfterIt() throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        List<String> ids = new ArrayList<>();
        for (int count = 0; count < 5; count++) {
            ids.add(execution(hostId(), clock.now.minus(Duration.ofDays(30 - count)), clock.now.minus(Duration.ofDays(29 - count)), 1));
        }
        String young = execution(hostId(), clock.now.minus(Duration.ofHours(5)), clock.now.minus(Duration.ofHours(4)), 1);

        assertEquals(new ExecutionLogRetention.Result(5, 0, 0), retention(clock, server.bean(ExecutionLogStore.class), 2).cleanUp(), "five old ones, in pages of two");

        ids.forEach(id -> assertFalse(filesExist(id), id));
        assertTrue(filesExist(young));

        // the oldest of the next five cannot be deleted: the pages after it are still read
        List<String> more = new ArrayList<>();
        for (int count = 0; count < 5; count++) {
            more.add(execution(hostId(), clock.now.minus(Duration.ofDays(30 - count)), clock.now.minus(Duration.ofDays(29 - count)), 1));
        }
        WatchedStore store = new WatchedStore(server.bean(ExecutionLogStore.class));
        fail(store, more.getFirst());

        assertEquals(new ExecutionLogRetention.Result(4, 1, 0), retention(clock, store, 2).cleanUp());

        assertTrue(filesExist(more.getFirst()));
        more.stream().skip(1).forEach(id -> assertFalse(filesExist(id), id));
        sweep(clock);
    }

    @Test
    void aRecordThatNotesNoSizeIsMeasuredInThePassAndCountsByWhatItTakes() throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        // the older one notes no size (it could not be measured when it ended): 70 bytes by its files; the other notes 60: 130 over 100
        String unnoted = execution(hostId(), clock.now.minus(Duration.ofHours(5)), clock.now.minus(Duration.ofHours(4)), 70, null, LogCompleteness.COMPLETE);
        String noted = execution(hostId(), clock.now.minus(Duration.ofHours(3)), clock.now.minus(Duration.ofHours(2)), 60);
        WatchedStore store = new WatchedStore(server.bean(ExecutionLogStore.class));

        assertEquals(new ExecutionLogRetention.Result(1, 0, 0), retention(clock, store, ExecutionLogRetention.PAGE).cleanUp());

        assertEquals(List.of(logs().resolve(unnoted).toString()), store.sized, "only the one without a size was measured");
        assertFalse(filesExist(unnoted));
        assertTrue(filesExist(noted));
        sweep(clock);
    }

    // ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- e

    @Test
    void aFailureOfTheStoreForOneRecordIsLoggedAndCountedTheOthersAreDeletedAndTheNextPassFinishesIt() throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        String faulty = execution(hostId(), clock.now.minus(Duration.ofDays(10)), clock.now.minus(Duration.ofDays(9)), 10);
        String fine = execution(hostId(), clock.now.minus(Duration.ofDays(10)), clock.now.minus(Duration.ofDays(9)), 10);
        String strayFaulty = unrecorded(clock.now.minus(Duration.ofDays(10)), 10);
        WatchedStore store = new WatchedStore(server.bean(ExecutionLogStore.class));
        fail(store, faulty);
        fail(store, strayFaulty);

        ExecutionLogRetention.Result first = retention(clock, store, ExecutionLogRetention.PAGE).cleanUp();

        assertEquals(new ExecutionLogRetention.Result(1, 2, 0), first, "a failure that is not an IOException stops nothing");
        assertFalse(filesExist(fine));
        assertTrue(filesExist(faulty) && filesExist(strayFaulty));
        assertNull(record(faulty).logRemovedAt());

        store.faulty.clear();
        assertEquals(new ExecutionLogRetention.Result(2, 0, 0), retention(clock, store, ExecutionLogRetention.PAGE).cleanUp());
        assertFalse(filesExist(faulty) || filesExist(strayFaulty));
        assertNotNull(record(faulty).logRemovedAt());
        sweep(clock);
    }

    @Test
    void aRecordWhoseFolderIsNotInThisServersLogFolderIsNotedOutOfReachOnceItsFilesAreNotTouchedAndItIsNotLookedAtAgain(@TempDir Path elsewhere) throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        String outside = UUID.randomUUID().toString();
        Path folder = Files.createDirectories(elsewhere.resolve(outside));
        Files.write(folder.resolve("stdout.log"), new byte[500]);
        recordAt(hostId(), outside, folder.toString(), clock.now.minus(Duration.ofDays(40)), clock.now.minus(Duration.ofDays(39)), 500L, LogCompleteness.TRUNCATED);
        // a text that is not a path on every system, or is one outside the log folder on the others
        String noPath = UUID.randomUUID().toString();
        recordAt(hostId(), noPath, "|?<>:*", clock.now.minus(Duration.ofDays(40)), clock.now.minus(Duration.ofDays(39)), 5L, LogCompleteness.COMPLETE);
        String own = execution(hostId(), clock.now.minus(Duration.ofHours(3)), clock.now.minus(Duration.ofHours(2)), 90);
        WatchedStore store = new WatchedStore(server.bean(ExecutionLogStore.class));

        ExecutionLogRetention.Result first = retention(clock, store, ExecutionLogRetention.PAGE).cleanUp();

        // 500 bytes of the outside record would have made 590 over 100 and deleted the own one, had it counted
        assertEquals(new ExecutionLogRetention.Result(0, 0, 2), first);
        assertTrue(Files.exists(folder.resolve("stdout.log")), "its files are not touched");
        assertTrue(filesExist(own), "and the folder of this server is not deleted for the bytes of a folder it cannot reach");
        for (String id : List.of(outside, noPath)) {
            assertEquals(clock.now, record(id).logUnreachableAt(), id);
            assertNull(record(id).logRemovedAt(), id);
        }
        assertEquals(LogCompleteness.TRUNCATED, record(outside).logCompleteness(), "the completeness is the execution's and is not mixed with this");

        store.asked.clear();
        assertEquals(new ExecutionLogRetention.Result(0, 0, 0), retention(clock, store, ExecutionLogRetention.PAGE).cleanUp());
        assertFalse(store.asked.contains(folder.toString()) || store.asked.contains("|?<>:*"), "the store is not asked about them again: " + store.asked);
        assertEquals(clock.now, record(outside).logUnreachableAt());
        sweep(clock);
    }

    @Test
    void aRecordWhoseFolderCannotBeJudgedNowIsNotNotedOutOfReachNorDeletedIsWarnedAboutInOneLineAndTheNextPassJudgesIt() throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        String undecidedId = execution(hostId(), clock.now.minus(Duration.ofDays(10)), clock.now.minus(Duration.ofDays(9)), 10);
        String other = execution(hostId(), clock.now.minus(Duration.ofDays(10)), clock.now.minus(Duration.ofDays(9)), 10);
        WatchedStore store = new WatchedStore(server.bean(ExecutionLogStore.class));
        store.undecided.add(logs().resolve(undecidedId).toString());
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ExecutionLogRetention.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        ExecutionLogRetention.Result first;
        try {
            first = retention(clock, store, ExecutionLogRetention.PAGE).cleanUp();
        } finally {
            logger.detachAppender(logged);
            logged.stop();
        }

        assertEquals(new ExecutionLogRetention.Result(1, 0, 0), first, "the one that can be judged is deleted; the one that cannot is neither deleted nor noted out of reach");
        assertFalse(filesExist(other));
        assertTrue(filesExist(undecidedId), "its files are not touched");
        assertNull(record(undecidedId).logUnreachableAt(), "an answer that is not known now is not noted in the record for ever");
        assertNull(record(undecidedId).logRemovedAt());
        List<ILoggingEvent> warnings = logged.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().getFormattedMessage().contains(undecidedId) && warnings.getFirst().getFormattedMessage().contains("cannot be had now"), warnings.getFirst().getFormattedMessage());
        assertNull(warnings.getFirst().getThrowableProxy(), "one line: the reason is in the text, with no stack");

        store.undecided.clear();
        assertEquals(new ExecutionLogRetention.Result(1, 0, 0), retention(clock, store, ExecutionLogRetention.PAGE).cleanUp(), "the next pass judges it");
        assertFalse(filesExist(undecidedId));
        assertNotNull(record(undecidedId).logRemovedAt());
        sweep(clock);
    }

    // ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- g8

    @Test
    void theExecutionNamedAsJustEndedIsNotDeletedByThePassItsEndStartedEvenWhenItIsTheOneToGoAndTheNextPassChoosesIt() throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        String justEnded = execution(hostId(), clock.now.minus(Duration.ofHours(5)), clock.now.minus(Duration.ofHours(4)), 90);
        String newer = execution(hostId(), clock.now.minus(Duration.ofHours(3)), clock.now.minus(Duration.ofHours(2)), 40);

        // 130 bytes with 100 allowed, and the one that ended longest ago is the one just ended: it stays, and the newer one goes for it
        assertEquals(new ExecutionLogRetention.Result(1, 0, 0), retention(clock).cleanUp(Set.of(justEnded)));
        assertTrue(filesExist(justEnded));
        assertNull(record(justEnded).logRemovedAt());
        assertFalse(filesExist(newer));

        String another = execution(hostId(), clock.now.minus(Duration.ofHours(1)), clock.now.minus(Duration.ofMinutes(30)), 40);
        assertEquals(new ExecutionLogRetention.Result(1, 0, 0), retention(clock).cleanUp(), "the next pass has no one to keep: the one that ended longest ago goes");
        assertFalse(filesExist(justEnded));
        assertTrue(filesExist(another));
        sweep(clock);
    }

    // ----------------------------------------------------------------------------------------------------------------------------------------------------------------- a5, a6, a7

    /** A Windows junction: the JDK has no call to make one, so the shell's {@code mklink /J} prepares this fixture. */
    private static void createJunction(Path junction, Path target) throws IOException {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), target.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "junction fixture creation timed out");
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while a junction was created", interrupted);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aJunctionInTheLogFolderIsNeitherListedNorFollowedAndWhatItPointsToIsNotTouched(@TempDir Path elsewhere) throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(3650));
        Path precious = Files.write(elsewhere.resolve("precious.txt"), new byte[500]);
        Path junction = logs().resolve(UUID.randomUUID().toString());
        Files.createDirectories(logs());
        createJunction(junction, elsewhere);
        try {
            // 500 bytes, older than the retention by any clock and over the capacity: it would be deleted had the junction been taken for a folder of this server
            ExecutionLogRetention.Result result = retention(clock).cleanUp();

            assertTrue(Files.exists(precious), "what the junction points to is not touched");
            assertEquals(500, Files.size(precious));
            assertEquals(new ExecutionLogRetention.Result(0, 0, 0), result);
            assertTrue(Files.exists(junction, java.nio.file.LinkOption.NOFOLLOW_LINKS), "and the junction is left as it is");
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test
    void aFolderThatIsNotNamedAsAnExecutionIdIsNeitherJudgedNorCountedHoweverOldAndBig() throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        Path stray = Files.createDirectories(logs().resolve("not-an-execution-id"));
        Path file = Files.write(stray.resolve("stdout.log"), new byte[500]);
        Files.setLastModifiedTime(file, FileTime.from(clock.now.minus(Duration.ofDays(30))));
        Files.setLastModifiedTime(stray, FileTime.from(clock.now.minus(Duration.ofDays(30))));
        // 90 bytes of its own: under the capacity unless the 500 bytes of the folder that this server did not make are counted
        String own = execution(hostId(), clock.now.minus(Duration.ofHours(3)), clock.now.minus(Duration.ofHours(2)), 90);
        try {
            assertEquals(new ExecutionLogRetention.Result(0, 0, 0), retention(clock).cleanUp());

            assertTrue(Files.exists(file), "older than the retention and over the capacity, and not deleted: this server did not make it");
            assertTrue(filesExist(own), "and it is not counted: the folder of this server is not deleted for its bytes");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(stray);
            sweep(clock);
        }
    }

    @Test
    void theFoldersAreListedBeforeTheRunnerIsAskedWhatRunsSoAnExecutionThatStartsBetweenThemIsNotTakenForAFolderNoRecordNames(@TempDir Path work) throws Exception {
        program(work, "Chatty", CHATTY);
        MovingClock clock = new MovingClock();
        // far ahead: whatever the file system says of a folder is older than the retention
        clock.now = T0.plus(Duration.ofDays(3650));
        ProcessRunner runner = server.bean(ProcessRunner.class);
        AtomicReference<ExecutionHandle> started = new AtomicReference<>();
        WatchedStore store = new WatchedStore(server.bean(ExecutionLogStore.class));
        // the execution is started inside the listing of the folders, before the listing is made: its folder is in the list, and it runs when the runner is asked after that
        store.beforeFolders = () -> started.set(runner.start(ExecutionIds.next(), java(work, LONG, "Chatty")));
        try {
            assertEquals(new ExecutionLogRetention.Result(0, 0, 0), retention(clock, store, ExecutionLogRetention.PAGE).cleanUp());

            assertNotNull(started.get());
            assertTrue(filesExist(started.get().id()), "the folder of an execution that runs is not deleted, whichever way it came to be in the list");
        } finally {
            Files.writeString(work.resolve("go.txt"), "go");
        }
        assertTrue(started.get().await(LONG).state().finished());
        sweep(clock);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- d5

    @Test
    void aRecordThatNotedNoSizeIsMeasuredOnceAndTheSizeIsWrittenIntoItsRecordSoThatNoPassMeasuresItAgainAndTheSumHasIt() throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        // 30 and 30 bytes: under the capacity, so nothing is deleted and only the measuring is looked at
        String unnoted = execution(hostId(), clock.now.minus(Duration.ofHours(5)), clock.now.minus(Duration.ofHours(4)), 30, null, LogCompleteness.COMPLETE);
        String noted = execution(hostId(), clock.now.minus(Duration.ofHours(3)), clock.now.minus(Duration.ofHours(2)), 30);
        assertNull(record(unnoted).logBytes());
        assertEquals(30L, records().loggedBytes(hostId()), "the sum has the one that notes a size only");
        WatchedStore first = new WatchedStore(server.bean(ExecutionLogStore.class));

        assertEquals(new ExecutionLogRetention.Result(0, 0, 0), retention(clock, first, ExecutionLogRetention.PAGE).cleanUp());

        assertEquals(List.of(logs().resolve(unnoted).toString()), first.sized, "measured in the pass");
        assertEquals(30L, record(unnoted).logBytes(), "and noted in its record");
        assertEquals(60L, records().loggedBytes(hostId()), "the sum has it from now on");
        WatchedStore second = new WatchedStore(server.bean(ExecutionLogStore.class));

        assertEquals(new ExecutionLogRetention.Result(0, 0, 0), retention(clock, second, ExecutionLogRetention.PAGE).cleanUp());

        assertEquals(List.of(), second.sized, "the next pass does not measure it again");
        assertTrue(filesExist(unnoted) && filesExist(noted));
        sweep(clock);
    }

    @Test
    void aRecordWhoseFolderCannotBeMeasuredIsNotNotedAsNothingAndIsMeasuredAgainByTheNextPass() throws Exception {
        MovingClock clock = new MovingClock();
        clock.now = T0.plus(Duration.ofDays(20));
        String unnoted = execution(hostId(), clock.now.minus(Duration.ofHours(5)), clock.now.minus(Duration.ofHours(4)), 30, null, LogCompleteness.COMPLETE);
        WatchedStore store = new WatchedStore(server.bean(ExecutionLogStore.class));
        store.unmeasurable.add(logs().resolve(unnoted).toString());

        retention(clock, store, ExecutionLogRetention.PAGE).cleanUp();

        assertNull(record(unnoted).logBytes(), "what could not be measured is not written as 0");
        store.unmeasurable.clear();
        retention(clock, store, ExecutionLogRetention.PAGE).cleanUp();
        assertEquals(30L, record(unnoted).logBytes());
        sweep(clock);
    }
}
