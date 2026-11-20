// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionManager;

import io.github.jaeseongs95.sennard.service.migration.MigrationScript;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLink;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkRequest;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkSettings;
import io.github.jaeseongs95.sennard.service.settings.DatabaseTls;
import io.github.jaeseongs95.sennard.service.settings.PasskeyAlgorithm;
import io.github.jaeseongs95.sennard.service.settings.PasskeyCredential;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistration;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistry;
import io.github.jaeseongs95.sennard.service.settings.PasskeyState;
import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminDraft;
import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminRecord;
import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminRecords;
import io.github.jaeseongs95.sennard.service.settings.SettingsCode;
import io.github.jaeseongs95.sennard.service.settings.SettingsException;
import io.github.jaeseongs95.sennard.service.settings.StoredEnum;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * The settings store against a real H2 file database, through the real Spring wiring (no mock). The case table:
 * <ol>
 * <li>A value outside an enum is refused by the Java check, before a write and when read; the schema has no CHECK to do it.</li>
 * <li>The settings schema has no IN-list or OR-chain CHECK (text of the script and the catalog of the database).</li>
 * <li>A new context on the same folder (a restart) finds the registry and the records as they were.</li>
 * <li>The settings database holds the link of the business database (one row, the path of the password file and never the password): the one source of the business database.</li>
 * <li>The settings DataSource and transaction manager are other beans than the business ones, and the business side's auto-configuration is undisturbed.</li>
 * <li>Times are stored as UTC TIMESTAMP and restored as the same instant, whatever zone the JVM has.</li>
 * <li>With the database open, a new connection's rows of every enum value are accepted.</li>
 * <li>The registry's and the records' own rules (duplicates, revoking, request ids).</li>
 * <li>The migrator refuses a changed, a missing and an out-of-order script, and applies nothing on a rerun.</li>
 * <li>A long Korean text of an intent survives a round trip.</li>
 * <li>The pool's name, sizes and timeout come from {@code sennard.settings.pool.*} (defaults 4, 1, 5 seconds), and a size below 1 is refused at start.</li>
 * </ol>
 */
class SettingsStoreTest {
    private static final Pattern IN_LIST = Pattern.compile("(?is)\\bIN\\s*\\(");

    // ------------------------------------------------------------------------------------------------------------ 1. the enum is the Java check

    @Test
    void aValueOutsideAnEnumIsRefusedByTheJavaCheckBeforeAndAfterStoring(@TempDir Path folder) {
        assertEquals(SettingsCode.VALUE_OUTSIDE_SET, assertThrows(SettingsException.class, () -> PasskeyAlgorithm.ofCose(-65535)).code());
        assertEquals(SettingsCode.VALUE_OUTSIDE_SET, assertThrows(SettingsException.class, () -> StoredEnum.of(PasskeyState.class, "state", "SUSPENDED")).code());
        assertEquals(SettingsCode.VALUE_OUTSIDE_SET, assertThrows(SettingsException.class, () -> StoredEnum.of(PasskeyState.class, "state", null)).code());
        assertEquals(PasskeyAlgorithm.ES256, PasskeyAlgorithm.ofCose(-7));
        SettingsFixtures.runner(folder).run(context -> {
            PasskeyRegistry registry = context.getBean(PasskeyRegistry.class);
            registry.register(SettingsFixtures.passkey("known"));
            // The schema holds no list, so a text outside the enum can reach the table from outside (a file edited by hand); the read refuses it.
            execute(context.getBean("settingsDataSource", DataSource.class), "UPDATE sennard.passkey_credentials SET state = 'SUSPENDED'");
            assertEquals(SettingsCode.STORED_VALUE_UNKNOWN, assertThrows(SettingsException.class, () -> registry.find("known")).code());
            assertEquals(SettingsCode.STORED_VALUE_UNKNOWN, assertThrows(SettingsException.class, registry::list).code());
        });
    }

    // ------------------------------------------------------------------------------------------------------------ 2. no CHECK list in the schema

    @Test
    void theSettingsSchemaHasNoInListOrOrChainCheck(@TempDir Path folder) throws IOException {
        for (String file : new String[] {"V1__settings.sql", "V2__pre_link_section.sql"}) {
            String script = new ClassPathResource("db/settings/" + file).getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            String code = script.lines().map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")) : line).reduce("", (a, b) -> a + "\n" + b);
            assertFalse(code.toUpperCase(java.util.Locale.ROOT).contains("CHECK"), "no CHECK at all in the settings script " + file);
            assertFalse(IN_LIST.matcher(code).find(), "no IN list in the settings script " + file);
        }
        SettingsFixtures.runner(folder).run(context -> {
            List<String> checks = column(context.getBean("settingsDataSource", DataSource.class),
                    "SELECT constraint_name FROM information_schema.table_constraints WHERE constraint_schema = 'SENNARD' AND constraint_type = 'CHECK'");
            assertEquals(List.of(), checks, "the catalog of the settings schema lists no CHECK constraint");
        });
    }

    // ------------------------------------------------------------------------------------------------------------ 3. restart

    @Test
    void theRegistryAndTheRecordsSurviveANewContextOnTheSameFolder(@TempDir Path folder) throws IOException {
        List<PasskeyCredential> keysBefore = new ArrayList<>();
        List<PreLinkAdminRecord> recordsBefore = new ArrayList<>();
        SettingsFixtures.runner(folder).run(context -> {
            PasskeyRegistry registry = context.getBean(PasskeyRegistry.class);
            registry.register(SettingsFixtures.passkey("a"));
            registry.register(SettingsFixtures.passkey("b"));
            registry.revoke("a");
            keysBefore.addAll(registry.list());
            PreLinkAdminRecords records = context.getBean(PreLinkAdminRecords.class);
            recordsBefore.add(records.append(SettingsFixtures.record("keys", "req-1")));
            recordsBefore.add(records.append(SettingsFixtures.record("global", null)));
        });
        assertTrue(Files.exists(folder.resolve("sennard-settings.mv.db")), "the database is a file under the folder");
        SettingsFixtures.runner(folder).run(context -> {
            assertEquals(keysBefore, context.getBean(PasskeyRegistry.class).list());
            assertEquals(PasskeyState.REVOKED, context.getBean(PasskeyRegistry.class).find("a").orElseThrow().state());
            assertEquals(recordsBefore, context.getBean(PreLinkAdminRecords.class).list());
            // The second start applied no script again: the history holds the rows of the first.
            assertEquals(List.of("1", "2"), column(context.getBean("settingsDataSource", DataSource.class), "SELECT CAST(version AS VARCHAR(10)) FROM sennard.history"));
        });
    }

    // ------------------------------------------------------------------------------------------------------------ 5. the beans are apart from the business ones

    @Test
    void theSettingsDataSourceAndTransactionManagerAreOtherBeansAndTheBusinessSideStaysSingle(@TempDir Path folder) {
        SettingsFixtures.runner(folder).withUserConfiguration(SettingsFixtures.BusinessSide.class)
                .withBean("transactionManager", TransactionManager.class, () -> new JdbcTransactionManager(new SettingsFixtures.BusinessStandIn()))
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class))
                .run(context -> {
                    DataSource business = context.getBean("store", DataSource.class);
                    DataSource settings = context.getBean("settingsDataSource", DataSource.class);
                    assertNotSame(business, settings);
                    // By type the application has one DataSource and one transaction manager: the business ones.
                    assertSame(business, context.getBean(DataSource.class));
                    assertSame(context.getBean("transactionManager"), context.getBean(TransactionManager.class));
                    assertNotSame(context.getBean("transactionManager"), context.getBean("settingsTransactionManager"));
                    // Boot's JDBC auto-configuration still made its JdbcTemplate, over the business DataSource.
                    assertSame(business, context.getBean(JdbcTemplate.class).getDataSource());
                    // The settings repositories work through their own beans meanwhile.
                    assertEquals("a", context.getBean(PasskeyRegistry.class).register(SettingsFixtures.passkey("a")).registration().credentialId());
                });
    }

    private static void assertSame(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertSame(expected, actual);
    }

    // ------------------------------------------------------------------------------------------------------------ 6. UTC

    @Test
    void timesAreStoredAsUtcTimestampsAndRestoredWhateverTheJvmZoneIs(@TempDir Path folder) {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : List.of("Asia/Seoul", "America/Los_Angeles", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                Path own = folder.resolve(zone.replace('/', '_'));
                SettingsFixtures.runner(own).run(context -> {
                    PasskeyRegistry registry = context.getBean(PasskeyRegistry.class);
                    registry.register(SettingsFixtures.passkey("k"));
                    PasskeyCredential revoked = registry.revoke("k");
                    assertEquals(SettingsFixtures.NOW, revoked.registeredAt(), zone);
                    assertEquals(SettingsFixtures.NOW, revoked.revokedAt(), zone);
                    assertEquals(List.of("2026-11-13 01:02:03.456"), column(context.getBean("settingsDataSource", DataSource.class),
                            "SELECT CAST(registered_at AS VARCHAR(40)) FROM sennard.passkey_credentials"), zone + ": the stored fields are the UTC ones");
                    assertEquals(SettingsFixtures.NOW, context.getBean(PreLinkAdminRecords.class).append(SettingsFixtures.record("t", null)).createdAt(), zone);
                });
            }
            // An instant that is a gap in a daylight-saving zone (02:30 on 8 March 2026 does not exist in Los Angeles) keeps its UTC fields.
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            Instant gap = Instant.parse("2026-03-08T10:30:00Z");
            assertEquals(gap, io.github.jaeseongs95.sennard.domain.Timestamps.toInstant(io.github.jaeseongs95.sennard.domain.Timestamps.toTimestamp(gap)));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ 7. enum rows with the database open

    @Test
    void withTheDatabaseOpenANewConnectionsRowsOfEveryEnumValueAreAccepted(@TempDir Path folder) {
        SettingsFixtures.runner(folder).run(context -> {
            DataSource pool = context.getBean("settingsDataSource", DataSource.class);
            try (Connection held = pool.getConnection(); Connection fresh = pool.getConnection(); Connection other = pool.getConnection()) {
                assertNotSame(held, fresh);
                int number = 0;
                for (PasskeyAlgorithm algorithm : PasskeyAlgorithm.values()) {
                    for (PasskeyState state : PasskeyState.values()) {
                        number++;
                        Connection writer = number % 2 == 0 ? fresh : other;
                        try (Statement statement = writer.createStatement()) {
                            statement.executeUpdate("INSERT INTO sennard.passkey_credentials (credential_id, public_key, algorithm, label, state, registered_at, revoked_at) VALUES ('raw" + number
                                    + "', 'k', '" + algorithm.name() + "', 'l', '" + state.name() + "', TIMESTAMP '2026-11-13 01:02:03', " + (state == PasskeyState.REVOKED ? "TIMESTAMP '2026-11-13 01:02:04'" : "NULL") + ")");
                        }
                    }
                }
            }
            PasskeyRegistry registry = context.getBean(PasskeyRegistry.class);
            assertEquals(PasskeyAlgorithm.values().length * PasskeyState.values().length, registry.list().size());
            // A new connection of the same pool: the pool's connections are retired (soft eviction, Hikari's own way to have a new one opened) and the next one borrowed is a
            // connection that did not exist when the rows were written; the file the open pool holds takes a row update from it as well.
            pool.unwrap(com.zaxxer.hikari.HikariDataSource.class).getHikariPoolMXBean().softEvictConnections();
            try (Connection direct = pool.getConnection(); Statement statement = direct.createStatement()) {
                assertEquals(1, statement.executeUpdate("UPDATE sennard.passkey_credentials SET label = 'again' WHERE credential_id = 'raw1'"));
            }
            assertEquals("again", registry.find("raw1").orElseThrow().registration().label());
        });
    }

    // ------------------------------------------------------------------------------------------------------------ 8. the rules of the three stores

    @Test
    void thePasskeyRegistryRegistersFindsListsAndRevokesOnce(@TempDir Path folder) {
        SettingsFixtures.runner(folder).run(context -> {
            PasskeyRegistry registry = context.getBean(PasskeyRegistry.class);
            assertTrue(registry.find("none").isEmpty());
            PasskeyCredential first = registry.register(SettingsFixtures.passkey("one"));
            assertEquals(PasskeyState.ACTIVE, first.state());
            assertNull(first.revokedAt());
            assertEquals(SettingsFixtures.passkey("one"), first.registration());
            // Registering the same key again is the same registration (the idempotence of the use case): the held key is answered.
            assertEquals(first, registry.register(SettingsFixtures.passkey("one")));
            PasskeyRegistration otherBytes = new PasskeyRegistration("one", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE-other", PasskeyAlgorithm.ES256, "another key");
            assertEquals(SettingsCode.PASSKEY_EXISTS, assertThrows(SettingsException.class, () -> registry.register(otherBytes)).code());
            PasskeyRegistration otherAlgorithm = new PasskeyRegistration("one", SettingsFixtures.passkey("one").publicKey(), PasskeyAlgorithm.RS256, "another algorithm");
            assertEquals(SettingsCode.PASSKEY_EXISTS, assertThrows(SettingsException.class, () -> registry.register(otherAlgorithm)).code());
            PasskeyCredential revoked = registry.revoke("one");
            assertEquals(PasskeyState.REVOKED, revoked.state());
            assertEquals(revoked, registry.revoke("one"), "a key that is revoked already is answered as revoked, with the time of the first revocation");
            assertEquals(revoked, registry.register(SettingsFixtures.passkey("one")), "a revoked id is not registered again: the same key is answered with the revoked one");
            assertEquals(SettingsCode.PASSKEY_NOT_FOUND, assertThrows(SettingsException.class, () -> registry.revoke("two")).code());
            assertEquals(SettingsCode.INVALID_SETTING, assertThrows(SettingsException.class, () -> SettingsFixtures.passkey("bad id!")).code());
            assertEquals(List.of(revoked), registry.list());
        });
    }

    /** AGS3-D-0254: the admin reset empties the registry, revoked keys included, and the same ids register again. */
    @Test
    void clearDeletesEveryKeyRevokedOnesIncludedAndTheirIdsRegisterAgain(@TempDir Path folder) {
        SettingsFixtures.runner(folder).run(context -> {
            PasskeyRegistry registry = context.getBean(PasskeyRegistry.class);
            registry.register(SettingsFixtures.passkey("one"));
            registry.register(SettingsFixtures.passkey("two"));
            registry.revoke("one");
            assertEquals(2, registry.list().size());
            registry.clear();
            assertTrue(registry.list().isEmpty());
            assertTrue(registry.find("one").isEmpty());
            registry.clear();
            assertEquals(PasskeyState.ACTIVE, registry.register(SettingsFixtures.passkey("one")).state(), "an id of a cleared key registers again");
        });
    }

    /** AGS3-D-0254: the section before the link is open until the business database is ready once; closed, it takes no record, and the reset moves the records to the archive. */
    @Test
    void thePreLinkSectionClosesOnceTakesNoMoreRecordsAndTheResetArchivesWhatItHolds(@TempDir Path folder) {
        SettingsFixtures.runner(folder).run(context -> {
            PreLinkAdminRecords records = context.getBean(PreLinkAdminRecords.class);
            assertFalse(records.closed(), "a new installation's section is open");
            PreLinkAdminRecord a = records.append(SettingsFixtures.record("keys", "req-a"));
            PreLinkAdminRecord b = records.append(SettingsFixtures.record("database", null));

            records.close();
            records.close();
            assertTrue(records.closed());
            assertEquals(List.of(a, b), records.list(), "closing keeps the records where they are");
            assertEquals(SettingsCode.PRE_LINK_CLOSED, assertThrows(SettingsException.class, () -> records.append(SettingsFixtures.record("global", null))).code());
            assertEquals(List.of("1"), column(context.getBean("settingsDataSource", DataSource.class), "SELECT CAST(count(*) AS VARCHAR(10)) FROM sennard.pre_link_section WHERE closed = TRUE"),
                    "closing twice closed the one state row");

            records.archive();
            assertTrue(records.list().isEmpty(), "the reset empties the section");
            assertTrue(records.closed(), "and leaves it closed");
            assertEquals(List.of("2"), column(context.getBean("settingsDataSource", DataSource.class), "SELECT CAST(count(*) AS VARCHAR(10)) FROM sennard.pre_link_admin_records_archive"));
            records.archive();
            assertEquals(List.of("2"), column(context.getBean("settingsDataSource", DataSource.class), "SELECT CAST(count(*) AS VARCHAR(10)) FROM sennard.pre_link_admin_records_archive"),
                    "archiving again archives nothing again");
        });
    }

    /** AGS3-D-0254 (13): the link settings keep one row, the driver and the password file as paths, and no password: a URL that carries one is refused and no column holds one. */
    @Test
    void theLinkSettingsKeepOneRowAndNoPassword(@TempDir Path folder) {
        SettingsFixtures.runner(folder).run(context -> {
            DatabaseLinkSettings links = context.getBean(DatabaseLinkSettings.class);
            Path home = folder.toAbsolutePath();
            assertTrue(links.find().isEmpty());
            DatabaseLinkRequest first = new DatabaseLinkRequest(home.resolve("driver.jar"), "jdbc:postgresql://127.0.0.1:5432/sennard", "sennard_owner", home.resolve("owner.pw"), DatabaseTls.LOOPBACK_PLAINTEXT);
            assertEquals(first, links.save(first).request());
            DatabaseLinkRequest second = new DatabaseLinkRequest(home.resolve("drivers"), "jdbc:mariadb://db.example:3306/sennard?sslMode=verify-full", "owner2", home.resolve("owner2.pw"),
                    DatabaseTls.VERIFY_FULL);
            DatabaseLink saved = links.save(second);
            assertEquals(second, saved.request());
            assertEquals(second, links.find().orElseThrow().request(), "the second save replaced the first");
            assertEquals(List.of("1"), column(context.getBean("settingsDataSource", DataSource.class), "SELECT CAST(count(*) AS VARCHAR(10)) FROM sennard.database_link"));

            for (String carrying : new String[] {"jdbc:postgresql://h/db?password=secret", "jdbc:postgresql://h/db?user=u&pwd=secret", "jdbc:mariadb://u:secret@h/db", "jdbc:postgresql://h/db;passwd=secret",
                    "jdbc:db2://h:50000/db:password=secret;", "jdbc:oracle:thin:scott/tiger@//h:1521/svc", "jdbc:sqlserver://h:1433;databaseName=db;user=u;password=secret"}) {
                assertEquals(SettingsCode.INVALID_SETTING, assertThrows(SettingsException.class,
                        () -> new DatabaseLinkRequest(home.resolve("driver.jar"), carrying, "u", home.resolve("pw"), DatabaseTls.LOOPBACK_PLAINTEXT)).code(), carrying);
            }
            assertEquals(SettingsCode.INVALID_SETTING, assertThrows(SettingsException.class,
                    () -> new DatabaseLinkRequest(Path.of("relative.jar"), "jdbc:postgresql://h/db", "u", home.resolve("pw"), DatabaseTls.LOOPBACK_PLAINTEXT)).code(), "a relative path means another place for each folder");
            assertEquals(SettingsCode.INVALID_SETTING, assertThrows(SettingsException.class,
                    () -> new DatabaseLinkRequest(home.resolve("driver.jar"), "postgresql://h/db", "u", home.resolve("pw"), DatabaseTls.LOOPBACK_PLAINTEXT)).code());
            assertEquals(List.of("LINK_ID", "DRIVER_PATH", "JDBC_URL", "DB_USER", "PASSWORD_REF", "TLS_POLICY", "SAVED_AT"), column(context.getBean("settingsDataSource", DataSource.class),
                    "SELECT column_name FROM information_schema.columns WHERE table_schema = 'SENNARD' AND table_name = 'DATABASE_LINK' ORDER BY ordinal_position"),
                    "the only column about the password is the file that holds it");
        });
    }

    @Test
    void thePreLinkRecordsKeepOrderAndOneRecordPerRequestId(@TempDir Path folder) {
        SettingsFixtures.runner(folder).run(context -> {
            PreLinkAdminRecords records = context.getBean(PreLinkAdminRecords.class);
            PreLinkAdminRecord a = records.append(SettingsFixtures.record("keys", "req-a"));
            PreLinkAdminRecord b = records.append(SettingsFixtures.record("global", null));
            PreLinkAdminRecord c = records.append(SettingsFixtures.record("roles", null));
            assertTrue(a.seq() < b.seq() && b.seq() < c.seq());
            assertEquals(List.of(a, b, c), records.list());
            assertEquals(a, records.findByRequest("req-a").orElseThrow());
            assertTrue(records.findByRequest("req-none").isEmpty());
            PreLinkAdminDraft again = SettingsFixtures.record("keys", "req-a");
            assertEquals(SettingsCode.REQUEST_ALREADY_RECORDED, assertThrows(SettingsException.class, () -> records.append(again)).code());
            assertEquals(3, records.list().size(), "the refused record was not kept");
            assertEquals(c.seq() + 1, records.append(SettingsFixtures.record("jev", null)).seq(), "nor was the sequence number it took");
            assertEquals(SettingsFixtures.record("keys", "req-a"), a.draft());
        });
    }

    // ------------------------------------------------------------------------------------------------------------ 9. the migrator

    @Test
    void theMigratorRefusesAChangedAMissingAndAnOutOfOrderScriptAndAppliesNothingOnARerun(@TempDir Path folder) {
        SettingsFixtures.runner(folder).run(context -> {
            DataSource source = context.getBean("settingsDataSource", DataSource.class);
            SettingsMigrator migrator = context.getBean(SettingsMigrator.class);
            List<MigrationScript> jar = MigrationScript.load(SettingsMigrator.LOCATION);
            assertEquals(2, jar.size());
            assertEquals(0, migrator.migrate(source), "a rerun applies nothing");
            MigrationScript v1 = jar.get(0);
            MigrationScript v2 = jar.get(1);
            byte[] edited = (new String(v1.bytes(), java.nio.charset.StandardCharsets.UTF_8) + "\n-- edited").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            MigrationScript changed = new MigrationScript(1, v1.file(), edited, io.github.jaeseongs95.sennard.domain.Digests.sha256Hex(edited));
            assertEquals(SettingsCode.MIGRATION_CHANGED, assertThrows(SettingsException.class, () -> migrator.migrate(source, List.of(changed, v2))).code());
            assertEquals(List.of("1/1 SUCCEEDED", "2/1 SUCCEEDED"), column(source, ATTEMPTS), "a changed file is no attempt: the history has no row more");
            assertEquals(SettingsCode.MIGRATION_MISSING, assertThrows(SettingsException.class, () -> migrator.migrate(source, List.of())).code());
            // The numbers are one after the other from 1: a number left out, or a script numbered 0, stops the run.
            MigrationScript v0 = script(0, "V0__late.sql", "SELECT 1");
            assertEquals(SettingsCode.MIGRATION_GAP, assertThrows(SettingsException.class, () -> migrator.migrate(source, List.of(v0, v1, v2))).code());
            MigrationScript v4 = script(4, "V4__skipped.sql", "CREATE TABLE sennard.skipped (id INTEGER NOT NULL, PRIMARY KEY (id))");
            assertEquals(SettingsCode.MIGRATION_GAP, assertThrows(SettingsException.class, () -> migrator.migrate(source, List.of(v1, v2, v4))).code());
            MigrationScript v3 = script(3, "V3__later.sql", "CREATE TABLE sennard.later (id INTEGER NOT NULL, PRIMARY KEY (id))");
            assertEquals(1, migrator.migrate(source, List.of(v1, v2, v3)), "a new script above the highest applied one is applied");
            assertEquals(List.of("1/1 SUCCEEDED", "2/1 SUCCEEDED", "3/1 SUCCEEDED"), column(source, ATTEMPTS));
            // A history changed by hand: the number 1 is gone from it while the number 3 is applied, so the script of the number 1 lies below the highest applied number.
            new JdbcTemplate(source).update("DELETE FROM sennard.history WHERE version = 1");
            assertEquals(SettingsCode.MIGRATION_OUT_OF_ORDER, assertThrows(SettingsException.class, () -> migrator.migrate(source, List.of(v1, v2, v3))).code());
            assertEquals(List.of("2/1 SUCCEEDED", "3/1 SUCCEEDED"), column(source, ATTEMPTS), "the refused run added no row");
        });
    }

    /** A script that fails leaves its attempt as FAILED (H2 keeps what its DDL did before the failure); the next run is the next attempt, which applies the fixed file. */
    @Test
    void aFailedSettingsScriptStaysInTheHistoryAndTheNextRunMakesTheNextAttempt(@TempDir Path folder) {
        SettingsFixtures.runner(folder).run(context -> {
            DataSource source = context.getBean("settingsDataSource", DataSource.class);
            SettingsMigrator migrator = context.getBean(SettingsMigrator.class);
            List<MigrationScript> jar = MigrationScript.load(SettingsMigrator.LOCATION);
            MigrationScript v1 = jar.get(0);
            MigrationScript v2 = jar.get(1);
            MigrationScript broken = script(3, "V3__later.sql", "INSERT INTO sennard.no_such_table (id) VALUES (1)");
            MigrationScript fixed = script(3, "V3__later.sql", "CREATE TABLE sennard.later (id INTEGER NOT NULL, PRIMARY KEY (id))");
            assertEquals(SettingsCode.MIGRATION_FAILED, assertThrows(SettingsException.class, () -> migrator.migrate(source, List.of(v1, v2, broken))).code());
            assertEquals(List.of("1/1 SUCCEEDED", "2/1 SUCCEEDED", "3/1 FAILED"), column(source, ATTEMPTS));
            assertEquals(1, migrator.migrate(source, List.of(v1, v2, fixed)), "the numbers 1 and 2 are applied already");
            assertEquals(List.of("1/1 SUCCEEDED", "2/1 SUCCEEDED", "3/1 FAILED", "3/2 SUCCEEDED"), column(source, ATTEMPTS), "the failed attempt's row stays");
            assertEquals(0, migrator.migrate(source, List.of(v1, v2, fixed)), "a rerun applies nothing and adds no row");
            assertEquals(4, column(source, ATTEMPTS).size());
        });
    }

    /** An outcome that is none of the two means the history cannot be read: the run stops with the failure code and applies no script, so the pending script's table is not made and no row is added. */
    @Test
    void anUnknownOutcomeInTheHistoryStopsTheRunAndAppliesNothing(@TempDir Path folder) {
        SettingsFixtures.runner(folder).run(context -> {
            DataSource source = context.getBean("settingsDataSource", DataSource.class);
            SettingsMigrator migrator = context.getBean(SettingsMigrator.class);
            List<MigrationScript> jar = MigrationScript.load(SettingsMigrator.LOCATION);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO sennard.history (version, attempt, script, digest, applied_at, outcome) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?)",
                    3, 1, "V3__later.sql", "0".repeat(64), "BOGUS");
            List<String> before = column(source, ATTEMPTS);
            assertEquals(List.of("1/1 SUCCEEDED", "2/1 SUCCEEDED", "3/1 BOGUS"), before);
            MigrationScript pending = script(3, "V3__later.sql", "CREATE TABLE sennard.later (id INTEGER NOT NULL, PRIMARY KEY (id))");
            SettingsException stopped = assertThrows(SettingsException.class, () -> migrator.migrate(source, List.of(jar.get(0), jar.get(1), pending)));
            assertEquals(SettingsCode.MIGRATION_FAILED, stopped.code());
            assertTrue(stopped.getMessage().contains("BOGUS") && stopped.getMessage().contains("version 3") && stopped.getMessage().contains("attempt 1"), stopped.getMessage());
            assertEquals(before, column(source, ATTEMPTS), "no row was added");
            assertEquals(0, column(source, "SELECT table_name FROM information_schema.tables WHERE LOWER(table_schema) = 'sennard' AND LOWER(table_name) = 'later'").size(),
                    "the pending script was not applied");
        });
    }

    /** {@code ${long_text}} on H2 is the dialect's declaration: a text of hundreds of thousands of characters, Korean among them, is stored and read back whole. */
    @Test
    void theLongTextDeclarationOfTheDialectHoldsAKoreanTextOfHundredsOfThousandsOfCharacters(@TempDir Path folder) {
        SettingsFixtures.runner(folder).run(context -> {
            DataSource source = context.getBean("settingsDataSource", DataSource.class);
            SettingsMigrator migrator = context.getBean(SettingsMigrator.class);
            List<MigrationScript> jar = MigrationScript.load(SettingsMigrator.LOCATION);
            MigrationScript table = script(3, "V3__long_text.sql", "CREATE TABLE sennard.long_texts (id INTEGER NOT NULL, body ${long_text} NOT NULL, PRIMARY KEY (id))");
            assertEquals(1, migrator.migrate(source, List.of(jar.get(0), jar.get(1), table)));
            String mixed = "한글 mixed 가나다 text. ".repeat(20_000); // 380,000 characters
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO sennard.long_texts (id, body) VALUES (?, ?)", 1, mixed);
            assertEquals(mixed, jdbc.queryForObject("SELECT body FROM sennard.long_texts WHERE id = 1", String.class));
        });
    }

    /** The history of the settings store: {@code version/attempt outcome} of every row, in the order of the number and the attempt. */
    private static final String ATTEMPTS = "SELECT CAST(version AS VARCHAR(10)) || '/' || CAST(attempt AS VARCHAR(10)) || ' ' || outcome FROM sennard.history ORDER BY version, attempt";

    private static MigrationScript script(long version, String file, String sql) {
        byte[] bytes = sql.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new MigrationScript(version, file, bytes, io.github.jaeseongs95.sennard.domain.Digests.sha256Hex(bytes));
    }

    // ------------------------------------------------------------------------------------------------------------ 11. the pool's values are settings

    @Test
    void thePoolIsSizedByTheSettingsProperties(@TempDir Path folder) {
        SettingsFixtures.runner(folder.resolve("default")).run(context -> {
            com.zaxxer.hikari.HikariDataSource pool = context.getBean("settingsDataSource", DataSource.class).unwrap(com.zaxxer.hikari.HikariDataSource.class);
            assertEquals(4, pool.getMaximumPoolSize());
            assertEquals(1, pool.getMinimumIdle());
            assertEquals(5_000, pool.getConnectionTimeout());
            assertEquals("sennard-settings", pool.getPoolName());
        });
        SettingsFixtures.runner(folder.resolve("set")).withPropertyValues("sennard.settings.pool.maximum-size=2", "sennard.settings.pool.connection-timeout=3s", "sennard.settings.pool.name=own")
                .run(context -> {
                    com.zaxxer.hikari.HikariDataSource pool = context.getBean("settingsDataSource", DataSource.class).unwrap(com.zaxxer.hikari.HikariDataSource.class);
                    assertEquals(2, pool.getMaximumPoolSize());
                    assertEquals(3_000, pool.getConnectionTimeout());
                    assertEquals("own", pool.getPoolName());
                });
        SettingsFixtures.runner(folder.resolve("bad")).withPropertyValues("sennard.settings.pool.maximum-size=0").run(context -> assertTrue(context.getStartupFailure() != null, "a pool of no connections is refused at start"));
    }

    // ------------------------------------------------------------------------------------------------------------ 11. a ';' in the folder (D-0214)

    /**
     * H2's URL cannot carry a ';' in the database name, and the settings store does not move the database to another folder to hide that: a state folder with a ';' in its path is
     * refused at start with INVALID_DATABASE_CONFIG, nothing is made (neither that folder nor a substitute), and the same runner on a folder without one opens.
     */
    @Test
    void aSemicolonInTheSettingsFolderIsRefusedAndNothingIsMade(@TempDir Path folder) {
        Path withSemicolon = folder.resolve("a;b");
        SettingsFixtures.runner(withSemicolon).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertTrue(failure != null, "a state folder with a ';' is refused at start");
            while (failure.getCause() != null) {
                failure = failure.getCause();
            }
            StoreException refused = assertInstanceOf(StoreException.class, failure);
            assertEquals(StoreCode.INVALID_DATABASE_CONFIG, refused.code());
        });
        assertFalse(Files.exists(withSemicolon), "the folder with the ';' is not made");
        try (java.util.stream.Stream<Path> files = Files.walk(folder)) {
            assertEquals(List.of(), files.filter(file -> file.getFileName().toString().startsWith("sennard-settings")).toList(), "no database file is made anywhere under the temporary folder");
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
        SettingsFixtures.runner(folder.resolve("a-b")).run(context -> assertNull(context.getStartupFailure()));
    }

    /** Other unusual characters were never a problem. */
    @Test
    void otherUnusualNamesOfTheSettingsFolderOpenInPlace(@TempDir Path folder) {
        SettingsFixtures.runner(folder.resolve("a b 한글")).run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBean(PasskeyRegistry.class).list().isEmpty());
        });
        assertTrue(Files.isDirectory(folder.resolve("a b 한글")), "the folder is the state folder itself, not a substitute");
    }

    // ------------------------------------------------------------------------------------------------------------ 10. long Korean text

    @Test
    void aLongKoreanIntentSurvivesARoundTrip(@TempDir Path folder) {
        SettingsFixtures.runner(folder).run(context -> {
            String intent = "{\"body\":\"" + "한글 승인 기록 ".repeat(20_000) + "\"}";
            PreLinkAdminRecords records = context.getBean(PreLinkAdminRecords.class);
            PreLinkAdminDraft draft = new PreLinkAdminDraft("global", intent, "cred-1", "YXV0aA", "e30", "c2ln", "hash", null);
            PreLinkAdminRecord stored = records.append(draft);
            assertEquals(intent, records.list().getFirst().draft().intent());
            assertEquals(stored, records.list().getFirst());
        });
    }

    // ------------------------------------------------------------------------------------------------------------ helpers

    private static void execute(DataSource source, String sql) throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static List<String> column(DataSource source, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}
