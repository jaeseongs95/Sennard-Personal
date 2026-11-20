// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;

import example.claimqueue.ClaimQueue;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The executable reference cases of the cs-engineering cards that are about a database (CONC-CLAIM-001, CONC-FENCE-002, DB-BOUNDARY-001, DB-ISOLATION-002,
 * DB-CONSTRAINT-003, DB-MIGRATION-004, and OS-LIFETIME-001 of the draft pool), run on the database the test run names ({@link BusinessDb}: PostgreSQL) against the example's candidate
 * ({@code skills/cs-engineering/assets/examples/claim-queue/candidate/ClaimQueue.java}, compiled with these tests by this module's build). No Sennard product class is involved:
 * these are witnesses for the cards, not measurements of a model and not a certification of the Sennard product or of a host.
 *
 * <p>The cases run in a schema of their own ({@value #SCHEMA}, one of the {@code sennard_*} schemas the test classes of this module drop at their start), created before each case
 * and dropped after the class. The claimants of a race are threads with a connection each: two connections to the server are as independent to the database as two
 * processes are. The one case that needs a client to vanish in the middle of a transaction starts a JVM of its own ({@link CsQueueCrashingWriter}).
 *
 * <p>The outcome of every case is kept. When the build is run with {@code -Psennard.cs.evidence.out=<file>} the class writes the summary of the run there, which is how the evidence file of
 * the example is made (see {@code worked-example.md}); the summary says what ran: the JDK, the database and the driver, the numbers of cases, the digest of the candidate and of this file.
 */
@Tag("business-db")
class CsQueueReferenceTest {
    static final String SCHEMA = "sennard_cs_queue_reference";
    private static final String TASKS = SCHEMA + ".tasks";
    private static final String EVIDENCE_PROPERTY = "sennard.cs.evidence.out";

    @RegisterExtension
    static final Outcomes OUTCOMES = new Outcomes();

    private static DataSource source;
    private static String candidateDigestBefore;
    private ClaimQueue queue;

    @BeforeAll
    static void connect() throws Exception {
        // The one business-database pool of the test JVM (six connections): every case borrows from it and gives back, so a race of more callers than that waits for a connection.
        source = BusinessDbExtension.pool();
        candidateDigestBefore = digest(candidateFile());
    }

    @AfterAll
    static void summarize() throws Exception {
        dropSchema();
        String written = System.getProperty(EVIDENCE_PROPERTY);
        if (written != null && !written.isBlank()) {
            Files.writeString(Path.of(written), pretty(OUTCOMES.summary()) + "\n", StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    void freshQueueWithOnePendingTask() throws SQLException {
        dropSchema();
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
        }
        queue = new ClaimQueue(source, SCHEMA);
        queue.initialize();
        execute("INSERT INTO " + TASKS + " (id, state) VALUES (1, 'pending')");
    }

    // -------------------------------------------------------------------------------------------------------------------------------- CONC-CLAIM-001

    @Test
    @DisplayName("CONC-CLAIM-001: six concurrent claimants, one connection each, give one winner")
    void sixConcurrentClaimantsHaveOneWinner() throws Exception {
        // The race is made, not hoped for: the queue gets the pool through a DataSource whose connections hold the first UPDATE of a claim back until all six claimants have
        // read the task as pending. The six claimants then write together, and the candidate's condition decides. (Six is the size of the pool: no connection is left over.)
        ClaimQueue gated = new ClaimQueue(new HoldsFirstUpdateUntil(new CyclicBarrier(6)).around(source), SCHEMA);
        List<Optional<ClaimQueue.Token>> results = race(6, index -> gated.claim("w" + index, 0, 100));
        assertEquals(1, results.stream().filter(Optional::isPresent).count(), results.toString());
        assertEquals("running", state());
        assertEquals("1", text("SELECT generation FROM " + TASKS));
    }

    @Test
    @DisplayName("CONC-CLAIM-001: negative case, a read followed by an unconditional write has two winners")
    void aReadFollowedByAnUnconditionalWriteHasTwoWinners() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Boolean> won = race(2, index -> {
            try (Connection connection = source.getConnection()) {
                long id;
                try (Statement read = connection.createStatement(); ResultSet row = read.executeQuery("SELECT id FROM " + TASKS + " WHERE state = 'pending'")) {
                    assertTrue(row.next(), "both claimants read the pending task before either writes");
                    id = row.getLong(1);
                }
                barrier.await(15, TimeUnit.SECONDS);
                try (PreparedStatement write = connection.prepareStatement("UPDATE " + TASKS + " SET state = 'running', owner = ? WHERE id = ?")) {
                    write.setString(1, "w" + index);
                    write.setLong(2, id);
                    return write.executeUpdate() == 1;
                }
            }
        });
        assertEquals(List.of(true, true), won, "the failure the card names: both think they own the task");
    }

    // -------------------------------------------------------------------------------------------------------------------------------- CONC-FENCE-002

    @Test
    @DisplayName("CONC-FENCE-002: an owner whose task was reassigned cannot publish")
    void aReassignedOwnerCannotPublish() throws SQLException {
        ClaimQueue.Token first = queue.claim("old", 0, 10).orElseThrow();
        ClaimQueue.Token second = queue.claim("new", 11, 10).orElseThrow();
        assertTrue(second.generation() > first.generation());
        assertFalse(queue.finish(first, "stale", 12));
        assertTrue(queue.finish(second, "current", 12));
        assertEquals("current", text("SELECT result FROM " + TASKS));
    }

    @Test
    @DisplayName("CONC-FENCE-002: an expired lease that was not reassigned is rejected")
    void anExpiredLeaseThatWasNotReassignedIsRejected() throws SQLException {
        ClaimQueue.Token first = queue.claim("old", 0, 10).orElseThrow();
        assertFalse(queue.finish(first, "late", 10), "a lease that ends at 10 is over at 10");
        assertEquals("running", state());
    }

    // -------------------------------------------------------------------------------------------------------------------------------- DB-BOUNDARY-001

    @Test
    @DisplayName("DB-BOUNDARY-001: a writer that crashes inside a transaction leaves no state behind")
    void aCrashedWriterLeavesNoUncommittedState() throws Exception {
        Path log = Files.createTempFile("cs-queue-crash-", ".log");
        // The heap of the child is stated, as the test JVM's is (the system property sennard.test.childHeap, which the build gives every test JVM: -Psennard.test.childHeap, else the test JVM's heap).
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx" + System.getProperty("sennard.test.childHeap")));
        for (String property : List.of(BusinessDb.URL_PROPERTY, BusinessDb.USER_PROPERTY, BusinessDb.PASSWORD_FILE_PROPERTY, BusinessDb.DRIVER_PATH_PROPERTY)) {
            if (System.getProperty(property) != null) {
                command.add("-D" + property + "=" + System.getProperty(property));
            }
        }
        command.addAll(List.of("-cp", System.getProperty("sennard.test.classpath"), CsQueueCrashingWriter.class.getName(), SCHEMA));
        Process crashing = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(crashing.waitFor(60, TimeUnit.SECONDS), "the writer ended");
        String output = read(log);
        Files.delete(log);
        assertEquals(CsQueueCrashingWriter.CRASH_EXIT, crashing.exitValue(), () -> "the writer did not crash as planned: " + output);
        assertEquals("pending", state(), "what the dead writer changed was never committed");
        // A read does not wait for a lock, so it would show 'pending' while the dead writer's transaction was still open. A conditional write does wait for it:
        // that it changes the row proves the server ended the transaction and rolled it back.
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            assertEquals(1, statement.executeUpdate("UPDATE " + TASKS + " SET state = 'running' WHERE id = 1 AND state = 'pending'"));
        }
    }

    // -------------------------------------------------------------------------------------------------------------------------------- DB-ISOLATION-002

    @Test
    @DisplayName("DB-ISOLATION-002: a repeatable-read transaction keeps its snapshot and cannot overwrite a concurrent update")
    void aRepeatableReadTransactionKeepsItsSnapshot() throws SQLException {
        try (Connection a = source.getConnection(); Connection b = source.getConnection()) {
            a.setAutoCommit(false);
            a.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            assertEquals("pending", state(a));
            try (Statement update = b.createStatement()) {
                update.executeUpdate("UPDATE " + TASKS + " SET state = 'running'");
            }
            assertEquals("pending", state(a), "the snapshot of a does not see what b committed");
            SQLException refused = assertThrows(SQLException.class, () -> {
                try (Statement overwrite = a.createStatement()) {
                    overwrite.executeUpdate("UPDATE " + TASKS + " SET state = 'done'");
                }
            });
            assertEquals("40001", refused.getSQLState(), "serialization failure: a write on a row another transaction changed after the snapshot");
            a.rollback();
            assertEquals("running", state(a));
        }
    }

    // -------------------------------------------------------------------------------------------------------------------------------- DB-CONSTRAINT-003

    @Test
    @DisplayName("DB-CONSTRAINT-003: the primary key and the check constraint reject invalid rows")
    void thePrimaryKeyAndTheCheckConstraintRejectInvalidRows() throws SQLException {
        SQLException duplicate = assertThrows(SQLException.class, () -> execute("INSERT INTO " + TASKS + " (id, state) VALUES (1, 'pending')"));
        // PostgreSQL names the kind of a violation by the SQLSTATE: 23505 unique, 23514 check.
        assertEquals("23505", duplicate.getSQLState(), "unique violation");
        SQLException invented = assertThrows(SQLException.class, () -> execute("UPDATE " + TASKS + " SET state = 'invented'"));
        assertEquals("23514", invented.getSQLState(), "check violation");
        assertEquals("pending", state());
    }

    // -------------------------------------------------------------------------------------------------------------------------------- DB-MIGRATION-004

    @Test
    @DisplayName("DB-MIGRATION-004: a rolled back migration leaves the table as it was, a repeated one changes nothing more, data stays")
    void aRolledBackMigrationLeavesTheTableAndARepeatedOneChangesNothingMore() throws SQLException {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + TASKS + " ADD COLUMN migrated BIGINT NOT NULL DEFAULT 0");
            }
            connection.rollback();
            assertFalse(columns(connection).contains("migrated"), "the rolled back column is gone");
            migrate(connection);
            migrate(connection);
            assertTrue(columns(connection).contains("migrated"));
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT id, state, migrated FROM " + TASKS)) {
                assertTrue(rows.next());
                assertEquals(List.of(1L, 0L), List.of(rows.getLong(1), rows.getLong(3)));
                assertEquals("pending", rows.getString(2));
                assertFalse(rows.next(), "the one row, once");
            }
        }
    }

    /** The migration step: it adds the column only when the table lacks it, so that running it again is harmless. */
    private static void migrate(Connection connection) throws SQLException {
        if (!columns(connection).contains("migrated")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + TASKS + " ADD COLUMN migrated BIGINT NOT NULL DEFAULT 0");
            }
        }
        connection.commit();
    }

    private static List<String> columns(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'tasks'")) {
            statement.setString(1, SCHEMA);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
            }
        }
        return names;
    }

    // -------------------------------------------------------------------------------------------------------------------------------- OS-LIFETIME-001 (draft pool)

    @Test
    @DisplayName("OS-LIFETIME-001: an invalid claim leaves the queue and its connections usable")
    void anInvalidClaimLeavesTheQueueUsable() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> queue.claim("", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> queue.claim("valid", 0, 0));
        assertTrue(queue.claim("valid", 0, 10).isPresent());
        assertEquals("running", state());
    }

    // -------------------------------------------------------------------------------------------------------------------------------- the fixture

    /** Runs {@code count} claimants at once, each on its own thread, and returns what they returned in order. */
    private static <T> List<T> race(int count, Claimant<T> claimant) throws Exception {
        CyclicBarrier start = new CyclicBarrier(count);
        try (ExecutorService threads = Executors.newFixedThreadPool(count)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int number = index;
                Callable<T> task = () -> {
                    start.await(15, TimeUnit.SECONDS);
                    return claimant.run(number);
                };
                futures.add(threads.submit(task));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    /**
     * Wraps a data source so that the first UPDATE each connection of a claim runs waits at a barrier (the claimants have all read by then). The connections are the pool's
     * own, behind a {@link Proxy} of the JDK that changes one call; every other call goes to the pool's connection, and closing gives it back to the pool.
     */
    private record HoldsFirstUpdateUntil(CyclicBarrier readsDone) {
        DataSource around(DataSource pool) {
            return proxy(DataSource.class, (self, method, arguments) -> {
                Object result = call(pool, method, arguments);
                return "getConnection".equals(method.getName()) ? holding((Connection) result) : result;
            });
        }

        private Connection holding(Connection real) {
            AtomicBoolean waited = new AtomicBoolean();
            return proxy(Connection.class, (self, method, arguments) -> {
                Object result = call(real, method, arguments);
                if ("prepareStatement".equals(method.getName()) && ((String) arguments[0]).startsWith("UPDATE")) {
                    PreparedStatement update = (PreparedStatement) result;
                    return proxy(PreparedStatement.class, (statement, invoked, invokedArguments) -> {
                        if ("executeUpdate".equals(invoked.getName()) && waited.compareAndSet(false, true)) {
                            readsDone.await(15, TimeUnit.SECONDS);
                        }
                        return call(update, invoked, invokedArguments);
                    });
                }
                return result;
            });
        }

        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(CsQueueReferenceTest.class.getClassLoader(), new Class<?>[] {type}, handler));
        }

        private static Object call(Object target, Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }

    @FunctionalInterface
    private interface Claimant<T> {
        T run(int index) throws Exception;
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static String text(String sql) throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getString(1);
        }
    }

    private static String state() throws SQLException {
        return text("SELECT state FROM " + TASKS);
    }

    private static String state(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery("SELECT state FROM " + TASKS)) {
            assertTrue(row.next());
            return row.getString(1);
        }
    }

    /** Removes the schema of the cases when it is there; the only thing of the database this class removes. */
    private static void dropSchema() throws SQLException {
        try (Connection connection = source.getConnection(); PreparedStatement exists = connection.prepareStatement("SELECT schema_name FROM information_schema.schemata WHERE schema_name = ?")) {
            exists.setString(1, SCHEMA);
            try (ResultSet found = exists.executeQuery(); Statement drop = connection.createStatement()) {
                if (found.next()) {
                    drop.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
                }
            }
        }
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------------------------------------------------------------- the evidence

    private static Path candidateFile() {
        return Path.of(System.getProperty("sennard.cs.example")).resolve("candidate/ClaimQueue.java");
    }

    private static String digest(Path file) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static String pretty(ObjectNode summary) {
        DefaultIndenter indent = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter(Separators.createDefaultInstance()
                .withObjectNameValueSpacing(Separators.Spacing.AFTER).withObjectEmptySeparator("").withArrayEmptySeparator(""))
                .withObjectIndenter(indent).withArrayIndenter(indent);
        return JsonMapper.builder().build().writer().with(printer).writeValueAsString(summary);
    }

    /** Keeps what happened to each case, for the summary of the run. */
    static final class Outcomes implements TestWatcher {
        private final List<String> caseIds = new ArrayList<>();
        private int failures;
        private int skipped;

        @Override
        public synchronized void testSuccessful(ExtensionContext context) {
            caseIds.add(caseId(context));
        }

        @Override
        public synchronized void testFailed(ExtensionContext context, Throwable cause) {
            caseIds.add(caseId(context));
            failures++;
        }

        @Override
        public synchronized void testAborted(ExtensionContext context, Throwable cause) {
            skipped++;
        }

        @Override
        public synchronized void testDisabled(ExtensionContext context, Optional<String> reason) {
            skipped++;
        }

        /** The card's ID (the display name up to the colon) and the method of the case. */
        private static String caseId(ExtensionContext context) {
            String display = context.getDisplayName();
            return display.substring(0, display.indexOf(':')) + " " + context.getRequiredTestMethod().getName();
        }

        synchronized ObjectNode summary() throws Exception {
            ObjectNode summary = JsonMapper.builder().build().createObjectNode();
            String product;
            String database;
            String driver;
            try (Connection connection = source.getConnection()) {
                DatabaseMetaData metadata = connection.getMetaData();
                product = metadata.getDatabaseProductName();
                database = product + " " + metadata.getDatabaseProductVersion();
                driver = metadata.getDriverName() + " " + metadata.getDriverVersion();
            }
            summary.put("scope", "local " + product + " reference candidate; not Sennard product integration");
            summary.put("jdk", System.getProperty("java.runtime.version") + " (" + System.getProperty("java.vendor") + ")");
            summary.put("database", database);
            summary.put("driver", driver);
            summary.put("concurrency", "claimants are threads with one JDBC connection each; the crash case is a JVM of its own halted inside a transaction");
            summary.put("testsRun", caseIds.size() + skipped);
            summary.put("failures", failures);
            summary.put("errors", 0);
            summary.put("skipped", skipped);
            String candidateAfter = digest(candidateFile());
            summary.put("status", failures == 0 && skipped == 0 && candidateDigestBefore.equals(candidateAfter) ? "PASS" : "FAIL");
            summary.put("candidateRawDigest", candidateDigestBefore);
            summary.put("candidateUnchangedDuringRun", candidateDigestBefore.equals(candidateAfter));
            summary.put("testScriptDigest", digest(Path.of(System.getProperty("sennard.cs.test.script"))));
            ArrayNode ids = summary.putArray("caseIds");
            caseIds.stream().sorted().forEach(ids::add);
            return summary;
        }
    }
}
