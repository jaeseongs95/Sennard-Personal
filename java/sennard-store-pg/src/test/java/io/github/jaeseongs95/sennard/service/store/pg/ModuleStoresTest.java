// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.zaxxer.hikari.HikariDataSource;

import io.github.jaeseongs95.sennard.service.secret.SecretSealException;
import io.github.jaeseongs95.sennard.service.store.AttemptState;
import io.github.jaeseongs95.sennard.service.store.JobState;
import io.github.jaeseongs95.sennard.service.store.LeaseState;
import io.github.jaeseongs95.sennard.service.store.ProposalState;
import io.github.jaeseongs95.sennard.service.store.RootState;
import io.github.jaeseongs95.sennard.service.store.RunState;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.UpdateComparison;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;
import io.github.jaeseongs95.sennard.service.store.modules.JevUsageService;
import io.github.jaeseongs95.sennard.service.store.modules.JevUsageStore;
import io.github.jaeseongs95.sennard.service.store.modules.SkillUsageService;
import io.github.jaeseongs95.sennard.service.store.modules.SkillUsageStore;
import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;
import io.github.jaeseongs95.sennard.service.store.modules.TrustStore;
import io.github.jaeseongs95.sennard.service.store.jobs.JobService;
import io.github.jaeseongs95.sennard.service.store.jobs.JobStore;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore;

/**
 * The module schemas V3 to V10 and V15 and the module stores, on the database the test run names ({@link BusinessDb}: PostgreSQL) through the one database user the product connects as.
 * Fixture rows that the stores could not write themselves (old times, full counters, tampering) and the checks of the
 * catalog are written and read with a second connection of the same user ({@link BusinessDb#connect()}). Every test starts from empty business tables
 * ({@link BusinessDbTest}), so the rows and counters of one test are never another test's.
 *
 * <p>One class where there were three (an abstract body and an embedded and an external subclass): the two subclasses ran the same cases against two kinds of server to
 * show that the row-level policies held for an owner that is not a superuser. With one user and no policy there is nothing that differs between the servers, so a
 * second run of the same cases on a second server proved nothing the first did not. (The cases that needed a server of their own, another encoding and TLS, went with the
 * server the tests used to start: the shared database is UTF-8, and TLS is a setting of the driver the user puts in the URL.)
 *
 * <h2>Old test to new test</h2>
 * <pre>
 * everyModuleTableIsForcedHasBothPoliciesAndStartsItsKeyWithNsAndAccount   -> noBusinessTableHasAnAccountOrNsColumnOrAHostingTable and everyModuleTableHasAPrimaryKey (read from the catalog),
 *                                                                              noBusinessTableHasRowLevelSecurityAPolicyOrAFunction (what the old test asserted is gone, so its absence is)
 * (new)                                                                    -> theModuleSchemaTableHoldsOnlyTheModuleAndItsVersion (the module's version is all the table records)
 * (new)                                                                    -> noModuleHasALockTableAndNoStoreTakesALock
 * theGlossaryTablesHoldTheReviewedTermsAndTheServiceReadsThemOnly          -> theGlossaryTablesHoldTheReviewedTerms (the content; the read-only boundary is the architecture rule w-sql-write-verb)
 * </pre>
 * New here: {@code aWriterWaitingForARowLockEndsInStoreTimeoutWhenItsTransactionRunsOut} (I6), and the two-connection cases of the writes that take no module lock.
 */
@BusinessDbTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModuleStoresTest {
    private static final String T0 = "2026-01-01T00:00:00.000Z";
    private static final String T1 = "2026-01-02T00:00:00.000Z";
    private static final String CUT = "2026-03-01T00:00:00.000Z";
    private static final String LATER = "2026-04-01T00:00:00.000Z";
    /** The tables of the eight modules, by module. All of them are in the one schema {@code sennard}; the tables of the other modules are in {@link #OTHER_TABLES}. */
    private static final Map<String, Set<String>> TABLES = Map.of(
            "board", Set.of("board_sessions"),
            "usage", Set.of("jev_usage_daily", "skill_calls", "skill_call_ids"),
            "trust", Set.of("trust_metadata", "input_source_receipts"),
            "context", Set.of("admin_log", "admin_log_requests", "admin_log_archive", "admin_resets", "admin_proposals", "role_contexts", "role_context_history", "session_seen", "context_deliveries", "jev_keys"),
            "continuity", Set.of("continuity_metadata", "continuity_tasks", "continuity_current_memory", "continuity_memory_history", "continuity_requests", "continuity_observations", "continuity_tombstones", "continuity_cleanup_backup"),
            "workflow", Set.of("workflow_metadata", "workflow_runs", "plugin_update_state", "convergence_roots", "convergence_root_identities", "convergence_epochs", "convergence_leases", "convergence_issued_leases", "convergence_active_surfaces", "convergence_attempts",
                    "convergence_reviews", "workflow_attempt_links", "state_cleanup_claims", "execution_observation_claims", "root_approval_decisions", "workflow_cleanup_backup", "workflow_run_skills"),
            "messaging", Set.of("messages", "prepared_messages", "wake_paths", "input_observations", "peer_wait_snapshots", "native_wake_reservations"),
            "core", Set.of("background_job"));

    private HikariDataSource pool;
    private TestStores store;

    @BeforeAll
    void start() {
        // the business schemas are migrated anew by @BusinessDbTest before this method; the pool is the test JVM's one, which the cases that race eight callers queue for
        pool = BusinessDb.pool();
        store = TestStores.of(pool);
    }

    // ------------------------------------------------------------------------------------------------------------------ fixtures

    private void owner(String... statements) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    private long ownerLong(String sql) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement(); ResultSet rows = s.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private String ownerString(String sql) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement(); ResultSet rows = s.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static long num(Object value) {
        return ((Number) value).longValue();
    }

    /** Runs {@code n} tasks at the same moment and returns their results; a task that throws fails the test with its cause. */
    private static <R> List<R> parallel(int n, IntFunction<R> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<R>> tasks = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                tasks.add(pool.submit(() -> {
                    go.await();
                    return work.apply(index);
                }));
            }
            go.countDown();
            List<R> results = new ArrayList<>();
            for (Future<R> task : tasks) {
                results.add(task.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------------------------------------------------- schema

    /** The tables of the identity, glossary, prompt, journal, attachment and operation modules and the version table, which the eight modules of {@link #TABLES} do not have. */
    private static final Set<String> OTHER_TABLES = Set.of("active_generation", "call_admission", "managed_child_identity", "session_generation", "session_ref", "module_schema",
            "glossary_metadata", "glossary_entries", "glossary_forms", "recap_nudge", "transcript_anchor", "turn_recap", "user_prompt",
            "baseline_file", "change_event", "journal_workflow", "observed_file", "plan_file", "plan_revision", "scan_failure", "write_root",
            "attachment", "attachment_file", "attachment_storage", "upload_staging", "operation_receipt");
    /** The tables of the eight modules ({@link #TABLES}) as the list of an SQL {@code IN}. */
    private static final String MODULE_TABLES = quoted(TABLES.values().stream().flatMap(Set::stream).collect(java.util.stream.Collectors.toSet()));

    private static String quoted(Set<String> names) {
        return names.stream().sorted().map(name -> "'" + name + "'").collect(java.util.stream.Collectors.joining(", "));
    }

    /** The one schema {@code sennard} holds the tables of all modules and nothing else but the two migration histories: a table name is taken once, with a module prefix where two modules had the same. */
    @Test
    void theSchemaHoldsExactlyTheDesignedTables() throws Exception {
        Set<String> found = new TreeSet<>();
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement();
                ResultSet rows = s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_type = 'BASE TABLE' AND table_schema = 'sennard' "
                        + "AND table_name NOT IN ('expand_history', 'contract_history')")) {
            while (rows.next()) {
                found.add(rows.getString(1));
            }
        }
        Set<String> designed = new TreeSet<>(OTHER_TABLES);
        TABLES.values().forEach(designed::addAll);
        assertEquals(designed, found);
        assertEquals(48 + OTHER_TABLES.size(), found.size());
        assertEquals(48, TABLES.values().stream().mapToInt(Set::size).sum());
        assertFalse(found.contains("continuity_delta_state"));
        assertFalse(found.contains("call_admissions"));
        assertFalse(found.contains("cleanup_backup"), "the two modules that kept a backup table have one each, named by the module");
        assertEquals(0, ownerLong("SELECT count(*) FROM information_schema.schemata WHERE schema_name LIKE 'sennard!_%' ESCAPE '!'"), "no schema beside sennard");
    }

    /**
     * The personal edition has one user of the database and one set of rows: no table of the {@code sennard} schema has an {@code account_id} or an {@code ns} column, and the identity
     * area's account, installation and quota tables and the meta schema's instance table, which held those, do not exist.
     */
    @Test
    void noBusinessTableHasAnAccountOrNsColumnOrAHostingTable() throws Exception {
        assertEquals(0, ownerLong("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sennard' AND column_name IN ('account_id', 'ns')"));
        assertEquals(0, ownerLong("SELECT count(*) FROM information_schema.tables WHERE (table_schema = 'sennard' AND table_name IN ('account', 'installation', 'account_quota')) "
                + "OR (table_schema = 'sennard' AND table_name = 'instances')"));
        // the tables the check could be about are there: the identity area's session tables and the meta schema's module table
        assertEquals(1, ownerLong("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'sennard' AND table_name = 'session_ref'"));
        assertEquals(1, ownerLong("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'sennard' AND table_name = 'module_schema'"));
    }

    /**
     * The module's version is all {@code sennard.module_schema} records: its columns are {@code module} and {@code version}, and the minimum client version it once held for a hosted
     * server is not a column.
     */
    @Test
    void theModuleSchemaTableHoldsOnlyTheModuleAndItsVersion() throws Exception {
        assertEquals(0, ownerLong("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sennard' AND table_name = 'module_schema' AND column_name = 'min_client_version'"));
        assertEquals(2, ownerLong("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sennard' AND table_name = 'module_schema' AND column_name IN ('module', 'version')"));
        assertEquals(2, ownerLong("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sennard' AND table_name = 'module_schema'"), "no other column");
    }

    /**
     * What a table of the eight modules is keyed by is its own: every one of the 48 has a primary key (the key a statement's WHERE reaches, and what the optimistic updates
     * and the unique rows of the stores rest on). Read from the standard catalog ({@code information_schema}), no {@code pg_*} tables.
     */
    @Test
    void everyModuleTableHasAPrimaryKey() throws Exception {
        assertEquals(48, ownerLong("SELECT count(*) FROM information_schema.tables t WHERE t.table_type = 'BASE TABLE' AND t.table_schema = 'sennard' AND t.table_name IN (" + MODULE_TABLES + ")"));
        assertEquals(48, ownerLong("SELECT count(*) FROM information_schema.table_constraints c WHERE c.constraint_type = 'PRIMARY KEY' AND c.table_schema = 'sennard' AND c.table_name IN (" + MODULE_TABLES + ")"),
                "one primary key for each of the 48 tables");
        assertEquals(0, ownerLong("SELECT count(*) FROM information_schema.tables t WHERE t.table_type = 'BASE TABLE' AND t.table_schema = 'sennard' AND t.table_name IN (" + MODULE_TABLES + ") "
                + "AND NOT EXISTS (SELECT 1 FROM information_schema.table_constraints c WHERE c.table_schema = t.table_schema AND c.table_name = t.table_name AND c.constraint_type = 'PRIMARY KEY')"),
                "a table without a primary key");
    }

    /**
     * What the old test of the policies asserted (every table forced, the row-level policies and the functions that bound a frame to a connection) is gone with the database's
     * part in the isolation (DB stage A1): nothing in the {@code sennard} schema is row-level secured, has a policy or is a function of the database, and the schemas are owned by the one user.
     */
    @Test
    void noBusinessTableHasRowLevelSecurityAPolicyOrAFunction() throws Exception {
        // The functions and the triggers are asked of the standard catalog.
        assertEquals(0, ownerLong("SELECT count(*) FROM information_schema.routines WHERE routine_schema = 'sennard'"), "no function or procedure in a business schema");
        assertEquals(0, ownerLong("SELECT count(*) FROM information_schema.triggers WHERE trigger_schema = 'sennard'"), "no trigger in a business schema");
        // Row-level security, policies and schema owners are asked of PostgreSQL's own catalog.
        assertEquals(0, ownerLong("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'sennard' AND c.relkind IN ('r', 'p') AND (c.relrowsecurity OR c.relforcerowsecurity)"));
        assertEquals(0, ownerLong("SELECT count(*) FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'sennard'"));
        assertEquals(0, ownerLong("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'sennard'"));
        assertEquals(0, ownerLong("SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'sennard' AND NOT t.tgisinternal"));
        assertEquals(0, ownerLong("SELECT count(*) FROM pg_namespace n WHERE n.nspname = 'sennard' AND n.nspowner <> (SELECT oid FROM pg_roles WHERE rolname = current_user)"),
                "every business schema belongs to the one database user");
    }

    /**
     * Messaging is the only module with foreign keys into identity's session table. Within identity, the V26 managed-child table references the child session. An operation receipt
     * (V20) names its caller by session reference as text.
     */
    @Test
    void moduleVersionsAreRecordedAndOnlyMessagingReferencesIdentityAcrossModules() throws Exception {
        Map<String, Long> versions = new TreeMap<>();
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement(); ResultSet rows = s.executeQuery("SELECT module, version FROM sennard.module_schema")) {
            while (rows.next()) {
                versions.put(rows.getString(1), rows.getLong(2));
            }
        }
        assertEquals(new TreeMap<>(Map.ofEntries(Map.entry("board", 1L), Map.entry("usage", 1L), Map.entry("trust", 1L), Map.entry("context", 2L), Map.entry("continuity", 5L), Map.entry("workflow", 10L),
                Map.entry("messaging", 5L), Map.entry("glossary", 1L), Map.entry("prompt", 1L), Map.entry("journal", 1L), Map.entry("attachment", 1L), Map.entry("operation", 1L))), versions);
        assertEquals(0, ownerLong(foreignKeysToTheSessionTable("t.relname NOT IN (" + MODULE_TABLES_OF_THE_MESSAGING
                + ", 'active_generation', 'call_admission', 'managed_child_identity', 'session_generation', 'session_ref')")));
        assertEquals(1, ownerLong(foreignKeysToTheSessionTable("t.relname = 'managed_child_identity'")), "the managed child references its own session");
        assertEquals(7, ownerLong(foreignKeysToTheSessionTable("t.relname IN (" + MODULE_TABLES_OF_THE_MESSAGING + ")")),
                "prepared_messages (sender, target), messages (sender, target), wake_paths, input_observations and native_wake_reservations");
        assertEquals(0, ownerLong("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sennard' AND (table_name LIKE 'person%' OR table_name IN ('ledger', 'cell_usage') OR column_name IN ('login', 'email'))"));
    }

    /**
     * The count of foreign keys, of the tables {@code tableCondition} names (a condition on {@code t.relname}), that refer to {@code sennard.session_ref}. The
     * standard catalog tells which table a foreign key refers to only through the unique constraint it names; so the referenced table is read from PostgreSQL's
     * {@code pg_constraint}.
     */
    private static String foreignKeysToTheSessionTable(String tableCondition) {
        return "SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace n ON n.oid = c.connamespace WHERE c.contype = 'f' AND n.nspname = 'sennard' AND "
                + tableCondition + " AND c.confrelid = to_regclass('sennard.session_ref')";
    }

    /** The tables of the messaging module as the list of an SQL {@code IN}. */
    private static final String MODULE_TABLES_OF_THE_MESSAGING = quoted(TABLES.get("messaging"));

    /**
     * The glossary (V13, V14 and V16__korean_prose_glossary): the 265 reviewed terms of version 1.3.0 are in the tables once the migrations have run, and the schema has no
     * row-level security (reference data). The counts are the ones of the data script: 177 protect, 68 allow, 7 prefer, 13 avoid, one inactive.
     *
     * <p>Was also: the service's role read the tables and could not write them (GRANT SELECT only; a DELETE was refused as not permitted). The database has one user and no GRANT now, so that boundary
     * is a rule of the code: the architecture check {@code w-sql-write-verb} (sennard-arch-checks, SqlRules) refuses an INSERT, UPDATE or DELETE on an {@code sennard} table in any SQL string
     * of the product, and the service that reads the tables (JdbcGlossaryCatalog, in sennard-server, which this module cannot use) only has SELECT statements. This test therefore
     * judges the content and the counts only.
     */
    @Test
    void theGlossaryTablesHoldTheReviewedTerms() throws Exception {
        assertEquals("1.3.0", ownerText("SELECT version FROM sennard.glossary_metadata WHERE glossary_id = 'korean-prose-core'"));
        assertEquals(265, ownerLong("SELECT count(*) FROM sennard.glossary_entries"));
        assertEquals(270, ownerLong("SELECT count(*) FROM sennard.glossary_forms"));
        assertEquals(177, ownerLong("SELECT count(*) FROM sennard.glossary_entries WHERE policy = 'PROTECT'"));
        assertEquals(68, ownerLong("SELECT count(*) FROM sennard.glossary_entries WHERE policy = 'ALLOW'"));
        assertEquals(7, ownerLong("SELECT count(*) FROM sennard.glossary_entries WHERE policy = 'PREFER'"));
        assertEquals(13, ownerLong("SELECT count(*) FROM sennard.glossary_entries WHERE policy = 'AVOID'"));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.glossary_entries WHERE status = 'RETIRED'"));
        assertEquals(264, ownerLong("SELECT count(*) FROM sennard.glossary_entries WHERE status = 'ACTIVE'"));
        assertEquals(0, ownerLong("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'sennard' AND c.relkind = 'r' AND c.relrowsecurity"));
        // a read through the service's pool, which is the connection the product reads the glossary with
        try (Connection service = pool.getConnection(); Statement s = service.createStatement(); ResultSet rows = s.executeQuery("SELECT count(*) FROM sennard.glossary_entries")) {
            rows.next();
            assertEquals(265, rows.getLong(1), "the service's pool reads the glossary");
        }
    }

    /** CONTENT-2: a narrative or path column is never matched, sorted or uniquely indexed in SQL. */
    @Test
    void noStoreStatementJudgesByANarrativeOrPathColumn() throws Exception {
        Path root = Path.of("src/main");
        Pattern like = Pattern.compile("\\bI?LIKE\\b");
        String columns = "(cwd|summary|role|objective|body|change_text|workspace_locator|memory_json|root_json|lease_json|proposal_json|receipt_json|record_json|review_json|outcome_json|result_json|input_json|identity_json|client_data_json)";
        Pattern ordered = Pattern.compile("ORDER BY[^\"\n]*\\b" + columns + "\\b");
        Pattern unique = Pattern.compile("UNIQUE[^;\n]*\\([^)]*\\b" + columns + "\\b");
        List<Path> files;
        try (Stream<Path> walk = Stream.concat(Files.walk(root.resolve("java/io/github/jaeseongs95/sennard/service/store/modules")), Files.walk(root.resolve("resources/db/expand/modules")))) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        assertEquals(30, files.size(), "the scan sees the 14 store sources and the sixteen scripts (V3 to V10, V15, V18, V19, V21 to V25)");
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            String text = Files.readString(file);
            if (like.matcher(text).find() || ordered.matcher(text).find() || unique.matcher(text).find()) {
                violations.add(file.toString());
            }
        }
        assertEquals(List.of(), violations);
    }

    private String ownerText(String sql) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement(); ResultSet rows = s.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    /**
     * The one TIMESTAMP value {@code sql} selects, as the date, the time and the fraction its fields hold, without the trailing zeros of the fraction ({@code 2026-04-01 00:00:00},
     * {@code 2030-01-01 00:00:00.123}). It is read as a {@link LocalDateTime}, the fields themselves, so no zone moves it and no cast to text, whose form is the DBMS's own, is needed.
     */
    private String ownerTimestampText(String sql) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement(); ResultSet rows = s.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            LocalDateTime fields = rows.getObject(1, LocalDateTime.class);
            String text = fields.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            if (fields.getNano() != 0) {
                text += "." + String.format(java.util.Locale.ROOT, "%09d", fields.getNano()).replaceAll("0+$", "");
            }
            return text;
        }
    }

    /** A TIMESTAMP literal of an instant written as the stores take it ({@code 2026-04-01T00:00:00.000Z}): the UTC fields, in the form every DBMS reads. */
    private static String timestampLiteral(String instant) {
        return "TIMESTAMP '" + instant.replace('T', ' ').replace("Z", "") + "'";
    }

    // ------------------------------------------------------------------------------------------------------------- usage
    // (The board's cases moved to BoardServiceTest with its rewrite: role and cwd kept, isolation, prune, parallel writers.)

    /** D8: a sum read as a number matches what was added. */
    @Test
    void jevUsageAddsUpPerDayAndModel() throws Exception {
        JevUsageService usage = store.context().getBean(JevUsageService.class);
        assertEquals(new JevUsageStore.Totals(0, 0.0), usage.totals());
        usage.add(Instant.parse(T0), "m", 10, 20, 0.5); // the first call of the day and the model writes the row, with the call counted in it
        usage.add(Instant.parse(T0), "m", 1, 2, 0.25); // the second one counts into that row
        usage.add(Instant.parse(T1), "m", 1, 2, 0.25);
        assertEquals(new JevUsageStore.Totals(3, 1.0), usage.totals());
        assertEquals(2, ownerLong("SELECT calls FROM sennard.jev_usage_daily WHERE model_id = 'm' AND day = " + timestampLiteral(T0)), "the first call is counted once in the row it wrote");
        assertEquals(11, ownerLong("SELECT input_tokens FROM sennard.jev_usage_daily WHERE model_id = 'm' AND day = " + timestampLiteral(T0)));
    }

    @Test
    void aSkillCallCountsOnceAndOldRowsLeaveOnTheNextWrite() throws Exception {
        SkillUsageService usage = store.context().getBean(SkillUsageService.class);
        Instant at = Instant.parse(T1);
        usage.record(new SkillUsageStore.Call("claude", "s1", "skill-a", false, "tool", "call-1", at));
        usage.record(new SkillUsageStore.Call("claude", "s1", "skill-a", false, "tool", "call-1", at));
        usage.record(new SkillUsageStore.Call("codex", "s2", "skill-b", true, "tool", "call-2", at));
        assertEquals(List.of("skill-a"), usage.calledInSession("s1"));
        SkillUsageStore.Summary summary = usage.summary(7, at);
        assertEquals(2, summary.rows().size());
        assertEquals(1, summary.rows().get(0).calls());
        assertTrue(summary.rows().get(1).subagent());
        usage.record(new SkillUsageStore.Call("codex", "s3", "skill-c", false, "tool", "call-3", at.plusSeconds(91 * 24 * 3600)));
        assertEquals(List.of(), usage.calledInSession("s1"), "older than the retention");
    }

    /**
     * I2, two first calls of a day and a model at the same moment: one transaction has counted its call (the UPDATE found no row, so it wrote the day's row with the call in it) and has
     * not committed; a second call finds no row to count into either, its INSERT waits for the first to end and then meets the first one's key. The second call ends in the translated
     * unique violation (the limit that is accepted): nothing catches it to go on and nothing runs the call again. The table has one row, with the first call counted.
     */
    @Test
    void i2_ofTwoFirstCallsOfOneKeyTheSecondEndsInTheUniqueViolationAndOneRowIsLeft() throws Exception {
        JevUsageService usage = store.context().getBean(JevUsageService.class);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderBackend = new AtomicLong(); // the database's identifier of the first writer's connection, taken inside its transaction
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(() -> store.plain(() -> {
                holderBackend.set(LockWaits.backendOfThisTransaction(BusinessDb.pool()));
                usage.add(Instant.parse(T0), "i2-model", 10, 20, 0.5);
                holding.countDown();
                awaitRelease(release);
                return null;
            }));
            assertTrue(holding.await(20, TimeUnit.SECONDS), "the first writer has written the key and holds it");
            Future<Object> second = pool.submit(() -> {
                usage.add(Instant.parse(T0), "i2-model", 1, 2, 0.25);
                return null;
            });
            LockWaits.awaitWaiterBlockedBy(holderBackend.get(), Duration.ofSeconds(20), "the second writer waits for the key of the first");
            assertFalse(second.isDone(), "the second call waits for the first one's commit");
            release.countDown();
            first.get(30, TimeUnit.SECONDS);
            ExecutionException lost = assertThrows(ExecutionException.class, () -> second.get(30, TimeUnit.SECONDS));
            assertEquals(StoreCode.STORE_UNIQUE_VIOLATION, assertInstanceOf(StoreException.class, lost.getCause()).code());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.jev_usage_daily WHERE model_id = 'i2-model'"), "one row for the key");
        assertEquals(1, ownerLong("SELECT calls FROM sennard.jev_usage_daily WHERE model_id = 'i2-model'"), "the first call is in it; the second one was not run again");
        assertEquals(10, ownerLong("SELECT input_tokens FROM sennard.jev_usage_daily WHERE model_id = 'i2-model'"));
    }

    /**
     * I2 again where the key is a host's call id: two records of one call at the same moment race for its row in the table of call ids. The id's row is the first thing a record
     * writes, so the second one ends in the translated unique violation before it wrote anything (the limit that is accepted); the call is counted once.
     */
    @Test
    void i2_ofTwoRecordsOfOneCallIdTheSecondEndsInTheUniqueViolationAndTheCallIsCountedOnce() throws Exception {
        SkillUsageService usage = store.context().getBean(SkillUsageService.class);
        Instant at = Instant.parse(T1);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderBackend = new AtomicLong(); // the database's identifier of the first record's connection, taken inside its transaction
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(() -> store.plain(() -> {
                holderBackend.set(LockWaits.backendOfThisTransaction(BusinessDb.pool()));
                usage.record(new SkillUsageStore.Call("claude", "i2-session", "skill-a", false, "tool", "i2-call", at));
                holding.countDown();
                awaitRelease(release);
                return null;
            }));
            assertTrue(holding.await(20, TimeUnit.SECONDS));
            Future<Object> second = pool.submit(() -> {
                usage.record(new SkillUsageStore.Call("claude", "i2-session", "skill-a", false, "tool", "i2-call", at));
                return null;
            });
            LockWaits.awaitWaiterBlockedBy(holderBackend.get(), Duration.ofSeconds(20), "the second record waits for the id of the first");
            assertFalse(second.isDone(), "the second record waits for the first one's commit");
            release.countDown();
            first.get(30, TimeUnit.SECONDS);
            ExecutionException lost = assertThrows(ExecutionException.class, () -> second.get(30, TimeUnit.SECONDS));
            assertEquals(StoreCode.STORE_UNIQUE_VIOLATION, assertInstanceOf(StoreException.class, lost.getCause()).code());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.skill_call_ids WHERE session_id = 'i2-session'"));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.skill_calls WHERE session_id = 'i2-session'"), "the call is counted once");
    }

    // ------------------------------------------------------------------------------------------------------------------- trust

    @Test
    void theFirstReceiptOfAnEventWinsAndADifferentOneIsAConflict() throws Exception {
        TrustStore trust = store.repository(TrustStore.class);
        SecretValue key = store.write(trust, () -> {
            trust.insertSigningKey(T0);
            return trust.requireSigningKey();
        });
        TrustStore.Receipt receipt = new TrustStore.Receipt("r1", "claude", "s1", "e1", "user", "none", T0, T1, "{\"a\":1}");
        assertEquals("{\"a\":1}", store.write(trust, () -> trust.recordInputSource(receipt, prior -> true)));
        TrustStore.Receipt second = new TrustStore.Receipt("r2", "claude", "s1", "e1", "user", "none", T1, T1, "{\"a\":2}");
        assertEquals("{\"a\":1}", store.write(trust, () -> trust.recordInputSource(second, prior -> true)), "same metadata returns what is stored");
        assertEquals(StoreCode.REQUEST_CONFLICT, assertThrows(StoreException.class, () -> store.write(trust, () -> trust.recordInputSource(second, prior -> false))).code());
        assertEquals("{\"a\":1}", store.write(trust, () -> trust.latestInputSource("claude", "s1")));
        TrustStore.KeyAndReceipt read = store.snapshot(() -> trust.readForVerification("r1"));
        assertEquals(key, read.signingKey());
        assertEquals("{\"a\":1}", read.receiptJson());
        assertFalse(read.toString().contains(new String(key.reveal())), "the text of the stored key and receipt has no key");
    }

    // ----------------------------------------------------------------------------------------------------------------- context

    @Test
    void theRoleBodyIsReplacedOnlyByTheHolderOfItsToken() throws Exception {
        ContextLayerStore context = store.repository(ContextLayerStore.class);
        ContextLayerStore.RoleSaved first = assertInstanceOf(ContextLayerStore.RoleSaved.class, store.write(context, () -> context.saveRole("lead", null, "one", "me", T0)));
        ContextLayerStore.RoleStale stale = assertInstanceOf(ContextLayerStore.RoleStale.class, store.write(context, () -> context.saveRole("lead", null, "two", "me", T1)));
        assertEquals(first.record().stateToken(), stale.actualStateToken());
        assertInstanceOf(ContextLayerStore.RoleSaved.class, store.write(context, () -> context.saveRole("lead", first.record().stateToken(), "two", "me", T1)));
        assertEquals("two", store.write(context, () -> context.readRole("lead").body()));
    }

    @Test
    void aJevKeyIsStoredSealedAndReadBackOpenedAndBoundToItsRow() throws Exception {
        ContextLayerStore context = store.repository(ContextLayerStore.class);
        store.write(context, () -> {
            context.putJevKey(new ContextLayerStore.JevKey("JEV_DB", "sha256:aa", SecretValue.of("sk-db-fixture"), T0));
            return null;
        });
        String stored = ownerString("SELECT sealed FROM sennard.jev_keys WHERE name = 'JEV_DB'");
        assertFalse(stored.contains("sk-db-fixture"), "the column holds no plain text");
        assertEquals(SecretValue.of("sk-db-fixture"), store.write(context, () -> context.jevKey("JEV_DB").secret()));
        assertEquals("sha256:aa", store.write(context, () -> context.jevKeyFingerprint("JEV_DB")));
        assertNull(store.write(context, () -> context.jevKeyFingerprint("JEV_NONE")));
        assertNull(store.write(context, () -> context.jevKey("JEV_NONE")));
        // a second registration of the name replaces the value (the update path of the row write)
        store.write(context, () -> {
            context.putJevKey(new ContextLayerStore.JevKey("JEV_DB", "sha256:bb", SecretValue.of("sk-db-second"), T1));
            return null;
        });
        assertEquals(SecretValue.of("sk-db-second"), store.write(context, () -> context.jevKey("JEV_DB").secret()));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.jev_keys WHERE name = 'JEV_DB'"));
        // the sealed value copied to another row (another name) does not open there
        owner("INSERT INTO sennard.jev_keys (name, fingerprint, sealed, stored_at) VALUES ('JEV_MOVED', 'sha256:bb', '"
                + ownerString("SELECT sealed FROM sennard.jev_keys WHERE name = 'JEV_DB'") + "', TIMESTAMP '2026-01-01 00:00:00')");
        assertThrows(SecretSealException.class, () -> store.write(context, () -> context.jevKey("JEV_MOVED")));
        // and under another fingerprint in the same row
        owner("UPDATE sennard.jev_keys SET fingerprint = 'sha256:cc' WHERE name = 'JEV_DB'");
        assertThrows(SecretSealException.class, () -> store.write(context, () -> context.jevKey("JEV_DB")));
    }

    @Test
    void aResetMovesTheLogAwayAndANewProposalReplacesThePendingOne() throws Exception {
        ContextLayerStore context = store.repository(ContextLayerStore.class);
        ContextLayerStore.LogRecord record = new ContextLayerStore.LogRecord(0, "global", "edit", "cred", "ad", "cd", "sig", "hash", T0);
        ContextLayerStore.LogRecord stored = store.write(context, () -> context.appendRecord(record, "req-1"));
        assertTrue(stored.seq() > 0);
        assertEquals(stored.seq(), num(store.write(context, () -> context.recordByRequest("req-1").seq())));
        store.write(context, () -> context.addProposal(new ContextLayerStore.Proposal("p1", "global", "c", "b", "claude", "s", "a", T0, ProposalState.PENDING, null)));
        store.write(context, () -> context.addProposal(new ContextLayerStore.Proposal("p2", "global", "c", "b", "claude", "s", "a", T1, ProposalState.PENDING, null)));
        assertEquals(ProposalState.REJECTED, store.write(context, () -> context.proposal("p1").state()));
        assertTrue((boolean) store.write(context, () -> context.decideProposal("p2", ProposalState.APPLIED, T1)));
        assertFalse((boolean) store.write(context, () -> context.decideProposal("p2", ProposalState.REJECTED, T1)), "a decided proposal stays decided");
        ContextLayerStore.Reset reset = store.write(context, () -> context.resetLog("hash", T1));
        assertEquals(1, reset.archived());
        assertEquals(List.of(), store.write(context, () -> context.logRecords()));
        assertEquals(reset.id(), num(store.write(context, () -> context.latestResetId())));
    }

    // -------------------------------------------------------------------------------------------------------------- continuity

    private static final String ACTIVE = "{\"status\":\"ACTIVE\",\"note\":\"x\"}";
    private static final String DONE = "{\"status\":\"COMPLETED\",\"note\":\"x\"}";

    @Test
    void aCheckpointIsAStateTokenCompareAndSetWithAReplayableRequest() throws Exception {
        ContinuityStore c = store.repository(ContinuityStore.class);
        store.write(c, () -> c.ensureTask("t", T0));
        ContinuityStore.Snapshot empty = store.write(c, () -> c.getCurrent("t", 1L));
        assertNull(empty.memoryJson());
        ContinuityStore.Stored stored = assertInstanceOf(ContinuityStore.Stored.class, store.write(c, () -> c.checkpoint("t", 1, empty.stateToken(), "req", "cmd", ACTIVE, T0, List.of("anchor"))));
        assertEquals("sha256:" + io.github.jaeseongs95.sennard.service.store.modules.StoreValues.sha256Hex(ACTIVE), stored.snapshot().memoryDigest());
        assertInstanceOf(ContinuityStore.Replay.class, store.write(c, () -> c.checkpoint("t", 1, empty.stateToken(), "req", "cmd", ACTIVE, T0, List.of())));
        assertInstanceOf(ContinuityStore.Conflict.class, store.write(c, () -> c.checkpoint("t", 1, empty.stateToken(), "req", "other", ACTIVE, T0, List.of())));
        ContinuityStore.Stale stale = assertInstanceOf(ContinuityStore.Stale.class, store.write(c, () -> c.checkpoint("t", 1, empty.stateToken(), "req2", "cmd", DONE, T1, List.of())));
        assertEquals(stored.snapshot().stateToken(), stale.actualStateToken());
        assertInstanceOf(ContinuityStore.EpochMismatch.class, store.write(c, () -> c.checkpoint("t", 2, empty.stateToken(), "req3", "cmd", DONE, T1, List.of())));
    }

    @Test
    void aTamperedMemoryIsAnIntegrityFailureNotARead() throws Exception {
        ContinuityStore c = store.repository(ContinuityStore.class);
        store.write(c, () -> c.ensureTask("t", T0));
        ContinuityStore.Snapshot empty = store.write(c, () -> c.getCurrent("t", 1L));
        store.write(c, () -> c.checkpoint("t", 1, empty.stateToken(), "req", "cmd", ACTIVE, T0, List.of()));
        owner("UPDATE sennard.continuity_current_memory SET memory_json = '{\"status\":\"changed\"}'");
        assertEquals(StoreCode.INTEGRITY_FAILED, assertThrows(StoreException.class, () -> store.write(c, () -> c.getCurrent("t", 1L))).code());
    }

    /** D3: the compare-and-set on the root holds for a null root and for a value. */
    @Test
    void rotationMovesToTheNextEpochAndKeepsToItsGuard() throws Exception {
        ContinuityStore c = store.repository(ContinuityStore.class);
        store.write(c, () -> c.ensureTask("t", T0));
        ContinuityStore.Snapshot empty = store.write(c, () -> c.getCurrent("t", 1L));
        store.write(c, () -> c.checkpoint("t", 1, empty.stateToken(), "req", "cmd", ACTIVE, T0, List.of()));
        assertEquals(1, store.write(c, () -> c.rotateEpoch("t", T1, new ContinuityStore.Expected(1, "other-root", () -> true, false))).currentEpoch(), "another root is expected");
        assertEquals(1, store.write(c, () -> c.rotateEpoch("t", T1, new ContinuityStore.Expected(1, null, () -> false, false))).currentEpoch(), "the root is not settled");
        assertEquals(2, store.write(c, () -> c.rotateEpoch("t", T1, new ContinuityStore.Expected(1, null, () -> true, true))).currentEpoch(), "null root compares equal to null");
        assertEquals(ACTIVE, store.write(c, () -> c.getCurrent("t", 2L)).memoryJson(), "carried");
        assertNull(store.write(c, () -> c.getCurrent("t", 1L)), "an old epoch does not select history");
        assertTrue((boolean) store.write(c, () -> c.bindRoot("t", 2, "root-1", T1)));
        assertEquals("root-1", store.write(c, () -> c.readTask("t")).rootId());
        assertEquals(2, store.write(c, () -> c.rotateEpoch("t", T1, new ContinuityStore.Expected(2, null, () -> true, false))).currentEpoch(), "a null expectation does not match a bound root");
        assertEquals(3, store.write(c, () -> c.rotateEpoch("t", T1, new ContinuityStore.Expected(2, "root-1", () -> true, false))).currentEpoch());
        assertNull(store.write(c, () -> c.getCurrent("t", 3L)).memoryJson(), "not carried");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.continuity_memory_history WHERE reason = 'epoch-carry'"));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.continuity_memory_history WHERE reason = 'clear'"));
    }

    /** D7 (substr with an int parameter), D3 (turn_hash null safe). Roots are found per task and across tasks. */
    @Test
    void boundRootsAndObservationsAreFoundByPrefixAndOnce() throws Exception {
        ContinuityStore c = store.repository(ContinuityStore.class);
        store.write(c, () -> c.ensureTask("t", T0));
        store.write(c, () -> c.ensureTask("u", T0));
        assertTrue((boolean) store.write(c, () -> c.bindRoot("t", 1, "root-1", T0)));
        assertTrue((boolean) store.write(c, () -> c.bindRoot("t", 1, "root-1", T0)));
        assertTrue((boolean) store.write(c, () -> c.bindRoot("t", 1, "root-2", T0)));
        assertEquals(List.of("root-1", "root-2"), store.write(c, () -> c.boundRoots("t", 1)));
        List<String> everyRoot = store.write(c, () -> c.allBoundRoots("t"));
        assertEquals(Set.of("root-1", "root-2"), new TreeSet<>(everyRoot));
        assertTrue((boolean) store.write(c, () -> c.ownedElsewhere("u", "root-1")));
        assertFalse((boolean) store.write(c, () -> c.ownedElsewhere("t", "root-1")));
        assertTrue((boolean) store.write(c, () -> c.rootBound("root-2")));
        assertTrue((boolean) store.write(c, () -> c.ownsRoot("t", "root-1")));
        assertEquals("workflow-root:root-1", store.write(c, () -> c.firstObservationEvent("t", 1, "workflow-root:")));
    }

    /** D-0156: the one-row reads of the observations take the business database's own row limit (SqlBuilder), the oldest of an epoch and the newest of any epoch. */
    @Test
    void theOldestObservationOfAnEpochAndTheNewestOfAnyEpochAreOneRowEach() throws Exception {
        ContinuityStore c = store.repository(ContinuityStore.class);
        store.write(c, () -> c.ensureTask("t", T0));
        store.write(c, () -> {
            c.recordObservation("t", 1, "turn-end", "h1", true, T0);
            c.recordObservation("t", 1, "turn-end", "h2", true, T0);
            c.recordObservation("t", 2, "turn-end", "h3", true, T1);
            return null;
        });
        ContinuityStore.ObservationAt oldest = store.write(c, () -> c.firstObservation("t", 1, "turn-end"));
        ContinuityStore.ObservationAt newest = store.write(c, () -> c.lastObservationAnyEpoch("t", "turn-end"));
        assertTrue(oldest.id() < newest.id(), "the oldest of epoch 1 is before the newest of all epochs");
        assertEquals(newest.id(), store.write(c, () -> c.lastObservationAnyEpoch("t", "turn-end")).id());
        assertNull(store.write(c, () -> c.lastObservationAnyEpoch("t", "no-such-event")));
        assertTrue((boolean) store.write(c, () -> c.hasObservationAnyEpochAfter("t", "turn-end", "h3", oldest.id())));
        assertFalse((boolean) store.write(c, () -> c.hasObservationAnyEpochAfter("t", "turn-end", "h3", newest.id())), "nothing of that hash after the newest row");
        assertFalse((boolean) store.write(c, () -> c.hasObservationAnyEpochAfter("t", "turn-end", "other", oldest.id())));
    }

    /** D4: an empty prefix list means nothing closes the event. */
    @Test
    void anOpenEventIsClosedOnlyByAClosingPrefix() throws Exception {
        ContinuityStore c = store.repository(ContinuityStore.class);
        store.write(c, () -> c.ensureTask("t", T0));
        store.write(c, () -> {
            c.recordObservation("t", 1, "pending-x", null, true, T0);
            return null;
        });
        assertTrue((boolean) store.write(c, () -> c.hasOpenCurrentEpochEvent("t", "pending-x", List.of())));
        assertTrue((boolean) store.write(c, () -> c.hasOpenCurrentEpochEvent("t", "pending-x", List.of("done-"))));
        store.write(c, () -> {
            c.recordObservation("t", 1, "done-1", "hash", true, T1);
            return null;
        });
        assertFalse((boolean) store.write(c, () -> c.hasOpenCurrentEpochEvent("t", "pending-x", List.of("done-"))));
        assertTrue((boolean) store.write(c, () -> c.hasObservation("t", 1, "done-1")));
    }

    /** D5 (the EXISTS results are booleans) and D6 (the status of a memory holding an escaped NUL is judged in Java). */
    @Test
    void cleanupProtectsActiveMemoryTakesOldTasksAndKeepsABackup() throws Exception {
        ContinuityStore c = store.repository(ContinuityStore.class);
        String nul = "{\"status\":\"COMPLETED\",\"note\":\"a\\u0000b\"}";
        String nulActive = "{\"status\":\"ACTIVE\",\"note\":\"a\\u0000b\"}";
        for (String[] item : new String[][] {{"old", nul}, {"kept", nulActive}, {"payload", DONE}}) {
            store.write(c, () -> c.ensureTask(item[0], T0));
            ContinuityStore.Snapshot empty = store.write(c, () -> c.getCurrent(item[0], 1L));
            assertInstanceOf(ContinuityStore.Stored.class, store.write(c, () -> c.checkpoint(item[0], 1, empty.stateToken(), "req", "cmd", item[1], T0, List.of())));
        }
        owner("UPDATE sennard.continuity_requests SET created_at = " + timestampLiteral(LATER) + " WHERE task_correlation = 'payload'");
        ContinuityStore.CleanupPreview preview = store.write(c, () -> c.previewCleanup(CUT, CUT));
        assertEquals(1, preview.protectedActiveTasks());
        assertEquals(List.of("old"), preview.tasks().stream().map(ContinuityStore.CleanupTask::taskCorrelation).toList());
        assertEquals(List.of("payload"), preview.snapshots().stream().map(ContinuityStore.CleanupSnapshot::taskCorrelation).toList());
        ContinuityStore.CleanupResult result = store.write(c, () -> c.executeCleanup(preview, "plan-1", LATER, CUT, CUT));
        assertEquals(new ContinuityStore.CleanupResult(1, 1), result);
        assertNull(store.write(c, () -> c.readTask("old")));
        assertNull(store.write(c, () -> c.getCurrent("payload", 1L)).memoryJson(), "the payload is erased");
        assertNotNull(store.write(c, () -> c.getTombstone("payload", 1)));
        assertTrue(ownerLong("SELECT count(*) FROM sennard.continuity_cleanup_backup WHERE plan_id = 'plan-1'") >= 3, "the deleted rows are kept");
        assertEquals(StoreCode.CONTINUITY_CLEANUP_STALE, assertThrows(StoreException.class, () -> store.write(c, () -> c.executeCleanup(new ContinuityStore.CleanupPreview(preview.snapshots(), List.of(), 0), "plan-2", LATER, CUT, CUT))).code());
    }

    @Test
    void aPurgeLeavesATombstoneAndScrubsBodiesFromStoredRequests() throws Exception {
        ContinuityStore c = store.repository(ContinuityStore.class);
        store.write(c, () -> c.ensureTask("t", T0));
        ContinuityStore.Snapshot empty = store.write(c, () -> c.getCurrent("t", 1L));
        ContinuityStore.Stored stored = assertInstanceOf(ContinuityStore.Stored.class, store.write(c, () -> c.checkpoint("t", 1, empty.stateToken(), "req", "cmd", ACTIVE, T0, List.of())));
        String digest = "sha256:" + "a".repeat(64);
        owner("UPDATE sennard.continuity_requests SET result_json = '{\"opaque-body\":\"body\"}'");
        ContinuityStore.Purged purged = assertInstanceOf(ContinuityStore.Purged.class, store.write(c, () -> c.purge("t", 1, stored.snapshot().stateToken(), "req-p", "cmd-p", digest, T1)));
        assertNull(purged.snapshot().memoryJson());
        assertEquals(digest, store.write(c, () -> c.getTombstone("t", 1)).payloadDigest());
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.continuity_requests WHERE result_json LIKE '%opaque-body%'"));
        assertInstanceOf(ContinuityStore.Replay.class, store.write(c, () -> c.purge("t", 1, stored.snapshot().stateToken(), "req-p", "cmd-p", digest, T1)));
    }

    @Test
    void pendingMarkersAreConsumedOnceAndOnlyWhileCurrent() throws Exception {
        ContinuityStore c = store.repository(ContinuityStore.class);
        store.write(c, () -> c.ensureTask("t", T0));
        ContinuityStore.Snapshot empty = store.write(c, () -> c.getCurrent("t", 1L));
        ContinuityStore.Snapshot s = assertInstanceOf(ContinuityStore.Stored.class, store.write(c, () -> c.checkpoint("t", 1, empty.stateToken(), "req", "cmd", ACTIVE, T0, List.of()))).snapshot();
        assertTrue((boolean) store.write(c, () -> c.setDirectPendingMarker("t", 1, s.stateToken(), s.memoryDigest(), T1)));
        assertTrue((boolean) store.write(c, () -> c.consumeDirectMarker("t", 1, s.stateToken(), s.memoryDigest(), T1)));
        assertFalse((boolean) store.write(c, () -> c.consumeDirectMarker("t", 1, s.stateToken(), s.memoryDigest(), T1)), "already consumed");
        store.write(c, () -> c.bindRoot("t", 1, "root-1", T1));
        assertFalse((boolean) store.write(c, () -> c.setWorkflowPendingMarker("t", 1, 3, "d", "other-root", T1)));
        assertTrue((boolean) store.write(c, () -> c.setWorkflowPendingMarker("t", 1, 3, "d", "root-1", T1)));
        assertFalse((boolean) store.write(c, () -> c.consumeWorkflowMarker("t", 1, "root-1", 4, "d", T1)), "another revision");
        assertTrue((boolean) store.write(c, () -> c.consumeWorkflowMarker("t", 1, "root-1", 3, "d", T1)));
        assertEquals(Long.valueOf(3), store.write(c, () -> c.readTask("t")).lastAutoInjectedRevision());
    }

    // ---------------------------------------------------------------------------------------------------------------- workflow

    private static WorkflowStore.RunRow run(String id, long revision, RunState state) {
        return new WorkflowStore.RunRow(id, revision, state, "{\"runId\":\"" + id + "\",\"revision\":" + revision + "}");
    }

    private static WorkflowStore.RootRow root(String id, long revision, RootState state, String updatedAt) {
        return new WorkflowStore.RootRow(id, revision, state, "ws-1", "/w", "{\"rootId\":\"" + id + "\",\"revision\":" + revision + ",\"state\":\"" + state + "\",\"updatedAt\":\"" + updatedAt + "\"}", T0, updatedAt, 1, "sha256:frame");
    }

    private static WorkflowStore.IdentityRow identity(String id) {
        return new WorkflowStore.IdentityRow(id, "{\"root\":\"" + id + "\"}", "surface-" + id);
    }

    private static <C> WorkflowStore.RootInsertionPlan<C> freePlan(String id) {
        return new WorkflowStore.RootInsertionPlan<>(null, List.of(), identity(id), "surface-" + id);
    }

    private static WorkflowStore.LeaseRow lease(String id, String root, long revision, long ordinal) {
        return new WorkflowStore.LeaseRow(id, root, revision, 1, ordinal, LeaseState.ISSUED, "{\"leaseId\":\"" + id + "\",\"state\":\"" + LeaseState.ISSUED + "\"}", "{\"proposal\":\"" + id + "\"}", T0, T1);
    }

    /** Every closed value set of a column is the enum's names: each constant is stored as its name, read back as itself, and a text that is not a name is refused by the database. */
    @Test
    void closedStateColumnsHoldConstantNamesAndTheDatabaseRefusesAnythingElse() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        for (RunState state : RunState.values()) {
            String id = "run-" + state;
            store.write(w, () -> {
                w.insertRun(run(id, 1, state), T0, "CHANGE", List.of());
                return null;
            });
            assertEquals(state.name(), ownerText("SELECT state FROM sennard.workflow_runs WHERE run_id = '" + id + "'"));
        }
        assertEquals(RunState.values().length, ownerLong("SELECT count(*) FROM sennard.workflow_runs WHERE state IN " + namesOf(RunState.values())));
        for (String legacy : new String[] {"needs-input", "passed", "Passed", "unknown", ""}) {
            assertThrows(SQLException.class, () -> owner("INSERT INTO sennard.workflow_runs (run_id, revision, state, receipt_json, created_at, updated_at) "
                    + "VALUES ('bad', 1, '" + legacy + "', '{}', " + timestampLiteral(T0) + ", " + timestampLiteral(T0) + ")"), "a run state that is not a RunState name: '" + legacy + "'");
        }
        for (RootState state : RootState.values()) {
            String id = "root-" + state;
            store.write(w, () -> w.insertConvergenceRoot(root(id, 1, state, T0), null, active -> freePlan(id)));
            assertEquals(state.name(), ownerText("SELECT state FROM sennard.convergence_roots WHERE root_id = '" + id + "'"));
        }
        assertThrows(SQLException.class, () -> owner("UPDATE sennard.convergence_roots SET state = 'needs-review'"));
        assertThrows(SQLException.class, () -> owner("INSERT INTO sennard.convergence_leases (lease_id, root_id, root_revision, epoch, ordinal, state, lease_json, proposal_json, issued_at, expires_at) "
                + "SELECT 'bad-lease', root_id, 1, 1, 9, 'issued', '{}', '{}', " + timestampLiteral(T0) + ", " + timestampLiteral(T1) + " FROM sennard.convergence_roots LIMIT 1"));
    }

    /** The constants' names as an IN list ({@code ('A', 'B')}), which every DBMS has (PostgreSQL's {@code = ANY(ARRAY[...])} is its own). */
    private static String namesOf(Enum<?>[] values) {
        return "(" + java.util.Arrays.stream(values).map(value -> "'" + value.name() + "'").collect(java.util.stream.Collectors.joining(", ")) + ")";
    }

    @Test
    void theRunRevisionIsACompareAndSetAndAMismatchedReceiptIsRefused() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        store.write(w, () -> {
            w.insertRun(run("r1", 1, RunState.RUNNING), T0, "CHANGE", List.of());
            return null;
        });
        assertEquals(T0, store.write(w, () -> w.getRunUpdatedAt("r1")));
        assertFalse((boolean) store.write(w, () -> w.updateRun(run("r1", 3, RunState.PASSED), 2, null, null, null, T1)));
        assertTrue((boolean) store.write(w, () -> w.updateRun(run("r1", 2, RunState.PASSED), 1, null, null, null, T1)));
        assertEquals(T1, store.write(w, () -> w.getRunUpdatedAt("r1")), "the update time is the caller's clock reading, not the database's");
        assertEquals("{\"runId\":\"r1\",\"revision\":2}", store.write(w, () -> w.getRun("r1")));
        owner("UPDATE sennard.workflow_runs SET revision = 9");
        assertEquals(StoreCode.INTEGRITY_FAILED, assertThrows(StoreException.class, () -> store.write(w, () -> w.getRun("r1"))).code());
    }

    @Test
    void metadataSequenceAndObservationClaimsAreOneUse() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        assertEquals(1, num(store.write(w, () -> w.nextRunSequence(T0))));
        assertEquals(2, num(store.write(w, () -> w.nextRunSequence(T0))));
        assertEquals(StoreCode.INTEGRITY_FAILED, assertThrows(StoreException.class, () -> store.write(w, () -> w.secret("k"))).code(), "a request does not make a secret");
        store.write(w, () -> {
            w.createSecretIfAbsent("k", "first", T0);
            return null;
        });
        assertEquals(SecretValue.of("first"), store.write(w, () -> w.secret("k")));
        store.write(w, () -> {
            w.createSecretIfAbsent("k", "second", T0);
            return null;
        });
        assertEquals(SecretValue.of("first"), store.write(w, () -> w.secret("k")), "the secret that was made first stays");
        assertTrue((boolean) store.write(w, () -> w.claimExecutionObservation("o1", T0)));
        assertFalse((boolean) store.write(w, () -> w.claimExecutionObservation("o1", T1)));
    }

    @Test
    void anApprovalIsFirstWriterWinsAndRevokedWithoutTouchingOtherFields() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        WorkflowStore.RootApproval first = new WorkflowStore.RootApproval("a1", "scope-1", "c", "q", "{\"z\":1,\"a\":1.50}", T0, null);
        store.write(w, () -> w.insertRootApproval(first));
        assertEquals("a1", store.write(w, () -> w.insertRootApproval(new WorkflowStore.RootApproval("a2", "scope-1", "c", "q", "{}", T1, null))).approvalId(), "same scope returns the stored decision");
        assertEquals(StoreCode.USER_APPROVAL_SOURCE_CONFLICT, assertThrows(StoreException.class, () -> store.write(w, () -> w.insertRootApproval(new WorkflowStore.RootApproval("a3", "scope-2", "c", "q", "{}", T1, null)))).code());
        assertTrue((boolean) store.write(w, () -> w.revokeRootApproval("a1", T1)));
        assertFalse((boolean) store.write(w, () -> w.revokeRootApproval("a1", T1)));
        assertEquals("{\"z\":1,\"a\":1.50,\"revokedAt\":\"" + T1 + "\"}", store.write(w, () -> w.getRootApproval("a1")).recordJson());
        assertNull(store.write(w, () -> w.findRootApproval("scope-1")));
    }

    /** GATE-ZERO-1 at the store: the planner receives the active root, so a conflict cannot be an empty read. */
    @Test
    void anActiveRootReachesThePlannerAndAConflictStopsTheInsert() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        assertNull(store.write(w, () -> w.insertConvergenceRoot(root("root-1", 1, RootState.OPEN, T0), null, active -> {
            assertEquals(0, active.size());
            return freePlan("root-1");
        })));
        assertTrue((boolean) store.write(w, () -> w.isConvergenceRootActive("root-1")));
        String conflict = store.write(w, () -> w.insertConvergenceRoot(root("root-2", 1, RootState.OPEN, T0), null, active -> {
            assertEquals(List.of("root-1"), active.stream().map(WorkflowStore.ActiveRootRow::rootId).toList());
            assertEquals("surface-root-1", active.get(0).surfaceDigest());
            return new WorkflowStore.RootInsertionPlan<>("ROOT_CONFLICT", List.of(new WorkflowStore.IdentityRow("root-1", "{\"fresh\":true}", "surface-new")), identity("root-2"), "surface-root-2");
        }));
        assertEquals("ROOT_CONFLICT", conflict);
        assertNull(store.snapshot(() -> w.getConvergenceSnapshot("root-2")));
        assertEquals("surface-new", store.write(w, () -> w.readActiveRoots()).get(0).surfaceDigest(), "a refreshed identity is saved even when the insert stops");
        assertEquals(StoreCode.STALE_REVISION, assertThrows(StoreException.class, () -> store.write(w, () -> {
            w.assertRootsInactive(List.of("root-1", "missing"));
            return null;
        })).code());
    }

    @Test
    void replacingAParentRootAbandonsItAndTheRootRevisionIsACompareAndSet() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        store.write(w, () -> w.insertConvergenceRoot(root("parent", 1, RootState.OPEN, T0), null, active -> freePlan("parent")));
        store.write(w, () -> w.insertConvergenceRoot(root("child", 1, RootState.OPEN, T1), "parent", active -> freePlan("child")));
        WorkflowStore.Snapshot parent = store.snapshot(() -> w.getConvergenceSnapshot("parent"));
        assertTrue(parent.rootJson().contains("\"state\":\"" + RootState.ABANDONED + "\"") && parent.rootJson().contains("\"revision\":2"), parent.rootJson());
        assertFalse((boolean) store.write(w, () -> w.isConvergenceRootActive("parent")));
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.convergence_active_surfaces WHERE root_id = 'parent'"), "the abandoned parent gave its surface up");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.convergence_active_surfaces WHERE root_id = 'child' AND active_surface = 'surface-child'"), "the child holds its surface");
        assertFalse((boolean) store.write(w, () -> w.updateConvergenceRoot(root("child", 5, RootState.OPEN, T1), 9, 1, null)));
        WorkflowStore.ReviewRow review = new WorkflowStore.ReviewRow("v1", "child", 1, "{\"review\":1}", T1);
        assertTrue((boolean) store.write(w, () -> w.updateConvergenceRoot(root("child", 2, RootState.NEEDS_REVIEW, T1), 1, 1, review)));
        assertEquals(List.of("{\"review\":1}"), store.snapshot(() -> w.getConvergenceSnapshot("child")).reviews());
        assertEquals(StoreCode.INVALID_INPUT, assertThrows(StoreException.class, () -> store.write(w, () -> w.insertConvergenceRoot(root("x", 1, RootState.OPEN, T0), "missing", active -> freePlan("x")))).code());
    }

    /**
     * Two active roots of one write surface: the second root's INSERT of its surface row meets the primary key of {@code convergence_active_surfaces} and ends in ROOT_CONFLICT, with the
     * root row of that transaction rolled back; a root that left the active states gives its surface up (its row is deleted); a root that is not active has no surface row.
     */
    @Test
    void aSecondActiveRootOfOneWriteSurfaceEndsInRootConflictAndAGivenUpSurfaceIsFree() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        store.write(w, () -> w.insertConvergenceRoot(root("root-1", 1, RootState.OPEN, T0), null, active -> freePlan("root-1")));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.convergence_active_surfaces WHERE root_id = 'root-1' AND active_surface = 'surface-root-1'"), "an active root holds its surface");
        WorkflowStore.RootInsertionPlan<String> second = new WorkflowStore.RootInsertionPlan<>(null, List.of(), new WorkflowStore.IdentityRow("root-2", "{\"root\":\"root-2\"}", "surface-root-2"), "surface-root-1");
        assertEquals(StoreCode.ROOT_CONFLICT, assertThrows(StoreException.class, () -> store.write(w, () -> w.insertConvergenceRoot(root("root-2", 1, RootState.OPEN, T1), null, active -> second))).code());
        assertNull(store.snapshot(() -> w.getConvergenceSnapshot("root-2")), "the refused root was not written");
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.convergence_active_surfaces WHERE root_id = 'root-2'"), "the refused root holds no surface");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.convergence_active_surfaces WHERE active_surface = 'surface-root-1'"), "the surface is still the first root's");
        assertFalse((boolean) store.write(w, () -> w.updateConvergenceRoot(root("root-1", 9, RootState.COMPLETED, T1), 7, 1, null)), "a write that lost the compare-and-set changed nothing");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.convergence_active_surfaces WHERE root_id = 'root-1'"), "and the root keeps its surface");
        assertTrue((boolean) store.write(w, () -> w.updateConvergenceRoot(root("root-1", 2, RootState.COMPLETED, T1), 1, 1, null)));
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.convergence_active_surfaces WHERE root_id = 'root-1'"), "a root that is no longer active gives its surface up");
        WorkflowStore.RootInsertionPlan<String> third = new WorkflowStore.RootInsertionPlan<>(null, List.of(), new WorkflowStore.IdentityRow("root-3", "{\"root\":\"root-3\"}", "surface-root-3"), "surface-root-1");
        assertNull(store.write(w, () -> w.insertConvergenceRoot(root("root-3", 1, RootState.OPEN, T1), null, active -> third)));
    }

    /**
     * A replacement abandons its parent (which gives up its surface row) and then INSERTs its own root and surface row in one transaction. When the surface INSERT meets the primary key of
     * {@code convergence_active_surfaces}, the transaction ends with the exception and the whole of it is rolled back: the parent is not left ABANDONED and keeps its surface, and the
     * child is not written.
     */
    @Test
    void aReplacementThatLosesOnTheUniqueKeyLeavesItsParentActive() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        store.write(w, () -> w.insertConvergenceRoot(root("root-1", 1, RootState.OPEN, T0), null, active -> freePlan("root-1")));
        store.write(w, () -> w.insertConvergenceRoot(root("parent", 1, RootState.OPEN, T0), null, active -> freePlan("parent")));
        WorkflowStore.RootInsertionPlan<String> child = new WorkflowStore.RootInsertionPlan<>(null, List.of(), identity("child"), "surface-root-1");
        assertEquals(StoreCode.ROOT_CONFLICT, assertThrows(StoreException.class, () -> store.write(w, () -> w.insertConvergenceRoot(root("child", 1, RootState.OPEN, T1), "parent", active -> child))).code());
        assertTrue((boolean) store.write(w, () -> w.isConvergenceRootActive("parent")), "the parent abandoned in the transaction that failed is active again");
        assertNull(store.snapshot(() -> w.getConvergenceSnapshot("child")), "the refused replacement was not written");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.convergence_active_surfaces WHERE root_id = 'parent' AND active_surface = 'surface-parent'"), "the parent kept its surface");
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.convergence_active_surfaces WHERE root_id = 'child'"), "the refused replacement holds no surface");
    }

    /** A replacement of the same write surface: the parent's surface row is deleted by the abandoning and the child's row takes the key in the same transaction. */
    @Test
    void aReplacementOfTheSameWriteSurfaceTakesTheSurfaceTheAbandonedParentGaveUp() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        store.write(w, () -> w.insertConvergenceRoot(root("same-parent", 1, RootState.OPEN, T0), null, active -> freePlan("same-parent")));
        WorkflowStore.RootInsertionPlan<String> child = new WorkflowStore.RootInsertionPlan<>(null, List.of(), identity("same-child"), "surface-same-parent");
        assertNull(store.write(w, () -> w.insertConvergenceRoot(root("same-child", 1, RootState.OPEN, T1), "same-parent", active -> child)));
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.convergence_active_surfaces WHERE root_id = 'same-parent'"), "the parent gave the surface up");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.convergence_active_surfaces WHERE root_id = 'same-child' AND active_surface = 'surface-same-parent'"), "the child holds it");
    }

    @Test
    void aLeaseIsClaimedOnceAndConsumingItStartsTheGuardedRun() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        store.write(w, () -> w.insertConvergenceRoot(root("root-1", 1, RootState.OPEN, T0), null, active -> freePlan("root-1")));
        assertFalse(w.insertAttemptLease(root("root-1", 2, RootState.OPEN, T1), 7, lease("l0", "root-1", 2, 1)), "wrong root revision");
        assertTrue(w.insertAttemptLease(root("root-1", 2, RootState.OPEN, T1), 1, lease("l1", "root-1", 2, 1)));
        assertTrue(w.insertAttemptLease(root("root-1", 3, RootState.OPEN, T1), 2, lease("l2", "root-1", 3, 2)), "the previous unstarted lease is superseded");
        assertTrue(store.write(w, () -> w.getAttemptLease("l1")).leaseJson().contains("\"state\":\"" + LeaseState.EXPIRED + "\""));
        assertNull(w.insertGuardedRun(run("r0", 1, RunState.RUNNING), "l1", 3, T1, "CHANGE", List.of()), "a superseded lease does not start a run");
        WorkflowStore.GuardedBinding started = w.insertGuardedRun(run("r1", 1, RunState.RUNNING), "l2", 3, T1, "CHANGE", List.of());
        assertNotNull(started);
        assertTrue(started.leaseJson().contains("\"state\":\"" + LeaseState.CONSUMED + "\""));
        assertNull(w.insertGuardedRun(run("r2", 1, RunState.RUNNING), "l2", 4, T1, "CHANGE", List.of()), "a consumed lease does not start a second run");
        assertEquals(started.leaseJson(), store.write(w, () -> w.getGuardedRunBinding("r1")).leaseJson());
        WorkflowStore.Outcome outcome = new WorkflowStore.Outcome(AttemptState.PASSED, "{\"o\":1}", T1);
        assertTrue((boolean) store.write(w, () -> w.updateRun(run("r1", 2, RunState.PASSED), 1, root("root-1", 5, RootState.OPEN, T1), 4L, outcome, T1)));
        assertEquals("{\"o\":1}", store.write(w, () -> w.getGuardedRunBinding("r1")).outcomeJson());
        assertEquals(StoreCode.STALE_REVISION, assertThrows(StoreException.class, () -> store.write(w, () -> w.updateRun(run("r1", 3, RunState.PASSED), 2, root("root-1", 6, RootState.OPEN, T1), 99L, outcome, T1))).code());
        assertEquals("{\"proposal\":\"l2\"}", store.snapshot(() -> w.getConvergenceSnapshot("root-1")).proposals().get(1));
    }

    /** A lease key collision is LEASE_CONFLICT, and the partial index stops a second issued lease whatever writes it. */
    @Test
    void aLeaseKeyCollisionIsALeaseConflictAndTheRootIsUnchanged() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        store.write(w, () -> w.insertConvergenceRoot(root("root-1", 1, RootState.OPEN, T0), null, active -> freePlan("root-1")));
        // A repository method joins the transaction of the service method (the store opens none), so each call is run in the one transaction a service method's @Transactional would open.
        assertTrue(store.write(w, () -> w.insertAttemptLease(root("root-1", 2, RootState.OPEN, T1), 1, lease("l1", "root-1", 2, 1))));
        StoreException conflict = assertThrows(StoreException.class, () -> store.write(w, () -> w.insertAttemptLease(root("root-1", 3, RootState.OPEN, T1), 2, lease("l1", "root-1", 3, 2))));
        assertEquals(StoreCode.LEASE_CONFLICT, conflict.code());
        assertEquals(2, ownerLong("SELECT revision FROM sennard.convergence_roots"), "the whole attempt rolled back");
        // A second issued lease of the root: the table of the issued leases has the root as its primary key (the standard has no unique index over the issued rows only).
        StoreException second = assertThrows(StoreException.class, () -> store.transaction(TxOptions.write(), tx -> {
            tx.query("INSERT INTO sennard.convergence_leases (lease_id, root_id, root_revision, epoch, ordinal, state, lease_json, proposal_json, issued_at, expires_at) "
                    + "SELECT 'second', root_id, 1, 1, 2, ?, '{}', '{}', ?, ? FROM sennard.convergence_roots WHERE root_id = 'root-1'", LeaseState.EXPIRED.name(),
                    io.github.jaeseongs95.sennard.domain.Timestamps.toTimestamp(T0), io.github.jaeseongs95.sennard.domain.Timestamps.toTimestamp(T1));
            return tx.query("INSERT INTO sennard.convergence_issued_leases (root_id, lease_id) VALUES ('root-1', 'second')");
        }));
        assertEquals(StoreCode.STORE_UNIQUE_VIOLATION, second.code());
    }

    @Test
    void pluginUpdateStateMergesUnderTheLockAndNoticesOncePerVersion() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        WorkflowStore.PluginUpdateState base = new WorkflowStore.PluginUpdateState("t", "1.0.0", "2.0.0", "v2", "abc", null, UpdateComparison.UPDATE_AVAILABLE, T0, T0, T1, null, null, null);
        store.write(w, () -> {
            w.putPluginUpdateState("t", prior -> {
                assertNull(prior);
                return base;
            });
            return null;
        });
        assertEquals("2.0.0", store.write(w, () -> w.getPluginUpdateState("t")).latestVersion());
        assertFalse((boolean) store.write(w, () -> w.claimPluginUpdateNotice("t", "3.0.0", T1, seen -> false)), "another version");
        assertTrue((boolean) store.write(w, () -> w.claimPluginUpdateNotice("t", "2.0.0", T1, seen -> false)));
        assertFalse((boolean) store.write(w, () -> w.claimPluginUpdateNotice("t", "2.0.0", T1, seen -> true)), "already notified");
    }

    @Test
    void cleanupVerifiesThePreviewDeletesLinkedRowsAndKeepsABackup() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        store.write(w, () -> w.insertConvergenceRoot(root("root-1", 1, RootState.OPEN, T0), null, active -> freePlan("root-1")));
        assertTrue(w.insertAttemptLease(root("root-1", 2, RootState.OPEN, T0), 1, lease("l1", "root-1", 2, 1)));
        assertNotNull(w.insertGuardedRun(run("r1", 1, RunState.RUNNING), "l1", 2, T0, "CHANGE", List.of()));
        store.write(w, () -> {
            w.insertRun(run("alone", 1, RunState.PASSED), T0, "CHANGE", List.of());
            return null;
        });
        assertTrue(store.write(w, () -> w.previewCleanup(CUT)).rootProtection().totalProtectedRoots() >= 1, "an active root is protected");
        assertTrue((boolean) store.write(w, () -> w.updateRun(run("r1", 2, RunState.PASSED), 1, root("root-1", 4, RootState.COMPLETED, T0), 3L, new WorkflowStore.Outcome(AttemptState.PASSED, "{}", T0), T0)));
        owner("UPDATE sennard.workflow_runs SET updated_at = " + timestampLiteral(T0));
        WorkflowStore.CleanupPreview preview = store.write(w, () -> w.previewCleanup(CUT));
        assertEquals(1, preview.roots().size());
        assertEquals(List.of("alone"), preview.standaloneRuns().stream().map(WorkflowStore.CleanupRun::runId).toList());
        assertTrue((boolean) store.write(w, () -> w.claimCleanupPlan("plan-1", "digest", LATER)));
        assertFalse((boolean) store.write(w, () -> w.claimCleanupPlan("plan-1", "digest", LATER)));
        WorkflowStore.CleanupResult result = store.write(w, () -> w.executeCleanup(preview, "plan-1", LATER));
        assertEquals(new WorkflowStore.CleanupResult(1, 2), result);
        assertNull(store.snapshot(() -> w.getConvergenceSnapshot("root-1")));
        assertTrue(ownerLong("SELECT count(*) FROM sennard.workflow_cleanup_backup WHERE plan_id = 'plan-1'") >= 7);
        assertEquals(StoreCode.STALE_REVISION, assertThrows(StoreException.class, () -> store.write(w, () -> w.executeCleanup(preview, "plan-2", LATER))).code());
    }

    // --------------------------------------------------------------------------------------------------------------- messaging
    // (The messaging cases moved to AbstractMessagingServiceTest with its rewrite: MessagingService on Spring Data JDBC, see the table of cases and the table of the old tests
    // in its Javadoc. The locks, the order of locks, SKIP LOCKED and the database clock they held belong to the mechanism that is gone.)

    // ---------------------------------------------------------------------------------------------------------- locks and pool

    private static void awaitRelease(CountDownLatch release) {
        try {
            assertTrue(release.await(30, TimeUnit.SECONDS), "the test released the holder");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /**
     * I6, a row lock: while one transaction has updated a row and not yet committed, another that wants to update it waits only as long as its own transaction has time (a transaction
     * timeout of one second, whole seconds: JDBC's query timeout cuts the statement that waits). The wait ends in the Sennard reason STORE_TIMEOUT within one to three seconds, nothing was
     * written (effect NONE), and once the holder has committed the same call is granted.
     */
    @Test
    void aWriterWaitingForARowLockEndsInStoreTimeoutWhenItsTransactionRunsOut() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        store.write(w, () -> {
            w.insertRun(run("r1", 1, RunState.RUNNING), T0, "CHANGE", List.of());
            return null;
        });
        String touch = "UPDATE sennard.workflow_runs SET updated_at = ? WHERE run_id = 'r1'";
        TxOptions oneSecond = TxOptions.write().withTimeout(Duration.ofSeconds(1));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holderThread = Executors.newSingleThreadExecutor();
        try {
            Future<Long> holder = holderThread.submit(() -> store.transaction(TxOptions.write(), tx -> {
                long changed = tx.query(touch, io.github.jaeseongs95.sennard.domain.Timestamps.toTimestamp(T1)).rowCount();
                holding.countDown();
                awaitRelease(release);
                return changed;
            }));
            assertTrue(holding.await(20, TimeUnit.SECONDS), "the holder has updated the row");
            long started = System.nanoTime();
            StoreException timedOut = assertThrows(StoreException.class, () -> store.transaction(oneSecond,
                    tx -> tx.query(touch, io.github.jaeseongs95.sennard.domain.Timestamps.toTimestamp(LATER))));
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(StoreCode.STORE_TIMEOUT, timedOut.code());
            assertEquals(StoreException.Effect.NONE, timedOut.effect());
            assertTrue(waited >= 900 && waited < 3_000, "the wait lasted " + waited + " ms: the transaction's second, not an instant refusal and not a hang");
            release.countDown();
            assertEquals(1L, holder.get(30, TimeUnit.SECONDS));
            assertEquals(1, store.transaction(oneSecond, tx -> tx.query(touch, io.github.jaeseongs95.sennard.domain.Timestamps.toTimestamp(LATER))).rowCount(),
                    "the same statement changes the row once the holder has committed");
            assertEquals("2026-04-01 00:00:00", ownerTimestampText("SELECT updated_at FROM sennard.workflow_runs WHERE run_id = 'r1'"),
                    "the holder's write came first, the second statement's last");
        } finally {
            release.countDown();
            holderThread.shutdownNow();
        }
    }

    /**
     * Pool N: the backends the service uses depend on the pool, not on how many callers there are. The pool is the test JVM's one (BusinessDbExtension), so the same
     * property is read from its size: one caller and thirty-seven callers use no more backends than the pool's maximum, which is the account's share of the server.
     */
    @Test
    void theNumberOfBackendsFollowsThePoolNotTheCallers() throws Exception {
        // The backends are told apart by their process ids; the checks of other tests borrow from the same pool, so only the ids this test's own queries ran on are counted.
        int limit = pool.getMaximumPoolSize();
        assertEquals(BusinessDbExtension.POOL_SIZE, limit, "the pool is the fixture's, of its fixed size");
        Set<Object> backends = new java.util.HashSet<>();
        for (int callers : new int[] {1, 37}) {
            for (QueryResult answer : parallel(callers, worker -> store.query("SELECT pg_sleep(0.02), pg_backend_pid() AS pid"))) {
                backends.add(answer.rows().get(0).get("pid"));
            }
            assertTrue(backends.size() >= 1 && backends.size() <= limit, callers + " callers: " + backends.size() + " backends, the pool's limit of " + limit + " decides");
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ dialect

    /** D9: the snapshot's READ ONLY does not stay on the pooled connection. */
    @Test
    void dialectReadOnlyDoesNotOutliveItsTransaction() throws Exception {
        // The shared pool with one connection left to borrow: every transaction below runs on that one connection, as on a pool of one.
        try (ScarcePool single = ScarcePool.leaving(1)) {
            TestStores one = TestStores.of(single.dataSource());
            WorkflowStore w = one.repository(WorkflowStore.class);
            assertNull(one.snapshot(() -> w.getRun("none")));
            StoreException readOnly = assertThrows(StoreException.class, () -> one.snapshot(() -> {
                w.insertRun(run("r", 1, RunState.RUNNING), T0, "CHANGE", List.of());
                return null;
            }));
            assertEquals("25006", readOnly.sqlState(), "a snapshot refuses writes");
            assertEquals(1, num(one.write(w, () -> w.nextRunSequence(T0))), "the same connection writes after it");
        }
    }

    /** D10 (H4): what IS JSON accepts and refuses. */
    @Test
    void dialectIsJsonAcceptsDocumentsAndRefusesBrokenText() throws Exception {
        Map<String, Boolean> samples = Map.of("{\"a\":1}", true, "[1,2]", true, "1", true, "\"x\"", true, "null", true, "{", false, "", false, "{\"a\":1}x", false, "{\"a\":1,\"a\":2}", true);
        // The test of a JSON text is PostgreSQL's IS JSON predicate.
        try (Connection owner = BusinessDb.connect(); java.sql.PreparedStatement p = owner.prepareStatement("SELECT CAST(? AS TEXT) IS JSON")) {
            for (Map.Entry<String, Boolean> sample : samples.entrySet()) {
                p.setString(1, sample.getKey());
                try (ResultSet rows = p.executeQuery()) {
                    rows.next();
                    assertEquals(sample.getValue(), rows.getBoolean(1), sample.getKey());
                }
            }
        }
    }

    /**
     * The column check on the memory: not JSON or over 2000 bytes is refused by the database itself. The refusal now goes through Spring's translator (a CHECK violation is a
     * DataIntegrityViolationException, which the store gives as INVALID_INPUT); the SQL state of the database's answer is still carried (23514, check_violation).
     * Not run yet since the change of the translator: that the code is INVALID_INPUT (and not STORE_FAILURE) is read from StoreErrors.Reason.of, to be confirmed by the run.
     */
    @Test
    void theMemoryColumnRefusesOversizedText() throws Exception {
        ContinuityStore c = store.repository(ContinuityStore.class);
        store.write(c, () -> c.ensureTask("t", T0));
        ContinuityStore.Snapshot empty = store.write(c, () -> c.getCurrent("t", 1L));
        // The column holds 2000 characters and its CHECK counts the bytes (standard OCTET_LENGTH): 700 Korean syllables are 2100 bytes and fit the characters, so the CHECK refuses them.
        StoreException tooManyBytes = assertThrows(StoreException.class, () -> store.write(c, () -> c.checkpoint("t", 1, empty.stateToken(), "r", "c", "{\"a\":\"" + "\uac00".repeat(700) + "\"}", T0, List.of())));
        // 23514 is PostgreSQL's check_violation.
        assertEquals("23514", tooManyBytes.sqlState());
        assertEquals(StoreCode.INVALID_INPUT, tooManyBytes.code());
        // More characters than the column holds: the value does not fit (the database has no IS JSON check any more: the JSON of a memory is the service layer's to validate).
        StoreException tooLong = assertThrows(StoreException.class, () -> store.write(c, () -> c.checkpoint("t", 1, empty.stateToken(), "r", "c", "{\"a\":\"" + "x".repeat(2000) + "\"}", T0, List.of())));
        assertEquals("22001", tooLong.sqlState());
        assertEquals(StoreCode.INVALID_INPUT, tooLong.code());
        assertNull(store.write(c, () -> c.getCurrent("t", 1L)).memoryJson(), "the refused memory was not stored");
    }

    // ------------------------------------------------------------------------------- clock, compare-and-set and lock boundaries

    /** No module has a lock to take: the lock table is not in the schema, and no store has a method that takes one (what two writers share is decided by the database's row locks and keys). */
    @Test
    void noModuleHasALockTableAndNoStoreTakesALock() throws Exception {
        assertEquals(0, ownerLong("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'sennard' AND table_name = 'module_lock'"));
        assertEquals(0, java.util.Arrays.stream(io.github.jaeseongs95.sennard.service.store.modules.ModuleStore.class.getMethods()).filter(method -> method.getName().equals("lockModule")).count());
    }

    /** The database has no clock of its own: no function that reads one is left in the schema, and a stored time is the one the caller handed in (the TIMESTAMP value is its UTC fields, whatever the JVM's zone). */
    @Test
    void theDatabaseHasNoClockFunctionAndAStoredTimeIsTheCallersReading() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        store.write(w, () -> {
            w.insertRun(run("r1", 1, RunState.RUNNING), T0, "CHANGE", List.of());
            return null;
        });
        assertEquals(0, ownerLong("SELECT count(*) FROM information_schema.routines WHERE routine_schema = 'sennard'"), "sennard.now_ms is gone");
        String reading = "2030-01-01T00:00:00.123Z";
        assertTrue((boolean) store.write(w, () -> w.updateRun(run("r1", 2, RunState.PASSED), 1, null, null, null, reading)));
        assertEquals(reading, store.write(w, () -> w.getRunUpdatedAt("r1")));
        assertEquals("2030-01-01 00:00:00.123", ownerTimestampText("SELECT updated_at FROM sennard.workflow_runs"), "the column holds the UTC fields of the instant");
    }

    /**
     * The same instant is stored as the same TIMESTAMP value and read back as the same instant whatever zone the JVM is in. The instant is a local time
     * that does not exist in New York (the March gap), where a java.sql.Timestamp would move it by an hour.
     */
    @Test
    void aTimeIsStoredAndReadBackIdenticallyWhateverZoneTheJvmIsIn() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        SkillUsageStore usage = store.repository(SkillUsageStore.class);
        Instant gap = Instant.parse("2026-03-08T07:30:00.123Z");
        java.util.TimeZone previous = java.util.TimeZone.getDefault();
        try {
            for (String zone : List.of("UTC", "Asia/Seoul", "America/New_York")) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));
                String run = "run-" + zone.replace('/', '-');
                store.write(w, () -> {
                    w.insertRun(run(run, 1, RunState.RUNNING), "2026-03-08T07:30:00.123Z", "CHANGE", List.of());
                    return null;
                });
                assertEquals("2026-03-08T07:30:00.123Z", store.write(w, () -> w.getRunUpdatedAt(run)), zone);
                assertEquals("2026-03-08 07:30:00.123", ownerTimestampText("SELECT updated_at FROM sennard.workflow_runs WHERE run_id = '" + run + "'"), zone);
                store.write(usage, () -> {
                    usage.record(new SkillUsageStore.Call("host", "session-" + zone, "skill", false, "basis", "call-" + zone, gap));
                    return null;
                });
                assertEquals("2026-03-08T07:30:00.123Z", store.write(usage, () -> usage.summary(36500, gap.plusSeconds(1))).hosts().get(0).firstAt(), zone);
            }
        } finally {
            java.util.TimeZone.setDefault(previous);
        }
    }

    @Test
    void sessionSeenAndDeliveriesAreKeptForThirtyDays() throws Exception {
        ContextLayerStore c = store.repository(ContextLayerStore.class);
        ContextLayerStore.Delivery delivery = new ContextLayerStore.Delivery("g", "r", "t");
        store.write(c, () -> {
            c.touchSession("claude", "s", T0);
            c.recordDelivery("actor", delivery, T0);
            c.touchSession("claude", "other", "2026-01-31T00:00:00.000Z");
            c.recordDelivery("other", delivery, "2026-01-31T00:00:00.000Z");
            return null;
        });
        assertEquals(T0, store.write(c, () -> c.sessionSeen("claude", "s")), "exactly thirty days");
        assertNotNull(store.write(c, () -> c.delivered("actor")), "exactly thirty days");
        store.write(c, () -> {
            c.touchSession("claude", "other", "2026-01-31T00:00:00.001Z");
            c.recordDelivery("other", delivery, "2026-01-31T00:00:00.001Z");
            return null;
        });
        assertNull(store.write(c, () -> c.sessionSeen("claude", "s")));
        assertNull(store.write(c, () -> c.delivered("actor")));
    }

    /** AGS3-D-0068: the root a child conversation was linked to is read back, and a later use of the child keeps the link. */
    @Test
    void aChildSessionKeepsItsRootLinkThroughLaterUse() throws Exception {
        ContextLayerStore c = store.repository(ContextLayerStore.class);
        assertNull(store.write(c, () -> c.rootOf("antigravity", "child")), "an unknown session has no root");
        store.write(c, () -> {
            c.touchSession("antigravity", "child", T0);
            return null;
        });
        assertNull(store.write(c, () -> c.rootOf("antigravity", "child")), "a session that is not linked has no root");
        store.write(c, () -> {
            c.linkRoot("antigravity", "child", "root", T0);
            c.touchSession("antigravity", "child", T1);
            return null;
        });
        assertEquals("root", store.write(c, () -> c.rootOf("antigravity", "child")), "a touch leaves the link");
        assertEquals(T1, store.write(c, () -> c.sessionSeen("antigravity", "child")));
        assertNull(store.write(c, () -> c.rootOf("claude", "child")), "the link is of the host that made it");
    }

    /** The other side of a compare-and-set: a revision already passed is as wrong as one not reached yet. */
    @Test
    void aRevisionAlreadyPassedIsRefusedForRootsAndRuns() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        store.write(w, () -> w.insertConvergenceRoot(root("root-1", 1, RootState.OPEN, T0), null, active -> freePlan("root-1")));
        assertTrue(w.insertAttemptLease(root("root-1", 2, RootState.OPEN, T1), 1, lease("l1", "root-1", 2, 1)));
        assertFalse(w.insertAttemptLease(root("root-1", 3, RootState.OPEN, T1), 1, lease("l2", "root-1", 3, 2)));
        assertEquals(2, ownerLong("SELECT revision FROM sennard.convergence_roots"));
        store.write(w, () -> {
            w.insertRun(run("r1", 1, RunState.RUNNING), T0, "CHANGE", List.of());
            return null;
        });
        assertTrue((boolean) store.write(w, () -> w.updateRun(run("r1", 2, RunState.RUNNING), 1, null, null, null, T1)));
        assertFalse((boolean) store.write(w, () -> w.updateRun(run("r1", 3, RunState.PASSED), 1, null, null, null, T1)));
        assertEquals(2, ownerLong("SELECT revision FROM sennard.workflow_runs"));
    }

    @Test
    void anAttemptOutcomeIsRecordedOnce() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        store.write(w, () -> w.insertConvergenceRoot(root("root-1", 1, RootState.OPEN, T0), null, active -> freePlan("root-1")));
        assertTrue(w.insertAttemptLease(root("root-1", 2, RootState.OPEN, T1), 1, lease("l1", "root-1", 2, 1)));
        assertNotNull(w.insertGuardedRun(run("r1", 1, RunState.RUNNING), "l1", 2, T1, "CHANGE", List.of()));
        assertTrue((boolean) store.write(w, () -> w.updateRun(run("r1", 2, RunState.RUNNING), 1, root("root-1", 4, RootState.OPEN, T1), 3L, new WorkflowStore.Outcome(AttemptState.PASSED, "{\"o\":1}", T1), T1)));
        assertEquals(StoreCode.LEASE_CONFLICT, assertThrows(StoreException.class, () -> store.write(w, () -> w.updateRun(run("r1", 3, RunState.PASSED), 2, root("root-1", 5, RootState.OPEN, T1), 4L, new WorkflowStore.Outcome(AttemptState.FAILED, "{\"o\":2}", T1), T1))).code());
        assertEquals("{\"o\":1}", store.write(w, () -> w.getGuardedRunBinding("r1")).outcomeJson());
    }

    @Test
    void cleanupRefusesARootWhoseRevisionMovedSinceThePreview() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        store.write(w, () -> w.insertConvergenceRoot(root("root-1", 1, RootState.COMPLETED, T0), null, active -> freePlan("root-1")));
        WorkflowStore.CleanupPreview preview = store.write(w, () -> w.previewCleanup(CUT));
        assertEquals(1, preview.roots().size());
        owner("UPDATE sennard.convergence_roots SET revision = 9");
        assertEquals(StoreCode.STALE_REVISION, assertThrows(StoreException.class, () -> store.write(w, () -> w.executeCleanup(preview, "plan-1", LATER))).code());
    }

    @Test
    void anEpochAlreadyPassedChangesNothing() throws Exception {
        ContinuityStore c = store.repository(ContinuityStore.class);
        store.write(c, () -> c.ensureTask("t", T0));
        store.write(c, () -> c.rotateEpoch("t", T1, null));
        assertFalse((boolean) store.write(c, () -> c.bindRoot("t", 1, "root-1", T1)));
        assertFalse((boolean) store.write(c, () -> c.setSuppressed("t", 1, T1)));
        ContinuityStore.Task task = store.write(c, () -> c.readTask("t"));
        assertNull(task.rootId());
        assertFalse(task.suppressed());
        assertEquals(2, store.write(c, () -> c.rotateEpoch("t", T1, new ContinuityStore.Expected(1, null, () -> true, false))).currentEpoch(), "the expected epoch is over");
    }

    // ------------------------------------------------------------------------ design B.4, the rows no test above runs in parallel

    /**
     * B.4 continuity and rotation: eight rotations from the epoch they read each name that epoch in the {@code WHERE} of one UPDATE, so no rotation fails and the epoch only moves
     * forward (a rotation that meets another's commit changes no row and answers the epoch it finds); the count of moves is not fixed, because nothing serializes the callers.
     */
    @Test
    void parallelRotationsNeverFailAndOnlyMoveTheEpochForward() throws Exception {
        ContinuityStore c = store.repository(ContinuityStore.class);
        store.write(c, () -> c.ensureTask("t", T0));
        List<Long> epochs = parallel(8, worker -> store.transaction(TxOptions.write(), tx -> c.rotateEpoch("t", T1, null).currentEpoch()));
        long last = store.write(c, () -> c.readTask("t")).currentEpoch();
        assertTrue(last >= 2 && last <= 9, "the epoch moved between once and eight times: " + last);
        assertEquals(last, epochs.stream().mapToLong(Long::longValue).max().orElseThrow());
        assertTrue(epochs.stream().allMatch(epoch -> epoch >= 2), epochs.toString());
    }

    /**
     * B.4 trust: eight writers of the same key and the same receipt together. The key and the receipt are rows with a primary key, so exactly one row of each is stored; a writer that
     * meets the first one's uncommitted row fails on the key ({@code STORE_UNIQUE_VIOLATION}), and every writer that returns got the stored value.
     */
    @Test
    void parallelTrustWritersStoreOneKeyAndOneReceipt() throws Exception {
        TrustStore t = store.repository(TrustStore.class);
        List<Object> keys = parallel(8, worker -> {
            try {
                return store.plain(() -> {
                    t.insertSigningKey(T0);
                    return t.requireSigningKey();
                });
            } catch (StoreException lost) {
                return lost.code();
            }
        });
        SecretValue key = store.plain(t::requireSigningKey);
        assertEquals(StoreCode.STORE_UNIQUE_VIOLATION, assertThrows(StoreException.class, () -> store.plain(() -> {
            t.insertSigningKey(T1);
            return null;
        })).code(), "a second key is refused by the primary key");
        assertEquals(key, store.plain(t::requireSigningKey), "the stored key is unchanged");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.trust_metadata WHERE meta_key = 'trust-signing-key'"), "one key row");
        assertTrue(keys.stream().allMatch(value -> value.equals(key) || value == StoreCode.STORE_UNIQUE_VIOLATION), keys.toString());
        List<Object> stored = parallel(8, worker -> {
            try {
                return store.plain(() -> t.recordInputSource(
                        new TrustStore.Receipt("r1", "claude", "s1", "e1", "user", "none", T0, T1, "{\"worker\":" + worker + "}"), prior -> true));
            } catch (StoreException lost) {
                return lost.code();
            }
        });
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.input_source_receipts"));
        assertTrue(stored.stream().filter(value -> value != StoreCode.STORE_UNIQUE_VIOLATION).distinct().count() <= 1, "every writer that returned got the row the first one stored: " + stored);
    }

    /** B.4 context-layer: from one state token one save wins; the log numbers its records without a gap. */
    @Test
    void parallelContextSavesFromOneTokenHaveOneWinner() throws Exception {
        ContextLayerStore context = store.repository(ContextLayerStore.class);
        String token = assertInstanceOf(ContextLayerStore.RoleSaved.class, store.write(context, () -> context.saveRole("lead", null, "base", "me", T0))).record().stateToken();
        List<ContextLayerStore.RoleSave> saves = parallel(8, worker -> store.write(context, () -> context.saveRole("lead", token, "body-" + worker, "me", T1)));
        assertEquals(1, saves.stream().filter(ContextLayerStore.RoleSaved.class::isInstance).count(), saves.toString());
        assertEquals(7, saves.stream().filter(ContextLayerStore.RoleStale.class::isInstance).count(), saves.toString());
        List<Long> numbers = parallel(8, worker -> store.write(context, () -> context.appendRecord(
                new ContextLayerStore.LogRecord(0, "global", "edit", "cred", "ad", "cd", "sig", "hash-" + worker, T0), "request-" + worker)).seq());
        assertEquals(8, new java.util.HashSet<>(numbers).size(), numbers.toString());
    }

    /** B.4 row CAS: no module lock, the row lock is enough. (The single upsert of the board is BoardServiceTest's case c.) */
    @Test
    void aCompareAndSetHasOneWinner() throws Exception {
        ContextLayerStore context = store.repository(ContextLayerStore.class);
        store.write(context, () -> context.addProposal(new ContextLayerStore.Proposal("p", "global", "c", "b", "claude", "s", "a", T0, ProposalState.PENDING, null)));
        List<Boolean> decided = parallel(8, worker -> store.plain(() -> context.decideProposal("p", ProposalState.APPLIED, T1)));
        assertEquals(1, java.util.Collections.frequency(decided, Boolean.TRUE), decided.toString());
    }

    // ---------------------------------------------------------------------------------------------------------- background jobs

    private void awaitJobState(UUID job, JobState state) throws Exception {
        String sql = "SELECT count(*) FROM sennard.background_job WHERE job_id = '" + job + "' AND state = '" + state + "'";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (ownerLong(sql) == 0 && System.nanoTime() < deadline) {
            LockWaits.pause();
        }
        assertEquals(1, ownerLong(sql), "job " + job + " reached " + state);
    }

    @Test
    void aJobStartsAfterItsTransactionCommitsAndKeepsItsResult() throws Exception {
        JobService jobs = store.context().getBean(JobService.class);
        String input = "ok-" + UUID.randomUUID();
        UUID job = store.transaction(TxOptions.write(), tx -> {
            UUID queued = jobs.enqueue("test", input);
            assertEquals(0, store.jobs().runs(input), "nothing starts before the commit");
            return queued;
        });
        awaitJobState(job, JobState.SUCCEEDED);
        assertEquals(1, store.jobs().runs(input));
        JobStore.BackgroundJob row = jobs.find(job).orElseThrow();
        assertEquals("{\"done\":true}", row.resultJson());
        assertNotNull(row.startedAt());
        assertNotNull(row.finishedAt());
        assertNull(row.errorCode());
    }

    @Test
    void aJobOfARolledBackTransactionNeverExistsOrRuns() throws Exception {
        JobService jobs = store.context().getBean(JobService.class);
        String input = "ok-" + UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> store.transaction(TxOptions.write(), tx -> {
            jobs.enqueue("test", input);
            throw new IllegalStateException("the business write failed");
        }));
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.background_job"));
        assertEquals(0, store.jobs().runs(input));
    }

    /** The claim is what makes a job run once: the same start delivered eight times at once runs it once. */
    @Test
    void aJobDeliveredManyTimesRunsOnce() throws Exception {
        JobService jobs = store.context().getBean(JobService.class);
        String input = "ok-" + UUID.randomUUID();
        UUID job = UUID.randomUUID();
        owner("INSERT INTO sennard.background_job (job_id, kind, state, input_json, created_at) VALUES ('" + job + "', 'test', '" + JobState.QUEUED + "', '" + input + "', now())");
        List<Boolean> claims = parallel(8, worker -> jobs.claim(job));
        assertEquals(1, java.util.Collections.frequency(claims, Boolean.TRUE), claims.toString());
        assertEquals(0, store.jobs().runs(input), "the claim itself runs nothing");
    }

    @Test
    void aFailedJobIsRecordedWithItsCode() throws Exception {
        JobService jobs = store.context().getBean(JobService.class);
        for (String[] row : new String[][] {{"test", "boom", "JOB_FAILED"}, {"test", "store-busy", "STORE_BUSY"}, {"no-such-kind", "x", "JOB_KIND_UNKNOWN"}}) {
            UUID job = jobs.enqueue(row[0], row[1]);
            awaitJobState(job, JobState.FAILED);
            assertEquals(row[2], jobs.find(job).orElseThrow().errorCode());
        }
    }

    /** A restart closes a running job as of unknown effect without repeating it, and starts a queued one. */
    @Test
    void theStartUpRecoveryClosesRunningJobsAndRestartsQueuedOnes() throws Exception {
        JobService jobs = store.context().getBean(JobService.class);
        UUID running = UUID.randomUUID();
        UUID queued = UUID.randomUUID();
        String input = "ok-" + UUID.randomUUID();
        String insert = "INSERT INTO sennard.background_job (job_id, kind, state, input_json, created_at) VALUES ";
        owner(insert + "('" + running + "', 'test', '" + JobState.RUNNING + "', 'never-rerun', now())");
        owner(insert + "('" + queued + "', 'test', '" + JobState.QUEUED + "', '" + input + "', now())");
        assertEquals(1, jobs.recover(), "one job starts again");
        awaitJobState(queued, JobState.SUCCEEDED);
        assertEquals(1, store.jobs().runs(input));
        assertEquals(0, store.jobs().runs("never-rerun"));
        JobStore.BackgroundJob closed = jobs.find(running).orElseThrow();
        assertEquals(JobState.FAILED, closed.state());
        assertEquals("SERVICE_RESTARTED", closed.errorCode());
        assertEquals("{\"effect\":\"unknown\"}", closed.resultJson());
    }

    // ------------------------------------------------------------------------------ two connections: what decides a race when a write takes no module lock

    /**
     * What a race of two transactions ended in: the first one's value, and the second one's value or the failure it ended in (exactly one of the two is not null).
     */
    private record Raced<A, B>(A first, B second, Throwable secondFailure) {
    }

    /**
     * Two transactions on two connections, in two threads, with no module lock: the first runs its body and stays uncommitted; the second then runs its body, and the test waits, on the
     * condition and not on a clock, until the database reports a statement waiting for a lock (the second one wants what the first one holds) and checks the second has not answered;
     * then the first commits, and the second ends with what the database decides after that commit.
     */
    private <A, B> Raced<A, B> raceOfTwoTransactions(Supplier<A> first, Supplier<B> second) throws Exception {
        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch firstMayCommit = new CountDownLatch(1);
        AtomicLong firstBackend = new AtomicLong(); // the database's identifier of the first transaction's connection, taken inside that transaction
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<A> firstRun = pool.submit(() -> store.plain(() -> {
                firstBackend.set(LockWaits.backendOfThisTransaction(BusinessDb.pool()));
                A value = first.get();
                firstHolds.countDown();
                awaitRelease(firstMayCommit);
                return value;
            }));
            assertTrue(firstHolds.await(30, TimeUnit.SECONDS), "the first transaction holds its write");
            Future<B> secondRun = pool.submit(() -> store.plain(second));
            LockWaits.awaitWaiterBlockedBy(firstBackend.get(), Duration.ofSeconds(30), "no statement came to wait for the first transaction");
            assertFalse(secondRun.isDone(), "the second transaction waits for the first one's commit");
            firstMayCommit.countDown();
            A firstValue = firstRun.get(60, TimeUnit.SECONDS);
            try {
                return new Raced<>(firstValue, secondRun.get(60, TimeUnit.SECONDS), null);
            } catch (java.util.concurrent.ExecutionException failed) {
                return new Raced<>(firstValue, null, failed.getCause());
            }
        } finally {
            firstMayCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void ofTwoLinksToTheSameChildOnlyTheFirstRootIsKept() throws Exception {
        ContextLayerStore context = store.repository(ContextLayerStore.class);
        store.plain(() -> {
            context.touchSession("antigravity", "contested-child", T0);
            return null;
        });
        Raced<String, String> raced = raceOfTwoTransactions(() -> {
            context.linkRoot("antigravity", "contested-child", "first-root", T1);
            return context.rootOf("antigravity", "contested-child");
        }, () -> {
            context.linkRoot("antigravity", "contested-child", "other-root", CUT);
            return context.rootOf("antigravity", "contested-child");
        });
        assertEquals("first-root", raced.first());
        assertNull(raced.secondFailure());
        assertEquals("first-root", raced.second(), "the second transaction leaves the established root intact");
        assertEquals(T1, store.plain(() -> context.sessionSeen("antigravity", "contested-child")));
    }

    /** Two callers that name the current state token of a role body: the second one's UPDATE waits, then changes no row; its body and history are not written. */
    @Test
    void ofTwoSavesOfOneRoleBodyTokenTheSecondChangesNoRowAndWritesNoHistory() throws Exception {
        ContextLayerStore context = store.repository(ContextLayerStore.class);
        ContextLayerStore.RoleSaved base = assertInstanceOf(ContextLayerStore.RoleSaved.class, store.plain(() -> context.saveRole("lead", null, "base", "me", T0)));
        String token = base.record().stateToken();
        Raced<ContextLayerStore.RoleSave, ContextLayerStore.RoleSave> raced = raceOfTwoTransactions(
                () -> context.saveRole("lead", token, "winner", "me", T1), () -> context.saveRole("lead", token, "loser", "me", T1));
        ContextLayerStore.RoleSaved winner = assertInstanceOf(ContextLayerStore.RoleSaved.class, raced.first());
        ContextLayerStore.RoleStale lost = assertInstanceOf(ContextLayerStore.RoleStale.class, raced.second());
        assertEquals(winner.record().stateToken(), lost.actualStateToken(), "the loser is told the winner's token");
        assertEquals("winner", store.plain(() -> context.readRole("lead").body()));
        assertEquals(2, ownerLong("SELECT count(*) FROM sennard.role_context_history WHERE role_id = 'lead'"), "the first write and the winner's: the loser wrote none");
    }

    /** Two first writes of a role body: the second one's INSERT waits for the first one's key, then fails on it (the service answers that as the stale conflict). */
    @Test
    void ofTwoFirstSavesOfARoleBodyTheSecondFailsOnThePrimaryKey() throws Exception {
        ContextLayerStore context = store.repository(ContextLayerStore.class);
        Raced<ContextLayerStore.RoleSave, ContextLayerStore.RoleSave> raced = raceOfTwoTransactions(
                () -> context.saveRole("fresh", null, "winner", "me", T0), () -> context.saveRole("fresh", null, "loser", "me", T0));
        assertInstanceOf(ContextLayerStore.RoleSaved.class, raced.first());
        assertEquals(StoreCode.STORE_UNIQUE_VIOLATION, assertInstanceOf(StoreException.class, raced.secondFailure()).code());
        assertEquals("winner", store.plain(() -> context.readRole("fresh").body()));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.role_context_history WHERE role_id = 'fresh'"));
    }

    /** Two decisions on one waiting root approval: the first DELETE changes the row, the second one's changes none, so the request is decided once. */
    @Test
    void ofTwoDecisionsOnOneWaitingRootApprovalOnlyTheFirstDeletesTheRequest() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        store.plain(() -> {
            w.savePendingRootApproval("scope-wait", "{}", T0);
            return null;
        });
        Raced<Boolean, Boolean> raced = raceOfTwoTransactions(() -> w.deletePendingRootApproval("scope-wait"), () -> w.deletePendingRootApproval("scope-wait"));
        assertTrue(raced.first(), "the first decision took the request");
        assertFalse(raced.second(), "the second one's DELETE changed no row");
        assertEquals(0, store.plain(() -> w.listPendingRootApprovals()).size());
    }

    /** Two callers that claim one execution observation: the second one's INSERT waits for the first one's key, then fails on it (the service answers that as a consumed observation). */
    @Test
    void ofTwoClaimsOfOneExecutionObservationTheSecondFailsOnThePrimaryKey() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        Raced<Boolean, Boolean> raced = raceOfTwoTransactions(() -> w.claimExecutionObservation("obs-race", T0), () -> w.claimExecutionObservation("obs-race", T1));
        assertTrue(raced.first());
        assertEquals(StoreCode.STORE_UNIQUE_VIOLATION, assertInstanceOf(StoreException.class, raced.secondFailure()).code());
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.execution_observation_claims WHERE observation_id = 'obs-race'"));
    }

    /** Two changes of a run that name the same revision (record_stage_result and abort_workflow meeting): the second one's UPDATE changes no row, and the run holds the first one's receipt. */
    @Test
    void ofTwoChangesOfOneRunRevisionTheSecondChangesNoRow() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        store.plain(() -> {
            w.insertRun(run("race-run", 1, RunState.RUNNING), T0, "CHANGE", List.of());
            return null;
        });
        Raced<Boolean, Boolean> raced = raceOfTwoTransactions(() -> w.updateRun(run("race-run", 2, RunState.PASSED), 1, null, null, null, T1),
                () -> w.updateRun(run("race-run", 2, RunState.BLOCKED), 1, null, null, null, T1));
        assertTrue(raced.first());
        assertFalse(raced.second(), "the second change's WHERE revision = 1 no longer holds after the first one's commit");
        assertEquals(RunState.PASSED.name(), ownerText("SELECT state FROM sennard.workflow_runs WHERE run_id = 'race-run'"));
    }

    /** Two claims of an attempt on one root revision: the second one's CAS on the root changes no row, so it issues no lease. */
    @Test
    void ofTwoLeaseClaimsOnOneRootRevisionTheSecondChangesNoRowAndIssuesNoLease() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        store.plain(() -> w.insertConvergenceRoot(root("race-root", 1, RootState.OPEN, T0), null, active -> freePlan("race-root")));
        Raced<Boolean, Boolean> raced = raceOfTwoTransactions(() -> w.insertAttemptLease(root("race-root", 2, RootState.OPEN, T1), 1, lease("race-l1", "race-root", 2, 1)),
                () -> w.insertAttemptLease(root("race-root", 2, RootState.OPEN, T1), 1, lease("race-l2", "race-root", 2, 1)));
        assertTrue(raced.first());
        assertFalse(raced.second());
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.convergence_leases WHERE root_id = 'race-root'"), "the loser issued no lease");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.convergence_issued_leases WHERE root_id = 'race-root' AND lease_id = 'race-l1'"));
    }

    /** Two starts that consume one issued lease: the second one's UPDATE ... WHERE state = ISSUED changes no row, so it starts no run. */
    @Test
    void ofTwoStartsOfOneLeaseTheSecondStartsNoRun() throws Exception {
        WorkflowStore w = store.repository(WorkflowStore.class);
        store.plain(() -> {
            w.insertConvergenceRoot(root("start-root", 1, RootState.OPEN, T0), null, active -> freePlan("start-root"));
            return w.insertAttemptLease(root("start-root", 2, RootState.OPEN, T0), 1, lease("start-l1", "start-root", 2, 1));
        });
        Raced<WorkflowStore.GuardedBinding, WorkflowStore.GuardedBinding> raced = raceOfTwoTransactions(() -> w.insertGuardedRun(run("start-r1", 1, RunState.RUNNING), "start-l1", 2, T1, "CHANGE", List.of()),
                () -> w.insertGuardedRun(run("start-r2", 1, RunState.RUNNING), "start-l1", 2, T1, "CHANGE", List.of()));
        assertNotNull(raced.first());
        assertNull(raced.second(), "the lease was consumed by the first start");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.workflow_runs WHERE run_id IN ('start-r1', 'start-r2')"));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.workflow_runs WHERE run_id = 'start-r1'"));
    }
}
