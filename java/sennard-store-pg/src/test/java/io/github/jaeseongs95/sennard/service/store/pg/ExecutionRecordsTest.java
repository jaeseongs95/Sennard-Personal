// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.sennard.app.execution.ExecutionKind;
import io.github.jaeseongs95.sennard.app.execution.ExecutionLogCandidates.Candidate;
import io.github.jaeseongs95.sennard.app.execution.ExecutionLogCandidates.Position;
import io.github.jaeseongs95.sennard.app.execution.ExecutionRecord;
import io.github.jaeseongs95.sennard.app.execution.ExecutionRecords;
import io.github.jaeseongs95.sennard.app.execution.ExecutionRecords.NewExecution;
import io.github.jaeseongs95.sennard.app.execution.ExecutionStatus;
import io.github.jaeseongs95.sennard.app.execution.LogCompleteness;

/**
 * The record of the programs that workflows had run ({@code sennard.execution}, V28) on the real business database and the real service: every method one transaction, no mock.
 *
 * <pre>
 * e1  a running record      is written and read back with every column (argv as a JSON array, the process id and its start time), and is among the running ones
 * e2  the end               closes a running record with its status, exit code, end time, log completeness and log bytes; a second end changes nothing and says so (the first one stays)
 * e3  an end that is not    a record that does not exist is not closed; the end RUNNING is refused
 * e4  records that began    a program that was not started, or ended before its record was written, is written closed (no process, or an exit code) and is not among the running ones
 *     closed
 * e5  the lists             the running ones and the ones of one workflow run are the right ones, the earliest first
 * e6  argv                  strings with quotes, backslashes, line breaks and non-ASCII characters come back as they were written
 * e7  the table             has no foreign key and no column that could hold the output (no binary, no large object), the columns in the order the design names, module_schema execution is 1
 * e8  the constraints       the database itself refuses a running row with an end time, a closed row without one (but a LOST row has none, and may not have one), an exit code on a status that has
 *                           none, a status or kind or log completeness that is not on its list (INQUIRY is not a kind, REMOVED is not a completeness), a negative size, a deleted-log time on a
 *                           running row, and a log both deleted and out of reach
 * e9  one installation     the running records and the log candidates are those of one server installation (host_id) only
 * e10 no end time           a LOST record is closed without an end time, any other end needs one
 * e11 a transaction        the record is not written or closed from inside a transaction of the caller (propagation NEVER), and nothing changes when that is refused; the plain question
 *                           whether the caller is inside one answers the same way and writes nothing
 * e12 the pages            the log candidates come oldest first by the end (the start for a LOST record), the same time by id, a page at a time after a position, and only those that have ended,
 *                           have a log folder, and are neither deleted nor out of reach
 * e13 the sum              of the bytes noted when the candidates ended: no other record, no running one, no one without a size counts
 * e14 the folders          which of many ids (more than one statement takes) are records of the installation whose log folder is still there, running ones too
 * e15 the notes            a deleted log has its time and the record keeps its completeness; a log out of reach is noted once, never together with a deleted one, a running record cannot be noted deleted
 * e16 the two columns      the database refuses an age (aged_at) that is not the end, or the start while there is none, and a pending flag (log_pending) that is not 1 exactly for an ended row
 *                          with a log folder that is neither deleted nor out of reach (and null, and a value that is not 0 or 1)
 * e17 every step           a running row has its start as its age and nothing pending; closing gives the end (a LOST row keeps the start) and pending when there is a folder; a deleted or an out
 *                          of reach log is no longer pending
 * e18 the index            the retention's index leads with the installation, the pending flag, the age and the id (read through the metadata of the database, not a product's catalog)
 * e19 the size noted       a record with no size is given one once; one that has a size, a running one, a deleted one and one that is not there are left as they are
 * e20 the same age         pages that end among records of the same age (the keyset position is the age and then the id) give every record once, in order, and a position inside the group is exact
 * e21 a running one        a running record is not noted out of reach or deleted; closing a row that was marked while running (which the table allows) is accepted and leaves it not pending
 * </pre>
 */
@BusinessDbTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExecutionRecordsTest {
    private static final Instant T0 = Instant.parse("2026-03-04T05:06:07.089Z");
    private static final String RUN = "run-task-1111-1";
    private static final String SESSION = "as1_TESTSESSIONAAAAAAAAAAAAAA";
    private static final String HOST = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER_HOST = "22222222-2222-4222-8222-222222222222";

    private RewrittenAreasContext context;
    private ExecutionRecords records;

    @BeforeAll
    void start() {
        context = RewrittenAreasContext.appConnection(T0);
        records = context.bean(ExecutionRecords.class);
    }

    @AfterAll
    void close() {
        if (context != null) {
            context.close();
        }
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static String host() {
        return UUID.randomUUID().toString();
    }

    private static NewExecution running(String id, String run, Instant startedAt, long pid) {
        return new NewExecution(id, run, ExecutionKind.OPERATION, SESSION, HOST, List.of("java", "-version"), "D:\\work\\folder", ExecutionStatus.RUNNING, null, startedAt, null, "state/executions/" + id,
                LogCompleteness.COMPLETE, null, pid, startedAt.plusMillis(5));
    }

    /** A record of {@code host} that has ended (or is LOST when {@code endedAt} is null) with a log folder; its size is noted when {@code bytes} is not null. */
    private void ended(String host, String id, Instant startedAt, Instant endedAt, Long bytes) {
        records.begin(new NewExecution(id, RUN, ExecutionKind.OPERATION, SESSION, host, List.of("java"), "/w", ExecutionStatus.RUNNING, null, startedAt, null, "state/executions/" + id,
                LogCompleteness.COMPLETE, null, null, null));
        if (endedAt != null) {
            assertTrue(records.finish(id, ExecutionStatus.EXITED, 0, endedAt, LogCompleteness.COMPLETE, bytes));
        } else {
            assertTrue(records.finish(id, ExecutionStatus.LOST, null, null, LogCompleteness.TRUNCATED, bytes));
        }
    }

    private ExecutionRecord found(String id) {
        return records.find(id).orElseThrow();
    }

    private static List<String> ids(List<Candidate> candidates) {
        return candidates.stream().map(Candidate::id).toList();
    }

    @Test
    void aRunningRecordIsWrittenAndReadBackWithEveryColumn() {
        String id = id();
        records.begin(running(id, RUN, T0, 4242));

        ExecutionRecord record = found(id);
        assertEquals(new ExecutionRecord(id, RUN, ExecutionKind.OPERATION, SESSION, HOST, "[\"java\",\"-version\"]", "D:\\work\\folder", ExecutionStatus.RUNNING, null, T0, null, "state/executions/" + id,
                LogCompleteness.COMPLETE, null, null, null, 4242L, T0.plusMillis(5)), record);
        assertEquals(List.of("java", "-version"), records.argvOf(record));
        assertTrue(records.running(HOST).stream().anyMatch(each -> each.id().equals(id)));
    }

    @Test
    void theEndClosesARunningRecordAndASecondEndChangesNothing() {
        String id = id();
        records.begin(running(id, RUN, T0, 1));
        Instant end = T0.plusSeconds(3);

        assertTrue(records.finish(id, ExecutionStatus.EXITED, 7, end, LogCompleteness.TRUNCATED, 1234L), "this call closed it");
        ExecutionRecord closed = found(id);
        assertEquals(ExecutionStatus.EXITED, closed.status());
        assertEquals(7, closed.exitCode());
        assertEquals(end, closed.endedAt());
        assertEquals(LogCompleteness.TRUNCATED, closed.logCompleteness());
        assertEquals(1234L, closed.logBytes());
        assertFalse(records.running(HOST).stream().anyMatch(each -> each.id().equals(id)));

        assertFalse(records.finish(id, ExecutionStatus.KILLED, null, end.plusSeconds(1), LogCompleteness.COMPLETE, 1L), "it was closed already: the first end stays");
        assertEquals(closed, found(id));
    }

    @Test
    void aRecordThatDoesNotExistIsNotClosedAndTheEndRunningIsRefused() {
        assertFalse(records.finish(id(), ExecutionStatus.KILLED, null, T0, LogCompleteness.COMPLETE, null));
        assertThrows(IllegalArgumentException.class, () -> records.finish(id(), ExecutionStatus.RUNNING, null, T0, LogCompleteness.COMPLETE, null));
        assertEquals(Optional.empty(), records.find(id()));
    }

    @Test
    void aProgramThatWasNotStartedOrEndedBeforeItsRecordWasWrittenIsWrittenClosed() {
        String notStarted = id();
        records.begin(new NewExecution(notStarted, RUN, ExecutionKind.OPERATION, SESSION, HOST, List.of("no-such-program"), "/work", ExecutionStatus.NOT_STARTED, null, T0, T0, null,
                LogCompleteness.COMPLETE, null, null, null));
        String exited = id();
        records.begin(new NewExecution(exited, RUN, ExecutionKind.OPERATION, SESSION, HOST, List.of("true"), "/work", ExecutionStatus.EXITED, 0, T0, T0.plusMillis(30), "state/executions/" + exited,
                LogCompleteness.COMPLETE, 77L, 99L, T0));

        ExecutionRecord first = found(notStarted);
        assertEquals(ExecutionStatus.NOT_STARTED, first.status());
        assertNull(first.pid());
        assertNull(first.processStartedAt());
        assertNull(first.logLocation());
        assertNull(first.exitCode());
        assertEquals(ExecutionKind.OPERATION, first.kind());
        assertEquals(0, found(exited).exitCode());
        assertEquals(77L, found(exited).logBytes(), "the size of what a program that ended before its record was written left");
        assertTrue(records.running(HOST).stream().noneMatch(each -> each.id().equals(notStarted) || each.id().equals(exited)));
    }

    @Test
    void theRunningOnesAndTheOnesOfARunAreTheRightOnesTheEarliestFirst() {
        String run = "run-lists-" + UUID.randomUUID();
        String late = id();
        String early = id();
        String other = id();
        records.begin(running(late, run, T0.plusSeconds(20), 3));
        records.begin(running(early, run, T0.plusSeconds(10), 2));
        records.begin(running(other, "another-run", T0.plusSeconds(15), 4));
        records.finish(other, ExecutionStatus.TIMED_OUT, null, T0.plusSeconds(16), LogCompleteness.COMPLETE, null);

        assertEquals(List.of(early, late), records.ofRun(run).stream().map(ExecutionRecord::id).toList());
        List<String> running = records.running(HOST).stream().map(ExecutionRecord::id).filter(id -> id.equals(early) || id.equals(late) || id.equals(other)).toList();
        assertEquals(List.of(early, late), running, "the closed one is not running, the others are in the order they began");
    }

    @Test
    void argvWithQuotesBackslashesLineBreaksAndNonAsciiCharactersComesBackAsWritten() {
        String id = id();
        List<String> argv = List.of("C:\\Program Files\\tool.exe", "say \"hi\"", "two\nlines", "\u00e9\uc548\ub155 \ud83d\ude00", "", "tab\there");
        records.begin(new NewExecution(id, RUN, ExecutionKind.OPERATION, SESSION, HOST, argv, "/w", ExecutionStatus.RUNNING, null, T0, null, null, LogCompleteness.COMPLETE, null, 5L, null));

        assertEquals(argv, records.argvOf(found(id)));
    }

    @Test
    void theRunningOnesAndTheLogCandidatesAreThoseOfOneServerInstallationOnly() {
        String own = id();
        String theirs = id();
        records.begin(running(own, RUN, T0, 11));
        records.begin(new NewExecution(theirs, RUN, ExecutionKind.OPERATION, SESSION, OTHER_HOST, List.of("java"), "/w", ExecutionStatus.RUNNING, null, T0, null, "state/executions/" + theirs,
                LogCompleteness.COMPLETE, null, 12L, T0));

        assertTrue(records.running(HOST).stream().anyMatch(each -> each.id().equals(own)));
        assertTrue(records.running(HOST).stream().noneMatch(each -> each.id().equals(theirs)), "another installation's running record is not this one's to account for");
        assertTrue(records.running(OTHER_HOST).stream().anyMatch(each -> each.id().equals(theirs)));
        assertEquals(OTHER_HOST, found(theirs).hostId());

        records.finish(own, ExecutionStatus.EXITED, 0, T0.plusSeconds(1), LogCompleteness.COMPLETE, 5L);
        records.finish(theirs, ExecutionStatus.EXITED, 0, T0.plusSeconds(1), LogCompleteness.COMPLETE, 5L);
        assertTrue(ids(records.logCandidates(HOST, null, 100000)).contains(own));
        assertFalse(ids(records.logCandidates(HOST, null, 100000)).contains(theirs));
        assertTrue(ids(records.logCandidates(OTHER_HOST, null, 100000)).contains(theirs));
    }

    @Test
    void aLostRecordIsClosedWithoutAnEndTimeAndEveryOtherEndHasOne() {
        String lost = id();
        String other = id();
        records.begin(running(lost, RUN, T0, 31));
        records.begin(running(other, RUN, T0, 32));

        assertThrows(IllegalArgumentException.class, () -> records.finish(lost, ExecutionStatus.LOST, null, T0.plusSeconds(1), LogCompleteness.TRUNCATED, null), "when a lost one ended is not known");
        assertThrows(IllegalArgumentException.class, () -> records.finish(other, ExecutionStatus.KILLED, null, null, LogCompleteness.COMPLETE, null), "any other end has its time");
        assertTrue(records.finish(lost, ExecutionStatus.LOST, null, null, LogCompleteness.TRUNCATED, null));

        ExecutionRecord closed = found(lost);
        assertEquals(ExecutionStatus.LOST, closed.status());
        assertNull(closed.endedAt());
        assertFalse(records.running(HOST).stream().anyMatch(each -> each.id().equals(lost)));
        records.finish(other, ExecutionStatus.KILLED, null, T0.plusSeconds(1), LogCompleteness.COMPLETE, null);
    }

    @Test
    void theRecordIsNotWrittenOrClosedFromInsideATransactionOfTheCallerAndTheQuestionAnswersTheSame() {
        TransactionTemplate outer = new TransactionTemplate(context.transactionManager());
        String id = id();
        records.begin(running(id, RUN, T0, 41));

        assertThrows(IllegalTransactionStateException.class, () -> outer.executeWithoutResult(status -> records.begin(running(id(), RUN, T0, 42))));
        assertThrows(IllegalTransactionStateException.class,
                () -> outer.executeWithoutResult(status -> records.finish(id, ExecutionStatus.EXITED, 0, T0.plusSeconds(1), LogCompleteness.COMPLETE, null)));
        assertThrows(IllegalTransactionStateException.class, () -> outer.executeWithoutResult(status -> records.requireOutsideTransaction()));
        assertEquals(ExecutionStatus.RUNNING, found(id).status(), "nothing was changed by the refused call");
        records.requireOutsideTransaction();
        assertTrue(records.finish(id, ExecutionStatus.EXITED, 0, T0.plusSeconds(1), LogCompleteness.COMPLETE, null), "outside a transaction it works");
    }

    @Test
    void theTableHasNoForeignKeyNoColumnForTheOutputAndTheColumnsOfTheDesign() throws SQLException {
        assertEquals(List.of("id", "run_id", "kind", "requested_by", "host_id", "argv", "cwd", "status", "exit_code", "started_at", "ended_at", "aged_at", "log_location", "log_completeness",
                "log_bytes", "log_removed_at", "log_unreachable_at", "log_pending", "pid", "process_started_at"), column("column_name"));
        List<String> types = column("data_type");
        assertTrue(types.stream().noneMatch(type -> type.equals("bytea") || type.equals("oid")), "no column holds bytes or a large object: " + types);
        assertEquals(0, count("SELECT count(*) FROM information_schema.table_constraints WHERE table_schema = 'sennard' AND table_name = 'execution' AND constraint_type = 'FOREIGN KEY'"));
        assertEquals(1, count("SELECT version FROM sennard.module_schema WHERE module = 'execution'"));
    }

    /** The database's own refusals: a row is written as the service does not, and the CHECK says no. */
    @Test
    void theDatabaseRefusesWhatTheStatesDoNotAllow() throws SQLException {
        // aged_at is the end, and the start for a row with none; log_pending is 0 for these rows, which have no log folder
        String ok = "INSERT INTO sennard.execution (id, run_id, kind, requested_by, host_id, argv, cwd, status, exit_code, started_at, ended_at, aged_at, log_completeness, log_bytes, "
                + "log_removed_at, log_unreachable_at, log_pending) VALUES ('%1$s', 'r', '%2$s', 's', 'h', '[]', '/w', '%3$s', %4$s, TIMESTAMP '2026-03-04 05:06:07', %5$s, "
                + "COALESCE(%5$s, TIMESTAMP '2026-03-04 05:06:07'), '%6$s', %7$s, %8$s, %9$s, 0)";
        String ended = "TIMESTAMP '2026-03-04 05:06:08'";
        String at = "TIMESTAMP '2026-03-05 05:06:08'";
        execute(ok.formatted(id(), "OPERATION", "RUNNING", "NULL", "NULL", "COMPLETE", "NULL", "NULL", "NULL"));
        execute(ok.formatted(id(), "OPERATION", "EXITED", "0", ended, "TRUNCATED", "10", "NULL", "NULL"));
        execute(ok.formatted(id(), "OPERATION", "LOST", "NULL", "NULL", "TRUNCATED", "NULL", "NULL", "NULL"));
        execute(ok.formatted(id(), "OPERATION", "KILLED", "NULL", ended, "TRUNCATED", "0", at, "NULL"));
        execute(ok.formatted(id(), "OPERATION", "KILLED", "NULL", ended, "COMPLETE", "NULL", "NULL", at));
        execute(ok.formatted(id(), "OPERATION", "RUNNING", "NULL", "NULL", "COMPLETE", "NULL", "NULL", at));
        for (String bad : List.of(
                ok.formatted(id(), "OPERATION", "RUNNING", "NULL", ended, "COMPLETE", "NULL", "NULL", "NULL"),
                ok.formatted(id(), "OPERATION", "LOST", "NULL", ended, "TRUNCATED", "NULL", "NULL", "NULL"),
                ok.formatted(id(), "OPERATION", "EXITED", "0", "NULL", "COMPLETE", "NULL", "NULL", "NULL"),
                ok.formatted(id(), "OPERATION", "KILLED", "9", ended, "COMPLETE", "NULL", "NULL", "NULL"),
                ok.formatted(id(), "OPERATION", "RUNNING", "1", "NULL", "COMPLETE", "NULL", "NULL", "NULL"),
                ok.formatted(id(), "OPERATION", "DONE", "NULL", ended, "COMPLETE", "NULL", "NULL", "NULL"),
                ok.formatted(id(), "INQUIRY", "RUNNING", "NULL", "NULL", "COMPLETE", "NULL", "NULL", "NULL"),
                ok.formatted(id(), "CHANGE", "RUNNING", "NULL", "NULL", "COMPLETE", "NULL", "NULL", "NULL"),
                ok.formatted(id(), "OPERATION", "RUNNING", "NULL", "NULL", "PARTIAL", "NULL", "NULL", "NULL"),
                ok.formatted(id(), "OPERATION", "KILLED", "NULL", ended, "REMOVED", "NULL", "NULL", "NULL"),
                ok.formatted(id(), "OPERATION", "KILLED", "NULL", ended, "COMPLETE", "-1", "NULL", "NULL"),
                ok.formatted(id(), "OPERATION", "RUNNING", "NULL", "NULL", "COMPLETE", "NULL", at, "NULL"),
                ok.formatted(id(), "OPERATION", "KILLED", "NULL", ended, "COMPLETE", "NULL", at, at))) {
            assertThrows(SQLException.class, () -> execute(bad), bad);
        }
    }

    /** The database's own refusals of the two columns the retention reads by: a row written as the service does not, with a log folder, and the CHECK says no. */
    @Test
    void theDatabaseRefusesAnAgeThatIsNotTheEndOrTheStartAndAPendingFlagThatIsNotWhatTheRowSays() throws SQLException {
        String row = "INSERT INTO sennard.execution (id, run_id, kind, requested_by, host_id, argv, cwd, status, exit_code, started_at, ended_at, aged_at, log_location, log_completeness, "
                + "log_bytes, log_removed_at, log_unreachable_at, log_pending) VALUES ('%1$s', 'r', 'OPERATION', 's', 'h', '[]', '/w', '%2$s', NULL, TIMESTAMP '2026-03-04 05:06:07', %3$s, %4$s, "
                + "'/log', 'COMPLETE', NULL, %5$s, %6$s, %7$s)";
        String started = "TIMESTAMP '2026-03-04 05:06:07'";
        String ended = "TIMESTAMP '2026-03-04 05:06:08'";
        String at = "TIMESTAMP '2026-03-05 05:06:08'";
        // an ended row that has a log folder is pending; a running one, a deleted one and an out of reach one are not; the age is the end, and the start while there is none
        execute(row.formatted(id(), "KILLED", ended, ended, "NULL", "NULL", "1"));
        execute(row.formatted(id(), "RUNNING", "NULL", started, "NULL", "NULL", "0"));
        execute(row.formatted(id(), "LOST", "NULL", started, "NULL", "NULL", "1"));
        execute(row.formatted(id(), "KILLED", ended, ended, at, "NULL", "0"));
        execute(row.formatted(id(), "KILLED", ended, ended, "NULL", at, "0"));
        for (String bad : List.of(
                row.formatted(id(), "KILLED", ended, ended, "NULL", "NULL", "0"),
                row.formatted(id(), "RUNNING", "NULL", started, "NULL", "NULL", "1"),
                row.formatted(id(), "KILLED", ended, ended, at, "NULL", "1"),
                row.formatted(id(), "KILLED", ended, ended, "NULL", at, "1"),
                row.formatted(id(), "KILLED", ended, started, "NULL", "NULL", "1"),
                row.formatted(id(), "RUNNING", "NULL", ended, "NULL", "NULL", "0"),
                row.formatted(id(), "KILLED", ended, "NULL", "NULL", "NULL", "1"),
                row.formatted(id(), "KILLED", ended, ended, "NULL", "NULL", "NULL"),
                row.formatted(id(), "KILLED", ended, ended, "NULL", "NULL", "2"))) {
            assertThrows(SQLException.class, () -> execute(bad), bad);
        }
    }

    @Test
    void theAgeAndThePendingFlagAreWhatTheStatesMakeThemAtEveryStep() throws SQLException {
        String running = id();
        String lost = id();
        String noFolder = id();
        String closed = id();
        String removed = id();
        String unreachable = id();
        records.begin(running(running, RUN, T0, 61));
        records.begin(running(lost, RUN, T0.plusSeconds(1), 62));
        records.begin(new NewExecution(noFolder, RUN, ExecutionKind.OPERATION, SESSION, HOST, List.of("x"), "/w", ExecutionStatus.NOT_STARTED, null, T0.plusSeconds(2), T0.plusSeconds(3), null,
                LogCompleteness.COMPLETE, null, null, null));
        records.begin(running(closed, RUN, T0.plusSeconds(4), 63));
        records.begin(running(removed, RUN, T0.plusSeconds(5), 64));
        records.begin(running(unreachable, RUN, T0.plusSeconds(6), 65));
        for (String id : List.of(running, lost, closed, removed, unreachable)) {
            assertEquals(1, count("SELECT count(*) FROM sennard.execution WHERE id = '" + id + "' AND aged_at = started_at AND log_pending = 0"), "running: its age is its start, nothing is pending: " + id);
        }
        assertEquals(1, count("SELECT count(*) FROM sennard.execution WHERE id = '" + noFolder + "' AND aged_at = ended_at AND log_pending = 0"), "no folder: nothing is pending");

        assertTrue(records.finish(lost, ExecutionStatus.LOST, null, null, LogCompleteness.TRUNCATED, null));
        assertTrue(records.finish(closed, ExecutionStatus.EXITED, 0, T0.plusSeconds(10), LogCompleteness.COMPLETE, 1L));
        assertTrue(records.finish(removed, ExecutionStatus.EXITED, 0, T0.plusSeconds(11), LogCompleteness.COMPLETE, 1L));
        assertTrue(records.finish(unreachable, ExecutionStatus.EXITED, 0, T0.plusSeconds(12), LogCompleteness.COMPLETE, 1L));
        assertEquals(1, count("SELECT count(*) FROM sennard.execution WHERE id = '" + lost + "' AND ended_at IS NULL AND aged_at = started_at AND log_pending = 1"), "a lost one has no end: the age stays its start");
        for (String id : List.of(closed, removed, unreachable)) {
            assertEquals(1, count("SELECT count(*) FROM sennard.execution WHERE id = '" + id + "' AND aged_at = ended_at AND log_pending = 1"), "closed: its age is its end, and it is pending: " + id);
        }

        assertTrue(records.logRemoved(removed, T0.plusSeconds(20)));
        assertTrue(records.logUnreachable(unreachable, T0.plusSeconds(20)));
        for (String id : List.of(removed, unreachable)) {
            assertEquals(1, count("SELECT count(*) FROM sennard.execution WHERE id = '" + id + "' AND aged_at = ended_at AND log_pending = 0"), "deleted or out of reach: no longer pending: " + id);
        }
        assertEquals(1, count("SELECT count(*) FROM sennard.execution WHERE id = '" + closed + "' AND log_pending = 1"));
        records.finish(running, ExecutionStatus.KILLED, null, T0.plusSeconds(30), LogCompleteness.COMPLETE, null);
    }

    @Test
    void theRetentionReadsThroughAnIndexThatLeadsWithTheInstallationThePendingFlagTheAgeAndTheId() throws SQLException {
        java.util.Map<String, List<String>> indexes = new java.util.TreeMap<>();
        try (Connection owner = BusinessDb.connect(); ResultSet set = owner.getMetaData().getIndexInfo(null, "sennard", "execution", false, false)) {
            java.util.Map<String, java.util.TreeMap<Short, String>> byPosition = new java.util.TreeMap<>();
            while (set.next()) {
                if (set.getString("INDEX_NAME") != null && set.getString("COLUMN_NAME") != null) {
                    byPosition.computeIfAbsent(set.getString("INDEX_NAME"), name -> new java.util.TreeMap<>()).put(set.getShort("ORDINAL_POSITION"), set.getString("COLUMN_NAME"));
                }
            }
            byPosition.forEach((name, columns) -> indexes.put(name, List.copyOf(columns.values())));
        }
        assertEquals(List.of("host_id", "log_pending", "aged_at", "id"), indexes.get("execution_host_pending_aged"), indexes.toString());
        assertEquals(List.of("host_id", "status", "started_at"), indexes.get("execution_host_status_started"), "the running ones of an installation are still found by their own index");
    }

    @Test
    void aSizeIsNotedForARecordThatHasNoneOnlyAndOnlyWhileItsFilesAreForTheRetentionToJudge() {
        String unnoted = id();
        String noted = id();
        String running = id();
        String removed = id();
        ended(HOST, unnoted, T0, T0.plusSeconds(1), null);
        ended(HOST, noted, T0, T0.plusSeconds(1), 5L);
        records.begin(running(running, RUN, T0, 71));
        ended(HOST, removed, T0, T0.plusSeconds(1), null);
        assertTrue(records.logRemoved(removed, T0.plusSeconds(9)));

        assertTrue(records.logBytesNoted(unnoted, 77L), "a record with no size has the size noted");
        assertEquals(77L, found(unnoted).logBytes());
        assertFalse(records.logBytesNoted(unnoted, 99L), "noted once: the size that is there stays");
        assertEquals(77L, found(unnoted).logBytes());
        assertFalse(records.logBytesNoted(noted, 99L), "a record that has a size is left as it is");
        assertEquals(5L, found(noted).logBytes());
        assertFalse(records.logBytesNoted(running, 99L), "a running record is not measured yet");
        assertNull(found(running).logBytes());
        assertFalse(records.logBytesNoted(removed, 99L), "the files of a record that was deleted are not there to be measured");
        assertNull(found(removed).logBytes());
        assertFalse(records.logBytesNoted(id(), 1L), "a record that does not exist is not noted");
        records.finish(running, ExecutionStatus.KILLED, null, T0.plusSeconds(2), LogCompleteness.COMPLETE, null);
    }

    @Test
    void theLogCandidatesComeOldestFirstByTheEndAPageAtATimeAndOnlyTheOnesWhoseLogFilesAreStillThere() {
        String host = host();
        String a = id();
        String b = id();
        String c = id();
        String lost = id();
        // the same time: ordered by id
        String twinFirst = "00000000-0000-4000-8000-000000000001";
        String twinSecond = "00000000-0000-4000-8000-000000000002";
        ended(host, c, T0.minusSeconds(5000), T0.plusSeconds(300), 3L);
        ended(host, a, T0.minusSeconds(4000), T0.plusSeconds(100), 1L);
        ended(host, b, T0.minusSeconds(3000), T0.plusSeconds(200), 2L);
        // a LOST record has no end: its age counts from its start, which puts it before them all
        ended(host, lost, T0.minusSeconds(9000), null, null);
        ended(host, twinSecond, T0, T0.plusSeconds(400), 4L);
        ended(host, twinFirst, T0, T0.plusSeconds(400), 5L);
        // not candidates: running, never had a log folder, deleted, out of reach
        String running = id();
        records.begin(new NewExecution(running, RUN, ExecutionKind.OPERATION, SESSION, host, List.of("java"), "/w", ExecutionStatus.RUNNING, null, T0, null, "state/executions/" + running,
                LogCompleteness.COMPLETE, null, null, null));
        String noFolder = id();
        records.begin(new NewExecution(noFolder, RUN, ExecutionKind.OPERATION, SESSION, host, List.of("java"), "/w", ExecutionStatus.NOT_STARTED, null, T0, T0, null, LogCompleteness.COMPLETE, null, null,
                null));
        String deleted = id();
        String outOfReach = id();
        ended(host, deleted, T0, T0.plusSeconds(10), 9L);
        ended(host, outOfReach, T0, T0.plusSeconds(10), 9L);
        assertTrue(records.logRemoved(deleted, T0.plusSeconds(1000)));
        assertTrue(records.logUnreachable(outOfReach, T0.plusSeconds(1000)));

        List<Candidate> all = records.logCandidates(host, null, 100);
        assertEquals(List.of(lost, a, b, c, twinFirst, twinSecond), ids(all));
        assertNull(all.getFirst().bytes(), "no size was noted for the lost one");
        assertEquals(T0.minusSeconds(9000), all.getFirst().agedAt(), "its age counts from its start");
        assertEquals(T0.plusSeconds(100), all.get(1).agedAt());
        assertEquals("state/executions/" + a, all.get(1).location());
        assertEquals(1L, all.get(1).bytes());

        List<Candidate> first = records.logCandidates(host, null, 2);
        assertEquals(List.of(lost, a), ids(first));
        List<Candidate> second = records.logCandidates(host, Position.of(first.getLast()), 2);
        assertEquals(List.of(b, c), ids(second));
        List<Candidate> third = records.logCandidates(host, Position.of(second.getLast()), 2);
        assertEquals(List.of(twinFirst, twinSecond), ids(third));
        assertEquals(List.of(), records.logCandidates(host, Position.of(third.getLast()), 2));
        // a page that ends between two records of the same time goes on with the second of them
        assertEquals(List.of(twinSecond), ids(records.logCandidates(host, Position.of(all.get(4)), 5)));
    }

    @Test
    void aPageThatEndsAmongRecordsOfTheSameAgeGoesOnWithTheRestOfThemWithoutOneTwiceOrOneMissed() {
        String host = host();
        String before = "00000000-0000-4000-8000-0000000000a0";
        String after = "00000000-0000-4000-8000-0000000000f0";
        List<String> same = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            same.add(String.format("00000000-0000-4000-8000-00000000000%d", index));
        }
        ended(host, after, T0, T0.plusSeconds(900), 1L);
        for (String id : same.reversed()) {
            ended(host, id, T0, T0.plusSeconds(500), 1L);
        }
        ended(host, before, T0, T0.plusSeconds(100), 1L);
        List<String> expected = new ArrayList<>();
        expected.add(before);
        expected.addAll(same);
        expected.add(after);

        // pages of two end between records of the same age (after the first of them, after the third) and the position after each is exact
        List<String> walked = new ArrayList<>();
        Position position = null;
        List<Candidate> page;
        do {
            page = records.logCandidates(host, position, 2);
            walked.addAll(ids(page));
            if (!page.isEmpty()) {
                position = Position.of(page.getLast());
            }
        } while (page.size() == 2);
        assertEquals(expected, walked, "every one once, by age and then by id, whichever way the pages fall");
        assertEquals(List.of(same.get(0), same.get(1)), ids(records.logCandidates(host, Position.of(records.logCandidates(host, null, 1).getFirst()), 2)), "a page of one that ends before the group goes on into it");
        assertEquals(List.of(same.get(2), same.get(3), same.get(4), after), ids(records.logCandidates(host, new Position(T0.plusSeconds(500), same.get(1)), 10)), "the position inside the group is exact");
    }

    @Test
    void aRunningRecordIsNotNotedOutOfReachOrDeletedAndClosingOneThatWasMarkedAnywayKeepsTheTableSatisfied() throws SQLException {
        String running = id();
        records.begin(running(running, RUN, T0, 81));

        assertFalse(records.logUnreachable(running, T0.plusSeconds(9)), "a running record is not noted out of reach: its closing would find it noted");
        assertFalse(records.logRemoved(running, T0.plusSeconds(9)));
        assertNull(found(running).logUnreachableAt());
        assertTrue(records.finish(running, ExecutionStatus.KILLED, null, T0.plusSeconds(10), LogCompleteness.COMPLETE, 5L));
        assertEquals(1, count("SELECT count(*) FROM sennard.execution WHERE id = '" + running + "' AND log_pending = 1 AND log_unreachable_at IS NULL"), "closed with a folder and nothing noted: pending");

        // the closing itself, on a row that has been marked while running (the table allows it: a running row is never pending): it goes on being not pending, and the table accepts the row
        String marked = id();
        records.begin(running(marked, RUN, T0, 82));
        execute("UPDATE sennard.execution SET log_unreachable_at = TIMESTAMP '2026-03-04 05:06:30' WHERE id = '" + marked + "'");
        assertTrue(records.finish(marked, ExecutionStatus.KILLED, null, T0.plusSeconds(10), LogCompleteness.COMPLETE, 5L), "the closing is not refused by the table");
        assertEquals(1, count("SELECT count(*) FROM sennard.execution WHERE id = '" + marked + "' AND log_pending = 0 AND log_unreachable_at IS NOT NULL AND status = 'KILLED'"),
                "the files are noted out of reach: not pending, as the table says");
    }

    @Test
    void theSumIsTheBytesNotedWhenTheCandidatesEndedAndNoOtherRecordCountsInIt() {
        String host = host();
        ended(host, id(), T0, T0.plusSeconds(1), 100L);
        ended(host, id(), T0, T0.plusSeconds(2), 20L);
        ended(host, id(), T0, T0.plusSeconds(3), null);
        String deleted = id();
        String outOfReach = id();
        ended(host, deleted, T0, T0.plusSeconds(4), 1000L);
        ended(host, outOfReach, T0, T0.plusSeconds(5), 1000L);
        records.logRemoved(deleted, T0.plusSeconds(10));
        records.logUnreachable(outOfReach, T0.plusSeconds(10));
        String running = id();
        records.begin(new NewExecution(running, RUN, ExecutionKind.OPERATION, SESSION, host, List.of("java"), "/w", ExecutionStatus.RUNNING, null, T0, null, "state/executions/" + running,
                LogCompleteness.COMPLETE, 5000L, null, null));
        ended(host(), id(), T0, T0.plusSeconds(1), 7777L);

        assertEquals(120L, records.loggedBytes(host));
        assertEquals(0L, records.loggedBytes(host()), "an installation with nothing sums to 0");
    }

    @Test
    void whichOfManyIdsAreRecordsWhoseLogFolderIsStillThereTheRunningOnesToo() {
        String host = host();
        String endedOne = id();
        String runningOne = id();
        String deleted = id();
        String outOfReach = id();
        String otherHost = id();
        String noFolder = id();
        ended(host, endedOne, T0, T0.plusSeconds(1), 1L);
        records.begin(new NewExecution(runningOne, RUN, ExecutionKind.OPERATION, SESSION, host, List.of("java"), "/w", ExecutionStatus.RUNNING, null, T0, null, "state/executions/" + runningOne,
                LogCompleteness.COMPLETE, null, null, null));
        ended(host, deleted, T0, T0.plusSeconds(1), 1L);
        ended(host, outOfReach, T0, T0.plusSeconds(1), 1L);
        records.logRemoved(deleted, T0.plusSeconds(5));
        records.logUnreachable(outOfReach, T0.plusSeconds(5));
        ended(host(), otherHost, T0, T0.plusSeconds(1), 1L);
        records.begin(new NewExecution(noFolder, RUN, ExecutionKind.OPERATION, SESSION, host, List.of("java"), "/w", ExecutionStatus.NOT_STARTED, null, T0, T0, null, LogCompleteness.COMPLETE, null,
                null, null));

        // more ids than one statement is given (500): the ones that count are on both sides of the borders
        List<String> asked = new ArrayList<>();
        for (int index = 0; index < 1203; index++) {
            asked.add(String.format("%08d-0000-4000-8000-%012d", 7, index));
        }
        asked.add(0, endedOne);
        asked.add(600, runningOne);
        asked.add(1100, deleted);
        asked.add(1150, outOfReach);
        asked.add(1180, otherHost);
        asked.add(noFolder);
        asked.add(1000, id());

        assertEquals(Set.of(endedOne, runningOne), records.withLogFolders(host, asked));
        assertEquals(Set.of(), records.withLogFolders(host, List.of()));
    }

    @Test
    void aDeletedLogHasItsTimeAndTheRecordKeepsItsCompletenessAndAnOutOfReachLogIsNotedOnceAndNeverWithADeletedOne() {
        String truncated = id();
        String running = id();
        String reach = id();
        records.begin(running(truncated, RUN, T0, 51));
        records.finish(truncated, ExecutionStatus.KILLED, null, T0.plusSeconds(1), LogCompleteness.TRUNCATED, 12L);
        records.begin(running(running, RUN, T0, 52));
        records.begin(running(reach, RUN, T0, 53));
        records.finish(reach, ExecutionStatus.KILLED, null, T0.plusSeconds(1), LogCompleteness.COMPLETE, 3L);

        assertFalse(records.logRemoved(running, T0.plusSeconds(9)), "a running record is not touched");
        assertNull(found(running).logRemovedAt());

        Instant removedAt = T0.plusSeconds(9);
        assertTrue(records.logRemoved(truncated, removedAt));
        ExecutionRecord removed = found(truncated);
        assertEquals(removedAt, removed.logRemovedAt());
        assertEquals(LogCompleteness.TRUNCATED, removed.logCompleteness(), "what the files held when the execution ended is not overwritten by what became of them");
        assertEquals(ExecutionStatus.KILLED, removed.status(), "the rest of the record stays");
        assertEquals("state/executions/" + truncated, removed.logLocation());
        assertEquals(12L, removed.logBytes());
        assertFalse(records.logRemoved(truncated, T0.plusSeconds(99)), "noted once");
        assertFalse(records.logUnreachable(truncated, T0.plusSeconds(99)), "a deleted one is not also out of reach");
        assertEquals(removedAt, found(truncated).logRemovedAt());
        assertNull(found(truncated).logUnreachableAt());

        Instant unreachableAt = T0.plusSeconds(11);
        assertTrue(records.logUnreachable(reach, unreachableAt));
        assertEquals(unreachableAt, found(reach).logUnreachableAt());
        assertEquals(LogCompleteness.COMPLETE, found(reach).logCompleteness());
        assertFalse(records.logUnreachable(reach, T0.plusSeconds(99)), "noted once");
        assertFalse(records.logRemoved(reach, T0.plusSeconds(99)), "an out-of-reach one is not also deleted");
        assertNull(found(reach).logRemovedAt());
        assertFalse(records.logUnreachable(id(), T0), "a record that does not exist is not noted");
        records.finish(running, ExecutionStatus.KILLED, null, T0.plusSeconds(2), LogCompleteness.COMPLETE, null);
    }

    private static void execute(String statement) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement()) {
            s.execute(statement);
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement(); ResultSet set = s.executeQuery(sql)) {
            set.next();
            return set.getLong(1);
        }
    }

    /** One column of information_schema.columns for the table, in the order of the table. */
    private static List<String> column(String name) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement();
                ResultSet set = s.executeQuery("SELECT " + name + " FROM information_schema.columns WHERE table_schema = 'sennard' AND table_name = 'execution' ORDER BY ordinal_position")) {
            while (set.next()) {
                values.add(set.getString(1));
            }
        }
        return values;
    }
}
