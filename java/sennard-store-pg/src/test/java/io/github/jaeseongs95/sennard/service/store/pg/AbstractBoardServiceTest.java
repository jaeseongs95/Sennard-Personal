// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.sennard.app.board.BoardService;
import io.github.jaeseongs95.sennard.app.board.BoardSession;
import io.github.jaeseongs95.sennard.app.board.BoardSessionRepository;
import io.github.jaeseongs95.sennard.domain.board.BoardEntry;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;

/**
 * The session board (sennard-app: {@link BoardService}, {@link BoardSessionRepository}) on the real database (PostgreSQL), through {@link RewrittenAreasContext}. It replaces the
 * board cases of the old {@code BoardStore} tests (AbstractModuleStoresTest, which held the role and cwd, the retention and the parallel writers on the
 * advisory lock) with the same meanings on the new mechanism: the primary key and the database's own ordering of writers (one UPDATE statement per change, no retry).
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * a  role and cwd kept     recordInput(cwd, prompt) then recordInput(no cwd, no prompt) then setSummary(role) then setSummary(no role)
 *                          -> cwd and the prompt time stay, the role stays, the summary is the last; read back through readEntry and the row
 * c  writers of one row    a first write makes the row; N threads recordInput the same session at once -> every call succeeds (the change is one UPDATE statement, no retry),
 *                          one row, version N (each of the N writers' changes counts it up once: no write lost)
 * c2 first writes, race    the test holds a first write's transaction open and a second write of the same session comes in; the database says the second one is blocked by the
 *                          holder; the holder commits -> the second ends in the translated ConflictException DUPLICATE and wrote nothing; one row is left (LockWaits)
 * e  invalid input         a two-line summary -> InvalidInputException SUMMARY_INVALID and the stored row unchanged; an over-long role -> ROLE_INVALID;
 *                          an empty or null host or session id -> InvalidInputException VALIDATION_FAILED (the Jakarta Validation of the @Validated service)
 * f  prune                 a user prompt deletes the rows older than the retention in the same transaction as its own write, an input that is no prompt deletes none; the boundary
 *                          exactly one retention old is kept (the clock is the test's)
 * g  Boot default path     the same cases run on a plain DataSource bean and Boot's transaction manager only (BoardServiceBootDefaultTest): no store transaction manager, no advisory lock
 * h  optimistic lock       two transactions read one row, both save -> the second gets OptimisticLockingFailureException (repository level)
 * i  errors and wording    ErrorMessagesTest (sennard-app): every code has its catalog line in English and Korean, the categories match, the names do not collide
 * s  schema facts          no row-level security on the board table, key (host, session_id), version NOT NULL, the times are TIMESTAMP without a zone
 * </pre>
 *
 * The cases (a) to (f), (h) and (s) are written here and run by both subclasses; each subclass states how its context is connected. The class runs on the
 * database the test run names ({@link BusinessDbTest}).
 */
@BusinessDbTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractBoardServiceTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-02T00:00:00Z");
    private static final int WRITERS = 8;

    private RewrittenAreasContext context;

    /** How the subclass connects the application layer to the database. */
    abstract RewrittenAreasContext open(Instant start);

    @BeforeAll
    void start() throws Exception {
        // the schemas are migrated anew by @BusinessDbTest before this method; the one account of the shared database is what the application connects as
        context = open(T0);
    }

    @AfterAll
    void stop() {
        if (context != null) {
            context.close();
        }
    }

    final RewrittenAreasContext context() {
        return context;
    }


    // ------------------------------------------------------------------------------------------------------------------ fixtures

    private long ownerLong(String sql) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement(); ResultSet rows = s.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private BoardService board() {
        return context.bean(BoardService.class);
    }

    /** The summary through the entry of the write tool, which takes the operation: a key of its own for each call, in the scope of the session (the call with no session is refused before the key matters). */
    private BoardService.SummaryResult setSummaryKeyed(String host, String sessionId, String summary, String role) {
        return board().setSummary(host, sessionId, summary, role, Operations.fresh("update_session_status", sessionId == null ? "as1_BOARDSERVICETEST" : sessionId));
    }

    private BoardEntry entry(String host, String sessionId) {
        return board().readEntry(host, sessionId).orElse(null);
    }

    private void at(Instant instant) {
        context.clock().set(instant);
    }

    private static Instant stamp(Instant instant) {
        return instant.truncatedTo(java.time.temporal.ChronoUnit.MILLIS); // what the service stores and returns
    }

    // ------------------------------------------------------------------------------------------------------------------ a

    @Test
    void theBoardKeepsTheRoleWhenAnUpdateOmitsItAndTheCwdWhenInputHasNone() throws Exception {
        at(T0);
        board().recordInput("claude", "s1", "/work", true);
        at(T1);
        board().recordInput("claude", "s1", null, false);
        BoardService.SummaryResult first = setSummaryKeyed("claude", "s1", " line ", "lead");
        assertEquals(new BoardService.SummaryResult("line", stamp(T1), "lead"), first, "trimmed, stamped with the clock, the role now in force");
        BoardService.SummaryResult second = setSummaryKeyed("claude", "s1", "next", null);
        assertEquals("lead", second.role(), "an omitted role keeps the one written before");

        BoardEntry entry = entry("claude", "s1");
        assertEquals("/work", entry.cwd(), "an input with no cwd keeps the cwd");
        assertEquals("next", entry.summary());
        assertEquals("lead", entry.role());
        assertEquals(stamp(T0), entry.lastPromptAt(), "only a prompt moves the prompt time");
        assertEquals(stamp(T1), entry.summaryAt());
        assertEquals(Boolean.FALSE, entry.summaryStale(), "the summary is newer than the last prompt");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.board_sessions"));
    }

    // ------------------------------------------------------------------------------------------------------------------ the operationKey

    /**
     * AGS3-D-0267: two first requests of one caller with one key that overlap, on a session whose row exists. The first one writes the row and its receipt and holds its transaction
     * open; the second one's UPDATE waits on the row (the database says so: no clock decides that they overlapped). After the commit the second one's work is done and its receipt meets the
     * first one's key: OPERATION_KEY_IN_PROGRESS, retryable, nothing written; the same request sent again is answered with the first one's result, and one receipt exists.
     */
    @Test
    void twoOverlappingFirstRequestsOfOneKeyLeaveOneReceiptAndTheSecondIsToldToSendItAgain() throws Exception {
        at(T0);
        setSummaryKeyed("claude", "keyed", "before", null); // the row exists, so both requests change it and meet on its row lock
        io.github.jaeseongs95.sennard.app.operation.OperationKey operation = Operations.fresh("update_session_status", "keyed");
        TransactionTemplate holder = new TransactionTemplate(context.transactionManager());
        CountDownLatch holderWorked = new CountDownLatch(1);
        CountDownLatch holderMayCommit = new CountDownLatch(1);
        AtomicLong holderBackend = new AtomicLong();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<BoardService.SummaryResult> first = pool.submit(() -> holder.execute(status -> {
                holderBackend.set(LockWaits.backendOfThisTransaction(context.dataSource()));
                BoardService.SummaryResult written = board().setSummary("claude", "keyed", "the line", null, operation);
                holderWorked.countDown();
                try {
                    assertTrue(holderMayCommit.await(120, TimeUnit.SECONDS), "the holder was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return written;
            }));
            assertTrue(holderWorked.await(120, TimeUnit.SECONDS), "the holder did not write");
            Future<BoardService.SummaryResult> second = pool.submit(() -> board().setSummary("claude", "keyed", "the line", null, operation));
            LockWaits.awaitWaiterBlockedBy(holderBackend.get(), Duration.ofSeconds(120), "the second request did not come to wait for the row the first one holds");
            assertFalse(second.isDone());
            holderMayCommit.countDown();
            BoardService.SummaryResult written = first.get(120, TimeUnit.SECONDS);
            ExecutionException lost = assertThrows(ExecutionException.class, () -> second.get(120, TimeUnit.SECONDS));
            ConflictException conflict = (ConflictException) lost.getCause();
            assertEquals(ConflictCode.OPERATION_KEY_IN_PROGRESS, conflict.code());
            assertEquals("OPERATION_IN_PROGRESS", conflict.details().get("reason"));
            assertEquals(written, board().setSummary("claude", "keyed", "the line", null, operation), "the same request sent again is answered with the first one's result");
        }
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.operation_receipt WHERE tool = 'update_session_status' AND operation_key = '" + operation.key() + "'"));
    }

    // ------------------------------------------------------------------------------------------------------------------ c

    @Test
    void sameExistingSessionWrittenByManyThreadsSucceedsEverywhereAndLosesNoWrite() throws Exception {
        at(T0);
        board().recordInput("claude", "racing", "/work/first", true); // the first write of the session: version 0
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(WRITERS)) {
            List<Future<Void>> writes = new ArrayList<>();
            for (int writer = 0; writer < WRITERS; writer++) {
                String cwd = "/work/" + writer;
                writes.add(pool.submit(() -> {
                    go.await();
                    board().recordInput("claude", "racing", cwd, true);
                    return null;
                }));
            }
            go.countDown();
            for (Future<Void> write : writes) {
                write.get(60, TimeUnit.SECONDS); // the change of a row is one UPDATE statement: none fails, none is run again
            }
        }
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.board_sessions"));
        assertEquals(WRITERS, ownerLong("SELECT version FROM sennard.board_sessions"), "the first insert has version 0, and every writer's change counts the version up once: no write was lost");
        assertTrue(entry("claude", "racing").cwd().startsWith("/work/"));
    }

    /**
     * Two first writes of one session at the same moment: the test holds the first one's transaction open (it has written the row and not committed) and a second call of the
     * service comes in. The database decides where the second one meets the first one's row, and the test waits until the database itself says the second one is blocked by the
     * holder. The second call's UPDATE sees no row (the holder's row is not committed), its INSERT waits on the primary key and, when the holder commits, fails; the
     * service lets the translated duplicate-key {@code ConflictException} through and writes nothing. One row is left and nothing is caught to go on or run again.
     */
    @Test
    void twoFirstWritesOfOneSessionAtTheSameMomentLeaveOneRowAndTheLaterEndsInTheTranslatedUniqueKeyError() throws Exception {
        at(T0);
        BoardSessionRepository sessions = context.bean(BoardSessionRepository.class);
        TransactionTemplate holder = new TransactionTemplate(context.transactionManager());
        CountDownLatch holderWroteTheRow = new CountDownLatch(1);
        CountDownLatch holderMayCommit = new CountDownLatch(1);
        AtomicLong holderBackend = new AtomicLong(); // the database's identifier of the holder's connection, taken inside its transaction (a wait of another connection does not count)
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> first = pool.submit(() -> holder.executeWithoutResult(status -> {
                holderBackend.set(LockWaits.backendOfThisTransaction(context.dataSource()));
                sessions.insert("claude", "racing", "/work/holder", T0, null, null, null, T0);
                holderWroteTheRow.countDown();
                hold(holderMayCommit);
            }));
            await(holderWroteTheRow);
            Future<?> second = pool.submit(() -> board().recordInput("claude", "racing", "/work/second", true));
            LockWaits.awaitWaiterBlockedBy(holderBackend.get(), Duration.ofSeconds(120), "the second write did not come to wait for the holder's row");
            assertFalse(second.isDone(), "the second write waits for the first one's commit");
            holderMayCommit.countDown();
            first.get(120, TimeUnit.SECONDS);
            ExecutionException lost = assertThrows(ExecutionException.class, () -> second.get(120, TimeUnit.SECONDS));
            ConflictException duplicate = assertInstanceOf(ConflictException.class, lost.getCause(), "the service lets the translated unique-key error through");
            assertEquals(ConflictCode.DUPLICATE, duplicate.code());
            assertEquals(0, ownerLong("SELECT version FROM sennard.board_sessions"), "the later write wrote nothing");
            assertEquals("/work/holder", entry("claude", "racing").cwd());
        }
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.board_sessions"));
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(120, TimeUnit.SECONDS), "a latch of the test was not released");
    }

    /** The holder's side of a latch, inside the holder's transaction (a callback that throws no checked exception). */
    private static void hold(CountDownLatch latch) {
        try {
            await(latch);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while holding the transaction open", interrupted);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ e

    @Test
    void invalidInputIsRefusedWithItsCodeAndStoresNothing() throws Exception {
        at(T0);
        setSummaryKeyed("claude", "s1", "kept", "lead");

        InvalidInputException twoLines = assertThrows(InvalidInputException.class, () -> setSummaryKeyed("claude", "s1", "two\nlines", null));
        assertEquals(InputCode.SUMMARY_INVALID, twoLines.code());
        assertEquals(200, twoLines.arguments().get("maxLength"), "the limit the message tells is the setting's");
        InvalidInputException longRole = assertThrows(InvalidInputException.class, () -> setSummaryKeyed("claude", "s1", "ok", "r".repeat(121)));
        assertEquals(InputCode.ROLE_INVALID, longRole.code());
        assertEquals("kept", entry("claude", "s1").summary(), "a refused call changed nothing");
        assertEquals("lead", entry("claude", "s1").role());

        InvalidInputException emptyHost = assertThrows(InvalidInputException.class, () -> board().recordInput("", "s1", null, false));
        assertEquals(InputCode.VALIDATION_FAILED, emptyHost.code());
        assertTrue(emptyHost.details().containsKey("violations"), "the fields that failed are listed: " + emptyHost.details());
        InvalidInputException nullSession = assertThrows(InvalidInputException.class, () -> setSummaryKeyed("claude", null, "x", null));
        assertEquals(InputCode.VALIDATION_FAILED, nullSession.code());
    }

    // ------------------------------------------------------------------------------------------------------------------ f

    @Test
    void theBoardForgetsASessionAfterTheRetentionNotBefore() throws Exception {
        at(T0);
        setSummaryKeyed("claude", "older", "line", null);
        at(T1);
        setSummaryKeyed("claude", "edge", "line", null);
        at(Instant.parse("2026-01-09T00:00:00Z")); // the retention is seven days: the cut is 2026-01-02T00:00:00.000Z
        board().recordInput("claude", "current", "/work", false);
        assertEquals("line", entry("claude", "older").summary(), "an input that is no prompt prunes nothing");
        board().recordInput("claude", "current", "/work", true);
        assertNull(entry("claude", "older"), "eight days");
        assertEquals("line", entry("claude", "edge").summary(), "exactly seven days");
        assertEquals(2, ownerLong("SELECT count(*) FROM sennard.board_sessions"), "the edge and the current session are left");
    }

    // ------------------------------------------------------------------------------------------------------------------ h

    @Test
    void twoTransactionsThatReadOneRowAndBothSaveLeaveTheSecondWithAnOptimisticLockFailure() throws Exception {
        at(T0);
        board().recordInput("claude", "s1", null, false); // the first write: version 0
        BoardSessionRepository sessions = context.bean(BoardSessionRepository.class);
        PlatformTransactionManager manager = context.transactionManager();
        TransactionTemplate outer = new TransactionTemplate(manager);
        TransactionTemplate inner = new TransactionTemplate(manager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); // a second connection, a second transaction, on the same thread

        assertThrows(OptimisticLockingFailureException.class, () -> outer.execute(status -> {
            BoardSession read = sessions.findByKeyHostAndKeySessionId("claude", "s1").orElseThrow();
            assertEquals(0L, read.version());
            BoardSession won = inner.execute(innerStatus -> sessions.save(sessions.findByKeyHostAndKeySessionId("claude", "s1").orElseThrow().summarized("first", T0, null)));
            assertEquals(1L, won.version(), "the winner's save raised the version");
            return sessions.save(read.summarized("second", T0, null));
        }));
        assertEquals("first", entry("claude", "s1").summary(), "the loser's change was not written over the winner's");
    }

    // ------------------------------------------------------------------------------------------------------------------ s

    @Test
    void theBoardTableHasNoPolicyIsKeyedByHostAndSessionAndHasAVersion() throws Exception {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement()) {
            try (ResultSet rows = s.executeQuery("SELECT c.relrowsecurity, (SELECT count(*) FROM pg_policy p WHERE p.polrelid = c.oid) FROM pg_class c "
                    + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'sennard' AND c.relname = 'board_sessions'")) {
                assertTrue(rows.next());
                assertFalse(rows.getBoolean(1), "no row-level security");
                assertEquals(0, rows.getInt(2));
            }
            try (ResultSet rows = s.executeQuery("SELECT (SELECT count(*) FROM information_schema.routines WHERE routine_schema = 'sennard'), "
                    + "(SELECT count(*) FROM information_schema.triggers WHERE trigger_schema = 'sennard')")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1), "no function or procedure: the board's rules are the service's");
                assertEquals(0, rows.getInt(2), "no trigger");
            }
            List<String> key = new ArrayList<>();
            try (ResultSet rows = s.executeQuery("SELECT k.column_name FROM information_schema.key_column_usage k JOIN information_schema.table_constraints t "
                    + "ON t.constraint_schema = k.constraint_schema AND t.constraint_name = k.constraint_name AND t.table_schema = k.table_schema AND t.table_name = k.table_name "
                    + "WHERE t.constraint_type = 'PRIMARY KEY' AND k.table_schema = 'sennard' AND k.table_name = 'board_sessions' ORDER BY k.ordinal_position")) {
                while (rows.next()) {
                    key.add(rows.getString(1));
                }
            }
            assertEquals(List.of("host", "session_id"), key);
            try (ResultSet rows = s.executeQuery("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'sennard' AND table_name = 'board_sessions' AND column_name = 'version'")) {
                assertTrue(rows.next());
                assertEquals("NO", rows.getString(1));
            }
            // the standard's TIMESTAMP without a zone is PostgreSQL's 'timestamp without time zone'
            try (ResultSet rows = s.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sennard' AND table_name = 'board_sessions' "
                    + "AND column_name IN ('summary_at', 'last_prompt_at', 'seen_at') AND data_type = 'timestamp without time zone'")) {
                assertTrue(rows.next());
                assertEquals(3, rows.getInt(1), "the times are TIMESTAMP without a zone: the conversion is Java's");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ t

    /**
     * The clock's UTC instant is what the table holds whatever zone the JVM is in: the column has no zone and the application's converter
     * ({@code UtcTimestampColumns}) hands the driver the UTC fields of the instant, so neither the driver nor the database converts with the JVM's zone.
     */
    @Test
    void aTimeIsStoredAsTheUtcInstantWhateverZoneTheJvmIsIn() throws Exception {
        java.util.TimeZone before = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"));
            Instant at = Instant.parse("2026-03-04T05:06:07.089Z");
            at(at);
            board().recordInput("claude", "tz", "/w", true);
            assertEquals(at, entry("claude", "tz").lastPromptAt(), "the instant comes back as it went in");
            try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement();
                    ResultSet rows = s.executeQuery("SELECT CAST(seen_at AS VARCHAR(40)) FROM sennard.board_sessions")) {
                assertTrue(rows.next());
                assertEquals("2026-03-04 05:06:07.089", rows.getString(1), "the stored value is the UTC wall time, not Seoul's (14:06)");
            }
        } finally {
            java.util.TimeZone.setDefault(before);
        }
    }
}
