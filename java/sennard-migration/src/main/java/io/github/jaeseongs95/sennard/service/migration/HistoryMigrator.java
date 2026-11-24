// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder.Database;

/**
 * The one migration runner of the product: the scripts of a jar ({@code V<number>__<description>.sql}) applied in number order, once each, with plain JDBC and Spring's script reader
 * ({@link ResourceDatabasePopulator}, which splits a script at its semicolons and knows quoted text and comments). It replaces Flyway, which has a list of the databases it knows
 * and a part for each; this class holds no DBMS name. What it needs from the database is the SQL standard: a history table of ANSI types, {@code CREATE SCHEMA}, the long text declaration the SqlBuilder
 * gives for the product ({@link SqlBuilder#generateLongText}) and the catalog {@code information_schema}. The business database's runner and the settings store's are adapters of
 * this class: they open the connection and turn a {@link MigrationException} into their own failure; the format and the rules are here, once.
 *
 * <p>The history table (a {@link MigrationTarget}) has a row per attempt: {@code (version, attempt)} is its key, with the script's file, the SHA-256 of the file, when the attempt
 * was made (a UTC {@code TIMESTAMP} from the injected clock) and whether it {@link Outcome#SUCCEEDED} or {@link Outcome#FAILED}. An attempt is one transaction. Its first statement
 * inserts its row as {@code FAILED}, which claims the number; its last statement turns that row, and no other, to {@code SUCCEEDED}. A row is never deleted and no row but the
 * attempt's own is changed. A script that failed leaves its {@code FAILED} row, and the next run makes attempt + 1.
 *
 * <p>The scripts are numbered 1, 2, 3 ... one after the other ({@link MigrationCode#GAP} otherwise). A script that was applied is never read again, and a changed file is refused:
 * the run stops with {@link MigrationCode#CHANGED} before it applies anything and adds no row, because the database was made by other text than the jar holds (a changed file is no
 * attempt). {@link MigrationCode#MISSING} (an applied number the jar has no script for) and {@link MigrationCode#OUT_OF_ORDER} (a script not applied below the highest applied one)
 * stop the run the same way.
 *
 * <p>Two runners at once do not apply a script twice, and neither waits by a clock: both insert the claim {@code (version, attempt)}, so the second one waits in the database for the
 * first one's transaction and then gets a duplicate key. It reads the history once more: when the number is {@code SUCCEEDED} with the digest it has, the other runner did the work
 * and it goes on; otherwise the other one failed and it stops with {@link MigrationCode#FAILED} (the next start tries again). A database whose DDL is not transactional (MariaDB,
 * MySQL: each DDL statement commits what came before it, the claim included) so never shows a half-applied script as {@code SUCCEEDED}: what it has committed of it says
 * {@code FAILED} until the whole script has run.
 */
public final class HistoryMigrator {
    private static final Logger LOG = LoggerFactory.getLogger(HistoryMigrator.class);

    /** What a history row says. The column holds the constant's name and, where the database takes it (MigrationTarget), a CHECK lists the two. */
    enum Outcome { SUCCEEDED, FAILED }

    /** One exact statement to execute with the attempt timestamp as its sole JDBC parameter. */
    public record TimestampBinding(String originalStatement, String parameterizedStatement) {}

    @FunctionalInterface
    public interface TimestampBindings {
        Optional<TimestampBinding> forScript(MigrationScript script);
    }

    private record HistoryRow(long version, int attempt, String script, String digest, Outcome outcome) {
        /**
         * One history row as the database holds it. The outcome column has no CHECK on every database, so a name that is none of {@link Outcome} means the history cannot be read: the
         * run stops with {@link MigrationCode#FAILED} before it applies anything.
         */
        static HistoryRow read(MigrationTarget target, long version, int attempt, String script, String digest, String outcome) {
            for (Outcome known : Outcome.values()) {
                if (known.name().equals(outcome)) {
                    return new HistoryRow(version, attempt, script, digest, known);
                }
            }
            throw new MigrationException(MigrationCode.FAILED, "The history (" + target.location() + ") cannot be read: version " + version + ", attempt " + attempt
                    + " has the outcome '" + outcome + "', which is none of " + Arrays.toString(Outcome.values()) + "; nothing was applied.");
        }
    }

    private final Clock clock;

    /** @param clock the application's one Clock, which stamps the history rows */
    public HistoryMigrator(Clock clock) {
        this.clock = clock;
    }

    /**
     * Applies the pending ones of these scripts (the jar's, or the ones a test gives); the number applied by this call. The connection is the caller's own, of the account that may
     * create tables; its auto-commit is turned off here, and it is left open for the caller to close.
     *
     * @throws MigrationException the run stopped for a reason of {@link MigrationCode}
     * @throws SQLException the database answered with a failure the runner has no code for
     */
    public int run(Connection connection, MigrationTarget target, List<MigrationScript> scripts) throws SQLException {
        return run(connection, target, scripts, script -> Optional.empty());
    }

    /** Applies pending scripts, allowing the adapter to bind the attempt timestamp to a statement in a pending script. */
    public int run(Connection connection, MigrationTarget target, List<MigrationScript> scripts,
            TimestampBindings timestampBindings) throws SQLException {
        connection.setAutoCommit(false);
        Database product = SqlBuilder.ofProductName(connection.getMetaData().getDatabaseProductName());
        ScriptDialect dialect = ScriptDialect.of(product);
        JdbcClient history = JdbcClient.create(new SingleConnectionDataSource(connection, true));
        ensureHistory(connection, history, target, dialect);
        List<HistoryRow> rows = readHistory(history, target);
        connection.commit();
        List<MigrationScript> pending = pending(scripts, rows, target);
        String longText = SqlBuilder.generateLongText(product);
        int applied = 0;
        for (MigrationScript script : pending) {
            if (apply(connection, history, target, script, nextAttempt(rows, script.version()),
                    script.text(longText, dialect), timestampBindings)) {
                applied++;
            }
        }
        return applied;
    }

    // ------------------------------------------------------------------------------------------------------------ history

    /** Makes the schema and the history table when they are not there. */
    private static void ensureHistory(Connection connection, JdbcClient history, MigrationTarget target, ScriptDialect dialect) throws SQLException {
        if (!schemaExists(history, target)) {
            create(connection, "CREATE SCHEMA " + target.schema(), () -> schemaExists(history, target));
        }
        if (!tableExists(history, target)) {
            create(connection, dialect.translate(historyTable(target)), () -> tableExists(history, target));
        }
    }

    private static String historyTable(MigrationTarget target) {
        return "CREATE TABLE " + target.qualified() + " (version BIGINT NOT NULL, attempt INTEGER NOT NULL, script VARCHAR(255) NOT NULL, digest CHAR(64) NOT NULL, "
                + "applied_at TIMESTAMP NOT NULL, outcome VARCHAR(10) NOT NULL, PRIMARY KEY (version, attempt))";
    }

    /** Runs one DDL statement. A runner that started at the same moment may have made the object first, which fails this statement: then the object is there, and that is what was wanted. */
    private static void create(Connection connection, String ddl, ExistsCheck exists) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            if (!exists.test()) {
                throw failure;
            }
            LOG.debug("The runner that came first made the object; this statement is not needed: {}", ddl, failure);
        }
    }

    @FunctionalInterface
    private interface ExistsCheck {
        boolean test() throws SQLException;
    }

    /**
     * Whether the schema is there, asked of the standard catalog ({@code information_schema}) and not of the driver's {@code getSchemas()}: a driver of a database whose schema is its
     * database (MariaDB, MySQL) lists no schema there. The names are compared in lower case, which is how the scripts write them and how a catalog that folds identifiers spells them
     * once the case is not the question.
     */
    private static boolean schemaExists(JdbcClient catalog, MigrationTarget target) {
        return catalog.sql("SELECT COUNT(*) FROM information_schema.schemata WHERE LOWER(schema_name) = ?").param(target.schema().toLowerCase(Locale.ROOT))
                .query(Long.class).single() > 0;
    }

    private static boolean tableExists(JdbcClient catalog, MigrationTarget target) {
        return catalog.sql("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_schema) = ? AND LOWER(table_name) = ?")
                .params(target.schema().toLowerCase(Locale.ROOT), target.table().toLowerCase(Locale.ROOT)).query(Long.class).single() > 0;
    }

    private static List<HistoryRow> readHistory(JdbcClient history, MigrationTarget target) {
        return history.sql("SELECT version, attempt, script, digest, outcome FROM " + target.qualified() + " ORDER BY version, attempt")
                .query((resultSet, rowNumber) -> HistoryRow.read(target, resultSet.getLong("version"), resultSet.getInt("attempt"), resultSet.getString("script"),
                        resultSet.getString("digest"), resultSet.getString("outcome")))
                .list();
    }

    /** The attempt a script makes next: one more than its highest row in the history. */
    private static int nextAttempt(List<HistoryRow> history, long version) {
        return history.stream().filter(row -> row.version() == version).mapToInt(HistoryRow::attempt).max().orElse(0) + 1;
    }

    /**
     * The scripts still to apply, in number order, after the numbers and the history were checked against the jar: the scripts are numbered 1, 2, 3 ... without a gap, every script
     * that was applied has to be in the jar with the digest it had, and no script of the jar that is not applied lies below the highest one applied (a number added behind the
     * database's back). A {@code FAILED} row is no applied script: its file may have been fixed since.
     */
    private static List<MigrationScript> pending(List<MigrationScript> given, List<HistoryRow> history, MigrationTarget target) {
        List<MigrationScript> scripts = given.stream().sorted(Comparator.comparingLong(MigrationScript::version)).toList();
        long expected = 1;
        for (MigrationScript script : scripts) {
            if (script.version() != expected) {
                throw new MigrationException(MigrationCode.GAP, "The scripts (" + target.location() + ") are not numbered one after the other: the number " + expected
                        + " is expected next and " + script.file() + " has " + script.version() + ".");
            }
            expected++;
        }
        Map<Long, MigrationScript> byVersion = scripts.stream().collect(Collectors.toMap(MigrationScript::version, script -> script));
        long highest = 0;
        Set<Long> done = history.stream().filter(row -> row.outcome() == Outcome.SUCCEEDED).map(HistoryRow::version).collect(Collectors.toSet());
        for (HistoryRow row : history) {
            if (row.outcome() != Outcome.SUCCEEDED) {
                continue;
            }
            MigrationScript script = byVersion.get(row.version());
            if (script == null) {
                throw new MigrationException(MigrationCode.MISSING, "The database has applied " + row.script() + " (" + target.location() + "), which this version has no script for.");
            }
            if (!script.digest().equals(row.digest().strip())) {
                throw new MigrationException(MigrationCode.CHANGED,
                        "The script " + script.file() + " (" + target.location() + ") is not the one that was applied: its digest differs from the history.");
            }
            highest = Math.max(highest, row.version());
        }
        List<MigrationScript> pending = scripts.stream().filter(script -> !done.contains(script.version())).toList();
        for (MigrationScript script : pending) {
            if (script.version() < highest) {
                throw new MigrationException(MigrationCode.OUT_OF_ORDER,
                        "The script " + script.file() + " (" + target.location() + ") has a number below the highest one applied, " + highest + ".");
            }
        }
        return pending;
    }

    // ------------------------------------------------------------------------------------------------------------ one script

    /**
     * Applies one script in its own transaction; false when another runner applied the number first. The attempt is claimed first, as FAILED, and turned to SUCCEEDED by the last
     * statement of the transaction.
     */
    private boolean apply(Connection connection, JdbcClient history, MigrationTarget target, MigrationScript script, int attempt,
            String sql, TimestampBindings timestampBindings) throws SQLException {
        Optional<TimestampBinding> timestampBinding = timestampBindings.forScript(script);
        int start = timestampBinding.map(binding -> uniqueStatementStart(sql, binding, script, target)).orElse(-1);
        LocalDateTime appliedAt = Timestamps.toTimestamp(clock.instant());
        try {
            claim(history, target, script, attempt, appliedAt);
        } catch (DuplicateKeyException taken) {
            connection.rollback();
            return appliedByAnother(connection, history, target, script, taken);
        }
        try {
            if (timestampBinding.isPresent()) {
                TimestampBinding binding = timestampBinding.orElseThrow();
                int end = start + binding.originalStatement().length();
                populate(connection, script.file(), sql.substring(0, start));
                history.sql(binding.parameterizedStatement()).param(appliedAt).update();
                populate(connection, script.file(), sql.substring(end));
            } else {
                populate(connection, script.file(), sql);
            }
        } catch (DataAccessException failure) {
            connection.rollback();
            recordFailure(connection, history, target, script, attempt, failure);
            throw new MigrationException(MigrationCode.FAILED, "The migration " + script.file() + " did not apply (" + target.location() + ").", failure);
        }
        history.sql("UPDATE " + target.qualified() + " SET outcome = ? WHERE version = ? AND attempt = ?").params(Outcome.SUCCEEDED.name(), script.version(), attempt).update();
        connection.commit();
        return true;
    }

    private static int uniqueStatementStart(String sql, TimestampBinding binding, MigrationScript script, MigrationTarget target) {
        String statement = binding.originalStatement();
        int start = sql.indexOf(statement);
        if (start < 0 || sql.indexOf(statement, start + statement.length()) >= 0) {
            throw new MigrationException(MigrationCode.CHANGED, "The statement to bind in " + script.file() + " (" + target.location() + ") does not match.");
        }
        return start;
    }

    private static void populate(Connection connection, String file, String sql) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(false, false, StandardCharsets.UTF_8.name(),
                new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8), file));
        populator.populate(connection);
    }

    /** The first statement of an attempt: its row, as FAILED. The key is what a second runner of the same number waits on. */
    private static void claim(JdbcClient history, MigrationTarget target, MigrationScript script, int attempt, LocalDateTime appliedAt) {
        history.sql("INSERT INTO " + target.qualified() + " (version, attempt, script, digest, applied_at, outcome) VALUES (?, ?, ?, ?, ?, ?)")
                .params(script.version(), attempt, script.file(), script.digest(), appliedAt, Outcome.FAILED.name()).update();
    }

    /**
     * The claim got a duplicate key: another runner made this attempt (the transaction here is rolled back, so the other one has committed or rolled back its own). The history is
     * read once more: when the number is SUCCEEDED with this script's digest the other runner did the work, and nothing is applied here; any other state is the other runner's
     * failure, which stops this run (the next start makes the next attempt).
     */
    private static boolean appliedByAnother(Connection connection, JdbcClient history, MigrationTarget target, MigrationScript script, DuplicateKeyException taken)
            throws SQLException {
        List<HistoryRow> rows = readHistory(history, target);
        connection.commit();
        boolean done = rows.stream().anyMatch(row -> row.version() == script.version() && row.outcome() == Outcome.SUCCEEDED && script.digest().equals(row.digest().strip()));
        if (!done) {
            throw new MigrationException(MigrationCode.FAILED,
                    "The migration " + script.file() + " (" + target.location() + ") was taken by another runner and it did not succeed there.", taken);
        }
        LOG.debug("Another runner has applied the migration {} ({}).", script.file(), target.location(), taken);
        return false;
    }

    /**
     * The attempt of a script that failed stays in the history, in a transaction of its own (the script's was rolled back). The row is there already when the database committed
     * the claim with the script's first DDL statement (it says FAILED), or when another runner took the attempt; either way no row is added.
     */
    private void recordFailure(Connection connection, JdbcClient history, MigrationTarget target, MigrationScript script, int attempt, DataAccessException cause)
            throws SQLException {
        try {
            claim(history, target, script, attempt, Timestamps.toTimestamp(clock.instant()));
            connection.commit();
        } catch (DuplicateKeyException present) {
            connection.rollback();
            LOG.warn("The migration {} failed here and its attempt is in the history already (committed by the script's own DDL, or taken by another runner); no row is added.",
                    script.file(), cause);
        }
    }
}
