// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.zaxxer.hikari.HikariDataSource;

import io.github.jaeseongs95.sennard.app.RandomConfiguration;
import io.github.jaeseongs95.sennard.service.migration.MigrationScript;
import io.github.jaeseongs95.sennard.service.secret.TestPrivateStorage;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.modules.JevUsageService;

/**
 * The store and schema tests on the database the test run names ({@link BusinessDb}: PostgreSQL, one account, the one the product connects as). There is one
 * server kind, so the embedded and the external server are not tested apart. The database has no roles, grants, row level security or functions of
 * its own, so none of these is tested here: the tests are the migrations, the store port (transactions, savepoints, advisory locks, timeouts, the
 * pool's limit), the encoding and the start-up of the store beans.
 */
@BusinessDbTest
class StoreClusterTest {
    /** A row of the simplest business table ({@code sennard.background_job}) for the tests that watch what a transaction keeps or undoes. */
    private static final String INSERT_JOB = "INSERT INTO sennard.background_job (job_id, kind, state, input_json, created_at) "
            + "VALUES (?, 'cluster-test', 'QUEUED', '{}', TIMESTAMP '2026-11-13 00:00:00')";
    /** Appends to the row's {@code error_code}: what each writer did to one row stays readable in the text. */
    private static final String APPEND_TO_JOB = "UPDATE sennard.background_job SET error_code = CONCAT(COALESCE(error_code, ''), ?) WHERE job_id = ?";

    /** Derived from the classpath, so a later script does not change these tests. */
    private static final int EXPAND_SCRIPTS = expandScripts();

    private static int expandScripts() {
        // The scripts are in the module's jar, which a file walk cannot enter: Spring reads a folder of a jar as well as one of the file system.
        try {
            return new org.springframework.core.io.support.PathMatchingResourcePatternResolver().getResources("classpath*:db/expand/**/*.sql").length;
        } catch (java.io.IOException cause) {
            throw new IllegalStateException(cause);
        }
    }

    /** The store on the test JVM's one pool, as a service has one pool. */
    private static TestStores store() {
        return TestStores.of(BusinessDb.pool());
    }

    /** A test that drops and migrates the schemas again starts from no plan of a table that was dropped: the drop replaces the pool's connections ({@link BusinessDb#dropBusinessSchemas()}). */
    private static void dropSchemas() throws SQLException {
        BusinessDb.dropBusinessSchemas();
    }

    /** Inserts one queued job; the transaction that calls it decides whether the row stays. */
    private static QueryResult insertJob(TestStores.Tx tx, UUID id) {
        return tx.query(INSERT_JOB, id);
    }

    /** How many jobs of these tests the database holds (committed ones only: this runs in a transaction of its own). */
    private static long jobs(TestStores store) {
        return ((Number) store.query("SELECT count(*) AS n FROM sennard.background_job WHERE kind = 'cluster-test'").rows().get(0).get("n")).longValue();
    }

    private static long jobsWithId(TestStores store, UUID id) {
        return ((Number) store.query("SELECT count(*) AS n FROM sennard.background_job WHERE job_id = ?", id).rows().get(0).get("n")).longValue();
    }

    private static String errorCodeOf(TestStores store, UUID id) {
        return (String) store.query("SELECT error_code FROM sennard.background_job WHERE job_id = ?", id).rows().get(0).get("error_code");
    }

    /** The SQLSTATE of a statement the database cut at its time limit: PostgreSQL's 57014 (query canceled). */
    private static final String TIMEOUT_SQL_STATE = "57014";

    // ------------------------------------------------------------------------------------------------------- what the database has

    /** The one clock of the service is Java's: no function of the schema reads one (the database has no clock function of its own). */
    @Test
    void theDatabaseHasNoClockFunction() throws Exception {
        try (Connection connection = BusinessDb.connect(); Statement s = connection.createStatement()) {
            try (ResultSet rows = s.executeQuery("SELECT count(*) FROM information_schema.routines WHERE routine_schema = 'sennard'")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getLong(1), "no function in any business schema (the service's one Clock is Java's)");
            }
        }
        StoreException gone = assertThrows(StoreException.class, () -> store().query("SELECT sennard.now_ms() AS t"));
        assertEquals(StoreCode.STORE_FAILURE, gone.code(), "the clock function of the first design is gone");
    }

    /** An exception's text reaches the log file, and the server's DETAIL line quotes row contents. */
    @Test
    void anErrorsTextDoesNotCarryTheServersDetailLine() throws Exception {
        try (Connection connection = BusinessDb.connect(); Statement statement = connection.createStatement()) {
            // The DETAIL line is PostgreSQL's (the product turns the pgjdbc option that puts it into the message off).
            SQLException failure = assertThrows(SQLException.class, () -> statement.execute("DO $$ BEGIN RAISE EXCEPTION 'refused' USING DETAIL = 'row-content'; END $$"));
            assertTrue(failure.getMessage().contains("refused"), failure.getMessage());
            assertFalse(failure.getMessage().contains("row-content"), failure.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------------------ migrations

    @Test
    void v1ToV31ApplyInVersionOrderOnAnEmptyDatabaseAndReRunsChangeNothing() throws Exception {
        dropSchemas();
        SennardDatabaseConfig config = BusinessDb.config();
        Migrations migrations = BusinessDb.migrations();
        assertEquals(EXPAND_SCRIPTS, migrations.expand(config), "every expand script");
        assertEquals(0, migrations.expand(config), "a second run applies nothing");
        assertEquals(0, migrations.expand(config), "a third run still applies nothing");
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement()) {
            try (ResultSet rows = s.executeQuery("SELECT version, outcome FROM sennard.expand_history ORDER BY version")) {
                List<String> versions = new ArrayList<>();
                while (rows.next()) {
                    versions.add(rows.getString(1));
                    assertEquals("SUCCEEDED", rows.getString(2), "expand script " + rows.getString(1));
                }
                // EXPAND_SCRIPTS is counted from the classpath, so a script that went missing would not show there.
                assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16",
                        "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31"),
                        versions, "V1 to V31, in version order");
            }
            try (ResultSet rows = s.executeQuery("SELECT script FROM sennard.expand_history WHERE version = 16")) {
                assertTrue(rows.next(), "the glossary script ran");
                assertEquals("V16__korean_prose_glossary.sql", rows.getString(1));
            }
            try (ResultSet rows = s.executeQuery("SELECT count(*) FROM sennard.expand_history")) {
                rows.next();
                assertEquals(EXPAND_SCRIPTS, rows.getLong(1), "one history row per script file");
            }
        }
    }

    @Test
    void v31UpgradesAReleasedV27DatabaseWithoutChangingWorkflowRows() throws Exception {
        dropSchemas();
        SennardDatabaseConfig config = BusinessDb.config();
        List<MigrationScript> scripts = MigrationScript.load("db/expand");
        List<MigrationScript> throughV27 = scripts.stream().filter(script -> script.version() <= 27).toList();
        List<MigrationScript> throughV26 = throughV27.stream().filter(script -> script.version() <= 26).toList();
        MigrationRunner runner = new MigrationRunner(Clock.systemUTC(), BusinessDb.dataSources());
        assertEquals(26, runner.run(config, MigrationRunner.Phase.EXPAND, throughV26));
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement()) {
            s.execute("INSERT INTO sennard.workflow_runs (run_id, revision, state, receipt_json, created_at, updated_at) "
                    + "VALUES ('before-v27', 7, 'RUNNING', '{\"before\":true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO sennard.convergence_roots (root_id, revision, state, workspace_id, workspace_locator, root_json, created_at, updated_at) "
                    + "VALUES ('before-v27', 3, 'OPEN', 'workspace', '/existing', '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
        assertEquals(1, runner.run(config, MigrationRunner.Phase.EXPAND, throughV27), "apply the released V27");
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement();
                ResultSet rows = s.executeQuery("SELECT to_regclass('sennard.execution')")) {
            assertTrue(rows.next());
            assertNull(rows.getString(1), "execution is absent before V28");
        }
        Migrations migrations = BusinessDb.migrations();
        assertEquals(4, migrations.expand(config), "V28 through V31 are pending");
        assertEquals(0, migrations.expand(config), "the new migrations are applied only once");
        List<String> expectedFiles = List.of("V26__managed_child_identity.sql", "V27__workflow_stop_checks.sql",
                "V28__execution.sql", "V29__session_parent.sql", "V30__observe_session_kind.sql", "V31__board_user_title.sql");
        List<MigrationScript> recentScripts = scripts.stream().filter(script -> script.version() >= 26 && script.version() <= 31).toList();
        assertEquals(expectedFiles, recentScripts.stream().map(MigrationScript::file).toList());
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement()) {
            try (ResultSet rows = s.executeQuery("SELECT version, script, digest, outcome FROM sennard.expand_history WHERE version BETWEEN 26 AND 31 ORDER BY version")) {
                for (int index = 0; index < expectedFiles.size(); index++) {
                    String file = expectedFiles.get(index);
                    assertTrue(rows.next(), file);
                    assertEquals(26L + index, rows.getLong(1), file);
                    assertEquals(file, rows.getString(2));
                    assertEquals(recentScripts.get(index).digest(), rows.getString(3).strip(), file);
                    assertEquals("SUCCEEDED", rows.getString(4), file);
                }
                assertFalse(rows.next());
            }
            try (ResultSet rows = s.executeQuery("SELECT revision, state, receipt_json, stop_violation FROM sennard.workflow_runs WHERE run_id = 'before-v27'")) {
                assertTrue(rows.next());
                assertEquals(7, rows.getLong(1));
                assertEquals("RUNNING", rows.getString(2));
                assertEquals("{\"before\":true}", rows.getString(3));
                assertFalse(rows.getBoolean(4));
            }
            try (ResultSet rows = s.executeQuery("SELECT revision, state, root_json, stop_rechecks, plan_target FROM sennard.convergence_roots WHERE root_id = 'before-v27'")) {
                assertTrue(rows.next());
                assertEquals(3, rows.getLong(1));
                assertEquals("OPEN", rows.getString(2));
                assertEquals("{}", rows.getString(3));
                assertEquals(0, rows.getInt(4));
                assertNull(rows.getString(5));
            }
            try (ResultSet rows = s.executeQuery("SELECT count(*) FROM sennard.workflow_blocked_plans")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getLong(1));
            }
            try (ResultSet rows = s.executeQuery("SELECT count(*) FROM sennard.execution")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getLong(1));
            }
        }
    }

    /** The user's convergence limits are "1 or more, no upper limit", so the epoch and the attempt number of a root are bounded below only, not by the old fixed 2 and 3. */
    @Test
    void theEpochAndTheAttemptNumberOfAConvergenceRootHaveNoUpperBound() throws Exception {
        dropSchemas();
        SennardDatabaseConfig config = BusinessDb.config();
        BusinessDb.migrations().expand(config);
        Map<String, String> clauses = new TreeMap<>();
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement();
                ResultSet rows = s.executeQuery("SELECT constraint_name, check_clause FROM information_schema.check_constraints WHERE constraint_schema = 'sennard' "
                        + "AND (constraint_name LIKE '%!_epoch' ESCAPE '!' OR constraint_name LIKE '%!_ordinal' ESCAPE '!')")) {
            while (rows.next()) {
                clauses.put(rows.getString(1), rows.getString(2));
            }
        }
        assertEquals(Set.of("convergence_epochs_epoch", "convergence_leases_epoch", "convergence_leases_ordinal", "convergence_attempts_epoch", "convergence_attempts_ordinal",
                "convergence_reviews_epoch", "workflow_attempt_links_epoch", "workflow_attempt_links_ordinal",
                "continuity_current_memory_epoch", "continuity_tasks_epoch"), clauses.keySet(), clauses.toString());
        clauses.forEach((name, clause) -> {
            assertTrue(clause.contains(">= 1"), name + " keeps its lower bound: " + clause);
            assertFalse(clause.contains("<="), name + " has no upper bound: " + clause);
        });
    }

    @Test
    void theFirstSchemaHasNoPersonOrLoginColumn() throws Exception {
        Map<String, Set<String>> columns = new TreeMap<>();
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement();
                ResultSet rows = s.executeQuery("SELECT table_schema, table_name, column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'sennard' AND table_name IN ('module_schema', 'active_generation', 'session_ref', 'session_generation', 'call_admission', 'managed_child_identity')")) {
            while (rows.next()) {
                // The qualified name is made here.
                columns.computeIfAbsent(rows.getString(1) + "." + rows.getString(2), k -> new HashSet<>()).add(rows.getString(3));
            }
        }
        Map<String, Set<String>> expected = new TreeMap<>();
        expected.put("sennard.module_schema", Set.of("module", "version"));
        expected.put("sennard.active_generation", Set.of("session_ref", "generation"));
        expected.put("sennard.session_ref", Set.of("session_ref", "host", "host_session_digest", "pepper_version",
                "local_session_id", "last_generation", "next_message_seq", "pending_count", "sender_drafts", "sender_receipts", "created_at",
                "last_seen_at", "kind", "parent_ref"));
        expected.put("sennard.managed_child_identity", Set.of("child_ref", "root_ref", "root_generation"));
        expected.put("sennard.session_generation", Set.of("session_ref", "generation", "origin", "started_at", "client_started_at",
                "client_ended_at", "process_key", "client_instance_id", "relay_id", "code_digest", "lease_until", "heartbeat_at", "ended_at", "end_reason",
                "superseded_at", "transport", "delivery"));
        expected.put("sennard.call_admission", Set.of("host", "call_id", "tool", "host_session_id", "agent_id", "model", "effort", "turn_id",
                "transcript_path", "created_at"));
        assertEquals(expected, columns, "exactly the designed columns");
        Pattern person = Pattern.compile("(?i)(person|member|email|e_mail|login|passw|display_name|user_name|username|subject|principal|oauth|owner_id)");
        columns.forEach((table, names) -> names.forEach(name -> assertFalse(person.matcher(name).find(), table + "." + name + " looks like a person or login column")));
        String v2 = new String(StoreClusterTest.class.getResourceAsStream("/db/expand/identity/V2__session_identity.sql").readAllBytes(), StandardCharsets.UTF_8);
        assertFalse(Pattern.compile("(?i)PASSWORD\\s+'").matcher(v2).find(), "V2 sets no password");
    }

    @Test
    void twoServicesMigratingAtTheSameTimeApplyEachScriptOnce() throws Exception {
        dropSchemas();
        SennardDatabaseConfig config = BusinessDb.config();
        Migrations migrations = BusinessDb.migrations();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    return migrations.expand(config);
                }));
            }
            go.countDown();
            int applied = 0;
            for (Future<Integer> run : runs) {
                applied += run.get(120, TimeUnit.SECONDS);
            }
            assertEquals(EXPAND_SCRIPTS, applied, "each script applied exactly once between the two");
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------------------------------- the store port

    @Test
    void aTransactionCommitsWhenTheBodyReturnsAndRollsBackWhenItThrows() throws Exception {
        TestStores store = store();
        UUID kept = UUID.randomUUID();
        UUID refused = UUID.randomUUID();
        assertEquals(0, jobs(store), "every test starts from empty business tables");
        store.transaction(TxOptions.write(), tx -> insertJob(tx, kept));
        assertEquals(1, jobs(store));
        assertEquals(1, jobsWithId(store, kept), "the returning body's row is committed");
        assertThrows(IllegalStateException.class, () -> store.transaction(TxOptions.write(), tx -> {
            insertJob(tx, refused);
            throw new IllegalStateException("domain refusal");
        }));
        assertEquals(1, jobs(store), "the throwing body wrote nothing");
        assertEquals(0, jobsWithId(store, refused));
    }

    @Test
    void aNestedTransactionIsASavepointThatUndoesOnlyItsOwnWrites() throws Exception {
        TestStores store = store();
        UUID outer = UUID.randomUUID();
        UUID undone = UUID.randomUUID();
        UUID innerKept = UUID.randomUUID();
        store.transaction(TxOptions.write(), tx -> {
            insertJob(tx, outer);
            assertThrows(IllegalStateException.class, () -> tx.nested(inner -> {
                insertJob(inner, undone);
                throw new IllegalStateException("inner refusal");
            }));
            tx.nested(inner -> insertJob(inner, innerKept));
            return null;
        });
        assertEquals(1, jobsWithId(store, outer), "the outer transaction's row");
        assertEquals(0, jobsWithId(store, undone), "the refused nested body's row is undone to its savepoint");
        assertEquals(1, jobsWithId(store, innerKept), "the nested body that returned keeps its row");
        assertEquals(2, jobs(store));
    }

    // ----------------------------------------------------------------------------------------- how long a wait for a lock can last (I6)

    /** A row another transaction has changed and not ended holds the same wait: it ends in STORE_TIMEOUT, leaves nothing behind, and the same statement runs once the holder rolled back. */
    @Test
    void aRowLockWaitEndsInStoreTimeoutAndNothingIsLeftBehind() throws Exception {
        UUID job = UUID.randomUUID();
        TestStores store = store();
        store.transaction(TxOptions.write(), tx -> insertJob(tx, job));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> holder = pool.submit(() -> store.transaction(TxOptions.write(), tx -> {
                tx.query(APPEND_TO_JOB, "holder", job);
                holding.countDown();
                awaitRelease(release);
                throw new IllegalStateException("the holder rolls back");
            }));
            assertTrue(holding.await(20, TimeUnit.SECONDS));
            StoreException timeout = assertThrows(StoreException.class,
                    () -> store.transaction(TxOptions.write().withTimeout(Duration.ofSeconds(1)), tx -> tx.query(APPEND_TO_JOB, "waiter;", job)));
            assertEquals(StoreCode.STORE_TIMEOUT, timeout.code());
            assertEquals(TIMEOUT_SQL_STATE, timeout.sqlState());
            release.countDown();
            ExecutionException rolledBack = assertThrows(ExecutionException.class, () -> holder.get(20, TimeUnit.SECONDS));
            assertEquals(IllegalStateException.class, rolledBack.getCause().getClass());
            assertEquals(1, store.transaction(TxOptions.write().withTimeout(Duration.ofSeconds(1)), tx -> tx.query(APPEND_TO_JOB, "waiter;", job)).rowCount(), "the same statement, once the row is free");
            assertEquals("waiter;", errorCodeOf(store, job), "one update's effect: neither the holder's rolled back one nor the timed out one is in it");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aStatementPastItsLimitIsStoreTimeoutAndNothingIsLeftBehind() throws Exception {
        TestStores store = store();
        StoreException timeout = assertThrows(StoreException.class,
                () -> store.transaction(TxOptions.write().withTimeout(Duration.ofSeconds(1)), tx -> tx.query("SELECT pg_sleep(3)")));
        assertEquals(StoreCode.STORE_TIMEOUT, timeout.code());
        assertEquals(TIMEOUT_SQL_STATE, timeout.sqlState());
        assertEquals(1, store.query("SELECT 1 AS one").rows().size(), "the pool still works");
    }

    @Test
    void whenEveryConnectionIsBusyTheCallerGetsPoolExhaustedNotAHang() throws Exception {
        // The shared pool with one connection left to borrow, and the product's one second wait: every caller below sees a pool of one.
        ScarcePool single = ScarcePool.leaving(1);
        TestStores store = TestStores.of(single.dataSource());
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> holder = pool.submit(() -> store.transaction(TxOptions.write(), tx -> {
                holding.countDown();
                awaitRelease(release);
                return null;
            }));
            assertTrue(holding.await(20, TimeUnit.SECONDS));
            StoreException exhausted = assertThrows(StoreException.class, () -> store.query("SELECT 1"));
            assertEquals(StoreCode.STORE_POOL_EXHAUSTED, exhausted.code());
            assertTrue(exhausted.getMessage().contains("threadsAwaitingConnection="), exhausted.getMessage());
            // The one connection is still held: the caller was answered by the pool's limit, not by a connection coming free.
            assertFalse(holder.isDone(), "the holder still has the connection");
            assertEquals(StoreCode.STORE_POOL_EXHAUSTED, assertThrows(StoreException.class, () -> store.transaction(TxOptions.write(), tx -> null)).code(), "a statement's transaction too");
            JevUsageService usage = store.context().getBean(JevUsageService.class);
            assertEquals(StoreCode.STORE_POOL_EXHAUSTED, assertThrows(StoreException.class, usage::totals).code(), "a service's transaction too");
            assertFalse(holder.isDone(), "the holder still has the connection");
            release.countDown();
            holder.get(20, TimeUnit.SECONDS);
            assertEquals(1, store.query("SELECT 1 AS one").rows().size());
        } finally {
            release.countDown();
            pool.shutdownNow();
            single.close();
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ encoding

    /**
     * What this can check on the shared database: the pool's connections are UTF-8 on both ends. The arm that the database <em>asks</em> for another client
     * encoding ({@code ALTER DATABASE ... SET client_encoding}) is not here: it changes a setting of the shared database itself, which a test of the
     * business schemas may not do (only the {@code sennard_*} schemas are the test's to change).
     */
    @Test
    void theServiceConnectionsAreUtf8OnBothEnds() throws Exception {
        try (Connection connection = BusinessDb.connect(); Statement s = connection.createStatement();
                ResultSet row = s.executeQuery("SELECT current_setting('server_encoding'), current_setting('client_encoding')")) {
            assertTrue(row.next());
            assertEquals("UTF8", row.getString(1));
            assertEquals("UTF8", row.getString(2));
        }
    }

    @Test
    void koreanTextComesBackFromTheDatabaseUnchanged() throws Exception {
        String korean = "한글 라운드트립";
        try (Connection owner = BusinessDb.connect(); PreparedStatement statement = owner.prepareStatement("SELECT CAST(? AS TEXT), OCTET_LENGTH(CAST(? AS TEXT))")) {
            statement.setString(1, korean);
            statement.setString(2, korean);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(korean, row.getString(1));
                assertEquals(korean.getBytes(StandardCharsets.UTF_8).length, row.getInt(2), "stored as UTF-8 bytes");
            }
        }
    }

    // ----------------------------------------------------------------------------------------------- the server's store beans

    /** The provider of one attempt: the shared database as it is, the pool the service would open, released when the test says. */
    private static final class Provided implements DatabaseConfigProvider {
        final SennardDatabaseConfig config;
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> stops = new java.util.concurrent.CopyOnWriteArrayList<>();
        final PhaseListener phases;
        volatile PreparingDataSource store;

        Provided(SennardDatabaseConfig config, PhaseListener phases) {
            this.config = config;
            this.phases = phases;
        }

        @Override
        public SennardDatabaseConfig provide() {
            phases.entered(StorePhase.CONNECTING);
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                throw new IllegalStateException(interrupted);
            }
            return config;
        }

        @Override
        public void stop() {
            stops.add(((HikariDataSource) store.getTargetDataSource()).isClosed() ? "pools already closed" : "pools still open");
        }
    }

    private static void awaitPhase(PreparedStore postgres, StorePhase phase) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (postgres.status().phase() != phase && System.nanoTime() < deadline) {
            LockWaits.pause();
        }
        assertEquals(phase, postgres.status().phase());
    }

    @Test
    void theStoreAnswersPreparingAtOnceThenServesAndTheBeansCloseThePoolBeforeTheDatabase() throws Exception {
        // The one pool of the tests that the product under test opens itself (PreparedStore makes it): two connections (a pool of one has no idle timeout, which Hikari warns of), counted in BusinessDbExtension.RESERVED_CONNECTIONS.
        SennardDatabaseConfig config = BusinessDb.config(2);
        AtomicReference<Provided> provided = new AtomicReference<>();
        PreparedStore postgres = new PreparedStore(phases -> {
            provided.set(new Provided(config, phases));
            return provided.get();
        }, Duration.ofMillis(1), Clock.systemUTC(), BusinessDb.dataSources());
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(PreparedStore.class, () -> postgres);
        context.registerBean(io.github.jaeseongs95.sennard.service.database.SqlBuilder.class,
                () -> new io.github.jaeseongs95.sennard.service.database.SqlBuilder(context.getBean(PreparingDataSource.class)));
        context.registerBean("clock", Clock.class, Clock::systemUTC); // the application's one Clock bean (AppConfiguration in the product)
        context.register(RandomConfiguration.class); // the application's one SecureRandom bean, which the stores take
        context.register(JacksonAutoConfiguration.class); // the JsonMapper bean the continuity and workflow stores take
        context.registerBean("storeIsolation", org.springframework.transaction.annotation.Isolation.class, () -> org.springframework.transaction.annotation.Isolation.READ_COMMITTED); // the edition's setting
        context.registerBean(io.github.jaeseongs95.sennard.service.secret.PrivateStorage.class, TestPrivateStorage::new); // the edition's port of the storage of secrets
        context.register(PreparedStoreConfiguration.class);
        context.refresh();
        PreparingDataSource store = context.getBean(PreparingDataSource.class);
        JevUsageService usage = context.getBean(JevUsageService.class); // a service whose method opens its own transaction
        StoreException before = assertThrows(StoreException.class, store::getConnection);
        assertEquals(StoreCode.STORE_PREPARING, before.code());
        assertEquals("NOT_CONFIGURED", before.details().get("phase"));
        assertEquals(StoreCode.STORE_PREPARING, assertThrows(StoreException.class, usage::totals).code(),
                "a service's transaction gets the same answer");

        Future<?> preparing = Executors.newVirtualThreadPerTaskExecutor().submit(() -> postgres.prepare(store::ready));
        // The machine says CONNECTING from the moment the attempt begins, so the provider of the attempt is what is waited for.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (provided.get() == null && System.nanoTime() < deadline) {
            LockWaits.pause();
        }
        awaitPhase(postgres, StorePhase.CONNECTING);
        provided.get().store = store;
        assertEquals("CONNECTING", assertThrows(StoreException.class, store::getConnection).details().get("phase"), "the answer names the phase while it lasts");
        provided.get().release.countDown();
        preparing.get(60, TimeUnit.SECONDS);
        assertEquals(StorePhase.READY, postgres.status().phase());
        assertEquals(0, usage.totals().calls(), "served through the same beans");

        context.close();
        assertEquals(List.of("pools already closed"), provided.get().stops, "the database stopped once, after the pools");
    }

    /**
     * A pool that cannot pass the start-up checks is never served. The role and policy checks are gone with the roles; the check left is the start-up
     * connection itself, and here it fails on a password file that does not exist: the reason is the code of that failure, after the three attempts.
     */
    @Test
    void poolsThatFailTheStartUpChecksEndThePreparationAsFailedWithTheReason() throws Exception {
        SennardDatabaseConfig base = BusinessDb.config(); // no pool is made: the start-up check fails first
        // A path nothing ever creates: a random name in the temp folder.
        Path missing = Path.of(System.getProperty("java.io.tmpdir"), "sennard-no-such-password-" + UUID.randomUUID());
        SennardDatabaseConfig unreadable = new SennardDatabaseConfig(base.jdbcUrl(), base.ownerUser(), missing, base.driverPath(), base.tlsPolicy(), base.poolSize());
        AtomicInteger attempts = new AtomicInteger();
        PreparedStore postgres = new PreparedStore(phases -> new DatabaseConfigProvider() {
            @Override
            public SennardDatabaseConfig provide() {
                attempts.incrementAndGet();
                return unreadable;
            }
        }, Duration.ofMillis(1), Clock.systemUTC(), BusinessDb.dataSources());
        PreparingDataSource store = new PreparingDataSource(() -> PreparedStoreConfiguration.statusBody(postgres.status()));
        postgres.prepare(store::ready);
        assertEquals(StorePhase.FAILED, postgres.status().phase());
        assertEquals("DB_CREDENTIAL_UNREADABLE", postgres.status().reason());
        assertEquals(3, attempts.get());
        assertEquals(StoreCode.STORE_PREPARING, assertThrows(StoreException.class, store::getConnection).code(), "no pool that failed its checks is served");
    }
}
