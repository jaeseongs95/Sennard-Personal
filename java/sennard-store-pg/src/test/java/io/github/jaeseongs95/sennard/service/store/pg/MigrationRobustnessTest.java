// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.service.migration.HistoryMigrator;
import io.github.jaeseongs95.sennard.service.migration.MigrationScript;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * What the migrations and the start-up check must survive: the runner's own cases (a new database, a restart, a changed script, a failed script, an order that is
 * broken), a schema that is somebody else's, and the start-up check of the encoding. They run on the database the test run names ({@link BusinessDb}: PostgreSQL), whose account
 * may not create a database, an application schema outside the test's namespace, or a {@code public} table. {@link #empty()} drops {@code sennard}
 * and the test-owned {@code sennard_*} schemas before each case. A temporary table exists only on the observing connection.
 *
 * <p>Not here any more: the case of a database in another encoding (LATIN1), which needs a database of its own; the shared database is UTF-8 and nothing may
 * create another. The refusal itself is {@code DataSourceFactory.verifyEncoding}; here the UTF-8 database is shown to pass it.
 */
@Tag("business-db")
class MigrationRobustnessTest {
    /** Derived from the classpath, so a later script does not change these tests. */
    private static final int EXPAND_SCRIPTS = expandScripts();
    /** The schema the runner's cases make their tables in: the business schema itself, which the runner made for the history and the reset drops. */
    private static final String SANDBOX = "sennard";
    private static final String V27_DIGEST = "5b75669d3a4d9ff82ba8be6c81d008f763c3450fdf036f9f56fc7ba637c05493";
    private static final Instant V27_TIME = Instant.parse("2004-05-06T07:08:09.123456Z");

    @BeforeEach
    void empty() throws SQLException {
        BusinessDb.dropBusinessSchemas();
    }

    private static int expandScripts() {
        // The scripts are in the module's jar, which a file walk cannot enter: Spring reads a folder of a jar as well as one of the file system.
        try {
            return new org.springframework.core.io.support.PathMatchingResourcePatternResolver().getResources("classpath*:db/expand/**/*.sql").length;
        } catch (IOException cause) {
            throw new IllegalStateException(cause);
        }
    }

    // ------------------------------------------------------------------------------------------------------------- the jar's scripts

    @Test
    void aSchemaThatIsSomebodyElsesDoesNotStopExpandOrTheFirstContract() throws Exception {
        SennardDatabaseConfig config = BusinessDb.config();
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement()) {
            s.execute("CREATE SCHEMA sennard_other_app");
            s.execute("CREATE TABLE sennard_other_app.someone_elses_table (id integer)");
            s.execute("CREATE TABLE sennard_other_app.expand_history (id integer)");
        }
        Migrations migrations = BusinessDb.migrations();
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement()) {
            owner.setAutoCommit(false);
            s.execute("CREATE TEMP TABLE expand_history (id integer) ON COMMIT DROP");
            s.execute("INSERT INTO pg_temp.expand_history VALUES (23)");
            List<String> before = externalTables(s);
            assertEquals(EXPAND_SCRIPTS, migrations.expand(config));
            assertEquals(before, externalTables(s), "expand left the external tables in place and added no external history");
            // A contract script exists only on the test classpath (src/test/resources/db/contract).
            assertEquals(1, migrations.contract(config));
            assertEquals(0, migrations.contract(config));
            assertEquals(0, migrations.expand(config));
            assertEquals(before, externalTables(s), "contract and restart added no external history and kept the other application's tables");
            try (ResultSet rows = s.executeQuery("SELECT id FROM pg_temp.expand_history ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals(23, rows.getInt(1));
                assertFalse(rows.next());
            }
            try (ResultSet rows = s.executeQuery("SELECT count(*) FROM sennard.contract_history WHERE version = 1 AND outcome = 'SUCCEEDED'")) {
                rows.next();
                assertEquals(1, rows.getLong(1));
            }
            try (ResultSet rows = s.executeQuery("SELECT count(*) FROM sennard.expand_history WHERE outcome = 'SUCCEEDED'")) {
                rows.next();
                assertEquals(EXPAND_SCRIPTS, rows.getLong(1), "the contract run did not touch the expand history");
            }
            owner.commit();
        }
    }

    /** Includes all the other application's tables and any names reserved for migration history outside the business schema. */
    private static List<String> externalTables(Statement statement) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT table_schema, table_name FROM information_schema.tables "
                + "WHERE table_schema <> 'sennard' AND (table_schema = 'sennard_other_app' OR table_name IN ('expand_history', 'contract_history')) "
                + "ORDER BY table_schema, table_name")) {
            while (rows.next()) {
                tables.add(rows.getString(1) + "." + rows.getString(2));
            }
        }
        return tables;
    }

    /**
     * The database of the shared service is UTF-8: it passes the start-up check, and the pool is made. (A database that is not UTF-8 cannot be made here.) The pool is
     * the test JVM's one, which the product's factory made with that check; the check itself is run again here on one of its connections, as the factory does.
     */
    @Test
    void aUtf8DatabasePassesTheStartupCheck() throws Exception {
        assertEquals(EXPAND_SCRIPTS, BusinessDb.migrations().expand(BusinessDb.config()), "a script holds Korean text, which the database takes whole");
        try (Connection connection = BusinessDb.connect()) {
            assertTrue(connection.isValid(5));
            connection.setAutoCommit(false); // the check runs in a transaction it rolls back, as on the start-up connection
            DataSourceFactory.verifyEncoding(connection);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------- the runner

    private static MigrationScript script(long version, String name, String sql) {
        byte[] bytes = sql.getBytes(StandardCharsets.UTF_8);
        return new MigrationScript(version, "V" + version + "__" + name + ".sql", bytes, Digests.sha256Hex(bytes));
    }

    /** The first script of a case: the schema is there already (the runner made it for the history), so the statements are the script. */
    private static String first(String statements) {
        return statements;
    }

    private static int run(SennardDatabaseConfig config, List<MigrationScript> scripts) {
        return new MigrationRunner(Clock.systemUTC(), BusinessDb.dataSources()).run(config, MigrationRunner.Phase.EXPAND, scripts);
    }

    /** One row of the history per attempt: the number, the file and the outcome, in the order of the number and the attempt. */
    private static List<String> history() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement();
                ResultSet found = s.executeQuery("SELECT version, script, outcome FROM sennard.expand_history ORDER BY version, attempt")) {
            while (found.next()) {
                rows.add(found.getLong(1) + " " + found.getString(2) + " " + found.getString(3));
            }
        }
        return rows;
    }

    /** The same rows as {@link #history()} with their attempt and the digest of the file they were made for: {@code version/attempt outcome digest}. */
    private static List<String> attempts() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement();
                ResultSet found = s.executeQuery("SELECT version, attempt, outcome, digest FROM sennard.expand_history ORDER BY version, attempt")) {
            while (found.next()) {
                rows.add(found.getLong(1) + "/" + found.getInt(2) + " " + found.getString(3) + " " + found.getString(4).strip());
            }
        }
        return rows;
    }

    private static boolean tableExists(String name) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement();
                ResultSet found = s.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = '" + SANDBOX + "' AND table_name = '" + name + "'")) {
            found.next();
            return found.getLong(1) == 1;
        }
    }

    @Test
    void pendingV27BindsItsAttemptTimeOnAFreshDatabaseInAnotherSessionZone() throws Exception {
        SennardDatabaseConfig config = BusinessDb.config();
        TimeZone previous = TimeZone.getDefault();
        try {
            // pgJDBC initializes each new session with the JVM zone.
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
            try (Connection connection = BusinessDb.dataSources().direct(config).getConnection();
                    Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SHOW TimeZone")) {
                assertTrue(rows.next());
                assertEquals("Asia/Seoul", rows.getString(1));
            }
            assertEquals(EXPAND_SCRIPTS, new Migrations(Clock.fixed(V27_TIME, ZoneOffset.ofHours(9)), BusinessDb.dataSources()).expand(config));
            LocalDateTime utc = LocalDateTime.parse("2004-05-06T07:08:09.123");
            assertEquals(new V27Row(V27_DIGEST, utc, "1", utc), v27Row());
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void successfulV27FromTheReleasedSqlKeepsItsHistoryAndLockRow() throws Exception {
        List<MigrationScript> scripts = MigrationScript.load(MigrationRunner.Phase.EXPAND.location());
        SennardDatabaseConfig config = BusinessDb.config();
        try (Connection connection = BusinessDb.dataSources().direct(config).getConnection()) {
            // The released path executes the original SQL without a timestamp binding.
            assertEquals(27, new HistoryMigrator(Clock.fixed(V27_TIME, ZoneOffset.UTC))
                    .run(connection, MigrationRunner.Phase.EXPAND.target(), scripts.subList(0, 27)));
        }
        V27Row original = v27Row();
        assertEquals(V27_DIGEST, original.digest());
        assertEquals("1", original.lockValue());
        assertFalse(original.appliedAt().equals(original.updatedAt()), "the released insert used the database clock");
        List<String> originalAttempts = attempts();
        Migrations candidate = new Migrations(Clock.fixed(V27_TIME.plusSeconds(60), ZoneOffset.UTC), BusinessDb.dataSources());
        assertEquals(EXPAND_SCRIPTS - 27, candidate.expand(config));
        assertEquals(0, candidate.expand(config));
        assertEquals(original, v27Row());
        assertEquals(originalAttempts, attempts().subList(0, 27));
    }

    @Test
    void pendingV27RollsBackABoundInsertFailureAndUsesTheNextAttemptTimeOnRetry() throws Exception {
        SennardDatabaseConfig config = BusinessDb.config();
        List<MigrationScript> scripts = MigrationScript.load(MigrationRunner.Phase.EXPAND.location());
        MigrationRunner first = new MigrationRunner(Clock.fixed(V27_TIME, ZoneOffset.UTC), BusinessDb.dataSources());
        assertEquals(26, first.run(config, MigrationRunner.Phase.EXPAND, scripts.subList(0, 26)));
        try (Connection connection = BusinessDb.connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO sennard.workflow_metadata (meta_key, value, updated_at) "
                    + "VALUES ('blocked-plan-write-lock', 'fixture', TIMESTAMP '2000-01-01 00:00:00')");
        }
        StoreException stopped = assertThrows(StoreException.class, () -> first.run(config, MigrationRunner.Phase.EXPAND));
        assertEquals(StoreCode.MIGRATION_FAILED, stopped.code());
        assertFalse(tableExists("workflow_blocked_plans"), "PostgreSQL rolled back V27's DDL");
        assertEquals("27/1 FAILED " + V27_DIGEST, attempts().get(26));
        try (Connection connection = BusinessDb.connect(); Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("DELETE FROM sennard.workflow_metadata WHERE meta_key = 'blocked-plan-write-lock' AND value = 'fixture'"));
        }
        MigrationRunner retry = new MigrationRunner(Clock.fixed(V27_TIME.plusSeconds(60), ZoneOffset.UTC), BusinessDb.dataSources());
        assertEquals(EXPAND_SCRIPTS - 26, retry.run(config, MigrationRunner.Phase.EXPAND));
        assertEquals(List.of("27/1 FAILED " + V27_DIGEST, "27/2 SUCCEEDED " + V27_DIGEST), attempts().subList(26, 28));
        LocalDateTime retriedAt = LocalDateTime.parse("2004-05-06T07:09:09.123");
        assertEquals(new V27Row(V27_DIGEST, retriedAt, "1", retriedAt), v27Row());
    }

    @Test
    void pendingV27RejectsChangedBytesAndAnUnrecognizedResourceBeforeAnAttempt() throws Exception {
        SennardDatabaseConfig config = BusinessDb.config();
        List<MigrationScript> scripts = MigrationScript.load(MigrationRunner.Phase.EXPAND.location());
        List<MigrationScript> beforeV27 = scripts.subList(0, 26);
        assertEquals(26, run(config, beforeV27));
        MigrationScript v27 = scripts.get(26);
        assertEquals(V27_DIGEST, v27.digest());
        List<MigrationScript> candidate = new ArrayList<>(beforeV27);
        candidate.add(new MigrationScript(v27.version(), v27.file(), v27.bytes(), v27.digest()));
        assertEquals(StoreCode.MIGRATION_CHANGED, assertThrows(StoreException.class, () -> run(config, candidate)).code());
        byte[] changed = v27.bytes().clone();
        changed[0] = ' ';
        candidate.set(26, new MigrationScript(v27.version(), v27.file(), changed, v27.digest(), v27.source()));
        assertEquals(StoreCode.MIGRATION_CHANGED, assertThrows(StoreException.class, () -> run(config, candidate)).code());
        assertEquals(26, attempts().size());
        assertFalse(tableExists("workflow_blocked_plans"));
    }

    @Test
    void pendingV27RejectsOneMatchingIdentifierWithoutRecordingAnAttempt() throws Exception {
        SennardDatabaseConfig config = BusinessDb.config();
        List<MigrationScript> scripts = MigrationScript.load(MigrationRunner.Phase.EXPAND.location());
        List<MigrationScript> beforeV27 = scripts.subList(0, 26);
        assertEquals(26, run(config, beforeV27));
        List<String> attemptsBeforeV27 = attempts();
        MigrationScript v27 = scripts.get(26);
        List<MigrationScript> candidate = new ArrayList<>(beforeV27);
        candidate.add(new MigrationScript(v27.version(), "v27__workflow_stop_checks.sql", v27.bytes(), v27.digest(), v27.source()));
        assertEquals(StoreCode.MIGRATION_CHANGED, assertThrows(StoreException.class, () -> run(config, candidate)).code());
        assertEquals(attemptsBeforeV27, attempts());
        assertFalse(tableExists("workflow_blocked_plans"));

        candidate.set(26, v27);
        assertEquals(1, run(config, candidate));
        List<String> attemptsThroughV27 = attempts();
        V27Row original = v27Row();
        candidate.add(new MigrationScript(v27.version() + 1, v27.file(), v27.bytes(), v27.digest(), v27.source()));
        assertEquals(StoreCode.MIGRATION_CHANGED, assertThrows(StoreException.class, () -> run(config, candidate)).code());
        assertEquals(attemptsThroughV27, attempts());
        assertEquals(original, v27Row());
    }

    private record V27Row(String digest, LocalDateTime appliedAt, String lockValue, LocalDateTime updatedAt) {}

    private static V27Row v27Row() throws SQLException {
        try (Connection connection = BusinessDb.connect(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT history.digest, history.applied_at, lock_row.value, lock_row.updated_at
                        FROM sennard.expand_history history
                        CROSS JOIN sennard.workflow_metadata lock_row
                        WHERE history.version = 27 AND history.outcome = 'SUCCEEDED'
                        AND lock_row.meta_key = 'blocked-plan-write-lock'
                        """)) {
            assertTrue(rows.next());
            V27Row row = new V27Row(rows.getString(1).strip(), rows.getObject(2, LocalDateTime.class), rows.getString(3), rows.getObject(4, LocalDateTime.class));
            assertFalse(rows.next());
            return row;
        }
    }

    /** A new database gets every script, in number order, with a history row each (file, digest, a UTC time, SUCCEEDED); a restart over it applies nothing. */
    @Test
    void aNewDatabaseGetsEveryScriptOnceAndARestartAppliesNothing() throws Exception {
        SennardDatabaseConfig config = BusinessDb.config();
        List<MigrationScript> scripts = List.of(script(2, "second", "CREATE TABLE " + SANDBOX + ".second_one (id BIGINT NOT NULL PRIMARY KEY);"),
                script(1, "first", first("CREATE TABLE " + SANDBOX + ".first_one (id BIGINT NOT NULL PRIMARY KEY);\nINSERT INTO " + SANDBOX + ".first_one (id) VALUES (1);")));
        assertEquals(2, run(config, List.of(scripts.get(1), scripts.get(0))), "both scripts, whatever order they are given in");
        assertEquals(List.of("1 V1__first.sql SUCCEEDED", "2 V2__second.sql SUCCEEDED"), history());
        assertTrue(tableExists("first_one") && tableExists("second_one"));
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement();
                ResultSet rows = s.executeQuery("SELECT digest, applied_at FROM sennard.expand_history WHERE version = 1")) {
            rows.next();
            assertEquals(scripts.get(1).digest(), rows.getString(1).strip(), "the history holds the SHA-256 of the file");
            assertTrue(rows.getObject(2, java.time.LocalDateTime.class).isAfter(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(5)), "a UTC time of the clock");
        }
        assertEquals(0, run(config, scripts), "the restart over an applied database applies nothing");
        List<MigrationScript> grown = new ArrayList<>(scripts);
        grown.add(script(3, "third", "CREATE TABLE " + SANDBOX + ".third_one (id BIGINT NOT NULL PRIMARY KEY);"));
        assertEquals(1, run(config, grown), "a script added by a newer version is the only one applied");
        assertEquals(List.of("1 V1__first.sql SUCCEEDED", "2 V2__second.sql SUCCEEDED", "3 V3__third.sql SUCCEEDED"), history());
    }

    /** An applied script whose file changed stops the run before anything is applied: the database was made by other text than the jar holds. */
    @Test
    void aChangedScriptStopsTheRunBeforeAnythingIsApplied() throws Exception {
        SennardDatabaseConfig config = BusinessDb.config();
        assertEquals(1, run(config, List.of(script(1, "first", first("CREATE TABLE " + SANDBOX + ".first_one (id BIGINT NOT NULL PRIMARY KEY);")))));
        List<MigrationScript> changed = List.of(script(1, "first", first("CREATE TABLE " + SANDBOX + ".first_one (id BIGINT NOT NULL PRIMARY KEY, extra BIGINT);")),
                script(2, "second", "CREATE TABLE " + SANDBOX + ".second_one (id BIGINT NOT NULL PRIMARY KEY);"));
        StoreException stopped = assertThrows(StoreException.class, () -> run(config, changed));
        assertEquals(StoreCode.MIGRATION_CHANGED, stopped.code());
        assertFalse(tableExists("second_one"), "the pending script was not applied");
        assertEquals(List.of("1 V1__first.sql SUCCEEDED"), history());
    }

    /**
     * A script that fails leaves its attempt in the history as FAILED, and the next start tries that number again as the next attempt: it applies once the file is fixed. The script is
     * rolled back whole (PostgreSQL's DDL is transactional), and the history (the claim was committed with it) says FAILED.
     */
    @Test
    void aFailedScriptLeavesItsNumberAsFailedAndIsTriedAgainAtTheNextStart() throws Exception {
        SennardDatabaseConfig config = BusinessDb.config();
        MigrationScript first = script(1, "first", first("CREATE TABLE " + SANDBOX + ".first_one (id BIGINT NOT NULL PRIMARY KEY);"));
        MigrationScript broken = script(2, "second", "CREATE TABLE " + SANDBOX + ".second_one (id BIGINT NOT NULL PRIMARY KEY);\nINSERT INTO " + SANDBOX + ".no_such_table (id) VALUES (1);");
        StoreException failed = assertThrows(StoreException.class,
                () -> run(config, List.of(first, broken, script(3, "third", "CREATE TABLE " + SANDBOX + ".third_one (id BIGINT NOT NULL PRIMARY KEY);"))));
        assertEquals(StoreCode.MIGRATION_FAILED, failed.code());
        assertTrue(failed.getMessage().contains("V2__second.sql"), "the message names the file: " + failed.getMessage());
        assertEquals(List.of("1 V1__first.sql SUCCEEDED", "2 V2__second.sql FAILED"), history(), "the failed number is recorded and the next one was not tried");
        assertFalse(tableExists("second_one"), "the failed script's first statement was rolled back with it");
        assertFalse(tableExists("third_one"));
        MigrationScript fixed = script(2, "second", "CREATE TABLE " + SANDBOX + ".second_one (id BIGINT NOT NULL PRIMARY KEY);");
        assertEquals(2, run(config, List.of(first, fixed, script(3, "third", "CREATE TABLE " + SANDBOX + ".third_one (id BIGINT NOT NULL PRIMARY KEY);"))),
                "the failed number and the one behind it");
        assertEquals(List.of("1 V1__first.sql SUCCEEDED", "2 V2__second.sql FAILED", "2 V2__second.sql SUCCEEDED", "3 V3__third.sql SUCCEEDED"), history(),
                "the failed attempt stays in the history and the next one is a row of its own");
    }

    @Test
    void aScriptBehindTheHighestAppliedNumberAndAnAppliedScriptThatIsGoneAreRefused() throws Exception {
        SennardDatabaseConfig config = BusinessDb.config();
        MigrationScript first = script(1, "first", first("CREATE TABLE " + SANDBOX + ".first_one (id BIGINT NOT NULL PRIMARY KEY);"));
        MigrationScript second = script(2, "second", "CREATE TABLE " + SANDBOX + ".second_one (id BIGINT NOT NULL PRIMARY KEY);");
        MigrationScript third = script(3, "third", "CREATE TABLE " + SANDBOX + ".third_one (id BIGINT NOT NULL PRIMARY KEY);");
        assertEquals(3, run(config, List.of(first, second, third)));
        StoreException gone = assertThrows(StoreException.class, () -> run(config, List.of(first, second)));
        assertEquals(StoreCode.MIGRATION_MISSING, gone.code(), "the history has applied the number 3, which the jar no longer has");
        // The numbers of the jar are one after the other, so a number below the highest applied one that is not applied is only found in a history that was changed by hand:
        // the number 2 is taken out of it (and its table, so that the script could apply again).
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement()) {
            s.execute("DELETE FROM sennard.expand_history WHERE version = 2");
            s.execute("DROP TABLE " + SANDBOX + ".second_one");
        }
        StoreException late = assertThrows(StoreException.class, () -> run(config, List.of(first, second, third)));
        assertEquals(StoreCode.MIGRATION_OUT_OF_ORDER, late.code());
        assertFalse(tableExists("second_one"), "the script below the highest applied number was not applied");
        assertEquals(List.of("1 V1__first.sql SUCCEEDED", "3 V3__third.sql SUCCEEDED"), history(), "the refused run added no row");
    }

    /** The numbers of the scripts are one after the other from 1: a script that leaves a number out stops the run before anything is applied, and no row is added. */
    @Test
    void aNumberLeftOutStopsTheRunBeforeAnythingIsApplied() throws Exception {
        SennardDatabaseConfig config = BusinessDb.config();
        MigrationScript first = script(1, "first", first("CREATE TABLE " + SANDBOX + ".first_one (id BIGINT NOT NULL PRIMARY KEY);"));
        MigrationScript third = script(3, "third", "CREATE TABLE " + SANDBOX + ".third_one (id BIGINT NOT NULL PRIMARY KEY);");
        StoreException gap = assertThrows(StoreException.class, () -> run(config, List.of(first, third)));
        assertEquals(StoreCode.MIGRATION_GAP, gap.code());
        assertFalse(tableExists("first_one"), "the script before the gap was not applied either");
        assertEquals(List.of(), history(), "the refused run added no row");
        StoreException notFromOne = assertThrows(StoreException.class, () -> run(config, List.of(script(2, "second", "CREATE TABLE " + SANDBOX + ".second_one (id BIGINT NOT NULL PRIMARY KEY);"))));
        assertEquals(StoreCode.MIGRATION_GAP, notFromOne.code(), "the first number is 1");
    }

    /**
     * A script that fails twice and then applies makes three attempts, three rows of the same number: the two FAILED ones stay (no row is deleted or changed but the attempt's own) and the
     * third is SUCCEEDED. Each row holds the digest of the file of its attempt. The broken script is one statement, so the rows are the same on every database.
     */
    @Test
    void aFailedAttemptStaysAndTheNextRunMakesTheNextAttempt() throws Exception {
        SennardDatabaseConfig config = BusinessDb.config();
        MigrationScript first = script(1, "first", first("CREATE TABLE " + SANDBOX + ".first_one (id BIGINT NOT NULL PRIMARY KEY);"));
        MigrationScript broken = script(2, "second", "INSERT INTO " + SANDBOX + ".no_such_table (id) VALUES (1);");
        MigrationScript alsoBroken = script(2, "second", "INSERT INTO " + SANDBOX + ".no_such_table (id) VALUES (2);");
        MigrationScript fixed = script(2, "second", "CREATE TABLE " + SANDBOX + ".second_one (id BIGINT NOT NULL PRIMARY KEY);");
        assertEquals(StoreCode.MIGRATION_FAILED, assertThrows(StoreException.class, () -> run(config, List.of(first, broken))).code());
        assertEquals(List.of("1/1 SUCCEEDED " + first.digest(), "2/1 FAILED " + broken.digest()), attempts());
        assertEquals(StoreCode.MIGRATION_FAILED, assertThrows(StoreException.class, () -> run(config, List.of(first, alsoBroken))).code());
        assertEquals(List.of("1/1 SUCCEEDED " + first.digest(), "2/1 FAILED " + broken.digest(), "2/2 FAILED " + alsoBroken.digest()), attempts(),
                "the first failure's row is still there and the second one is a row of its own");
        assertEquals(1, run(config, List.of(first, fixed)), "the script applies once the file is fixed; the number 1 is applied already");
        assertEquals(List.of("1/1 SUCCEEDED " + first.digest(), "2/1 FAILED " + broken.digest(), "2/2 FAILED " + alsoBroken.digest(), "2/3 SUCCEEDED " + fixed.digest()), attempts());
        assertTrue(tableExists("second_one"));
        assertEquals(0, run(config, List.of(first, fixed)), "a restart applies nothing and adds no row");
        assertEquals(4, attempts().size());
    }

    /**
     * Two runners at once apply the same number once. The first runner claims the number 2 and stops in its script on a row the test holds (the database says it waits for the test's
     * connection); the second one starts then and its claim of the same number waits for the first one's transaction (the database says it waits for the first runner, whose backend
     * it names). When the test lets the first one go it commits, and the second one gets the duplicate key, reads the history, finds the number SUCCEEDED with its digest and applies
     * nothing. Nothing in the test waits on a clock: each wait is the database's, observed (LockWaits), and a deadline is reached only by a failure. One SUCCEEDED row, one application.
     */
    @Test
    void twoRunnersThatApplyTheSameNumberAtOnceLeaveOneSucceededRowAndApplyItOnce() throws Exception {
        SennardDatabaseConfig config = BusinessDb.config();
        MigrationScript base = script(1, "base", first("CREATE TABLE " + SANDBOX + ".gate (n BIGINT NOT NULL PRIMARY KEY);\nINSERT INTO " + SANDBOX + ".gate (n) VALUES (0);\n"
                + "CREATE TABLE " + SANDBOX + ".marks (mark VARCHAR(20) NOT NULL);"));
        // The script inserts its mark first and then waits for the row of the gate, so a second application would show as a second mark.
        MigrationScript contested = script(2, "contested", "INSERT INTO " + SANDBOX + ".marks (mark) VALUES ('applied');\nUPDATE " + SANDBOX + ".gate SET n = n + 1;");
        List<MigrationScript> scripts = List.of(base, contested);
        assertEquals(1, run(config, List.of(base)));
        Duration limit = Duration.ofSeconds(20);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection gate = BusinessDb.connect()) {
            gate.setAutoCommit(false);
            try (Statement hold = gate.createStatement(); ResultSet row = hold.executeQuery("SELECT n FROM " + SANDBOX + ".gate FOR UPDATE")) {
                assertTrue(row.next());
            }
            long gateBackend = LockWaits.backendOf(gate);
            try {
                Future<Integer> first = pool.submit(() -> run(config, scripts));
                long firstBackend = LockWaits.awaitWaiterBackendBlockedBy(gateBackend, limit, "the first runner has claimed the number 2 and waits at the gate's row");
                Future<Integer> second = pool.submit(() -> run(config, scripts));
                LockWaits.awaitWaiterBlockedBy(firstBackend, limit, "the second runner waits for the number 2 that the first one claimed");
                assertFalse(second.isDone(), "the second runner waits for the first one's commit");
                gate.rollback();
                assertEquals(1, first.get(30, TimeUnit.SECONDS), "the first runner applied the number 2");
                assertEquals(0, second.get(30, TimeUnit.SECONDS), "the second runner found it applied and applied nothing");
            } finally {
                gate.rollback();
                pool.shutdownNow();
            }
        }
        assertEquals(List.of("1/1 SUCCEEDED " + base.digest(), "2/1 SUCCEEDED " + contested.digest()), attempts(), "one SUCCEEDED row for the number 2, and no FAILED one");
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement()) {
            try (ResultSet rows = s.executeQuery("SELECT count(*) FROM " + SANDBOX + ".marks WHERE mark = 'applied'")) {
                rows.next();
                assertEquals(1, rows.getLong(1), "the script ran once");
            }
            try (ResultSet rows = s.executeQuery("SELECT n FROM " + SANDBOX + ".gate")) {
                rows.next();
                assertEquals(1, rows.getLong(1));
            }
        }
    }

    /** {@code ${long_text}} is the declaration of the SqlBuilder dialect (TEXT on PostgreSQL): a value far over any VARCHAR limit of another database is stored whole. */
    @Test
    void theLongTextTypeOfTheDialectIsWrittenIntoTheScript() throws Exception {
        SennardDatabaseConfig config = BusinessDb.config();
        assertEquals(1, run(config, List.of(script(1, "text", first("CREATE TABLE " + SANDBOX + ".texts (id BIGINT NOT NULL PRIMARY KEY, body ${long_text} NOT NULL);")))));
        String body = "가".repeat(50_000);
        try (Connection owner = BusinessDb.connect(); java.sql.PreparedStatement insert = owner.prepareStatement("INSERT INTO " + SANDBOX + ".texts (id, body) VALUES (1, ?)")) {
            insert.setString(1, body);
            assertEquals(1, insert.executeUpdate());
        }
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement();
                ResultSet rows = s.executeQuery("SELECT body FROM " + SANDBOX + ".texts WHERE id = 1")) {
            rows.next();
            assertEquals(body, rows.getString(1));
        }
    }

    /** The declaration the dialect gives for this database holds a text over 1 MiB, in single-byte and in multi-byte characters, and reads it back whole. */
    @Test
    void theLongTextTypeHoldsAValueOverOneMebibyteAndReadsItBack() throws Exception {
        SennardDatabaseConfig config = BusinessDb.config();
        assertEquals(1, run(config, List.of(script(1, "text", first("CREATE TABLE " + SANDBOX + ".big_texts (id BIGINT NOT NULL PRIMARY KEY, body ${long_text} NOT NULL);")))));
        String ascii = "a".repeat(1_100_000);
        String korean = "가".repeat(400_000); // 1.2 million bytes in UTF-8
        try (Connection owner = BusinessDb.connect(); java.sql.PreparedStatement insert = owner.prepareStatement("INSERT INTO " + SANDBOX + ".big_texts (id, body) VALUES (?, ?)")) {
            insert.setLong(1, 1);
            insert.setString(2, ascii);
            assertEquals(1, insert.executeUpdate());
            insert.setLong(1, 2);
            insert.setString(2, korean);
            assertEquals(1, insert.executeUpdate());
        }
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement(); ResultSet rows = s.executeQuery("SELECT id, body FROM " + SANDBOX + ".big_texts ORDER BY id")) {
            assertTrue(rows.next());
            assertEquals(ascii, rows.getString(2));
            assertTrue(rows.next());
            assertEquals(korean, rows.getString(2));
        }
    }
}
