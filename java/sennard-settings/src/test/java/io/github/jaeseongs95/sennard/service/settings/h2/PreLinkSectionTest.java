// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings.h2;

import static io.github.jaeseongs95.sennard.service.settings.h2.HeldWriter.LARGER_POOL;
import static io.github.jaeseongs95.sennard.service.settings.h2.HeldWriter.column;
import static io.github.jaeseongs95.sennard.service.settings.h2.HeldWriter.count;
import static io.github.jaeseongs95.sennard.service.settings.h2.HeldWriter.execute;
import static io.github.jaeseongs95.sennard.service.settings.h2.HeldWriter.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminRecord;
import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminRecords;
import io.github.jaeseongs95.sennard.service.settings.SettingsCode;
import io.github.jaeseongs95.sennard.service.settings.SettingsException;

/**
 * AGS3-D-0254 (audit 5): closing the section of the admin log before the link and appending a record to it are both conditional UPDATEs of the section's one state row, so the
 * database decides which comes first and no record enters a closed section. The callers meet behind a writer the test holds ({@link HeldWriter}), the way the earlier code's
 * check-then-insert would have let a record in after the close.
 *
 * <pre>
 * r3   close first    an append waits for a close that is in flight: it is refused (PRE_LINK_CLOSED), took no sequence number, and the closed section holds no record
 * r4   append first   a close waits for an append that is in flight: the record is kept, before the close, and the closed section takes no more
 * r5   the script     the script that made the state row folds the earlier table: the last sequence number of the records and the archive, and the closing
 * r5b  an open one    an open section stays open, and the next record goes on counting
 * </pre>
 */
class PreLinkSectionTest {
    @Test
    void r3AnAppendThatWaitsForACloseInFlightIsRefusedAndTheClosedSectionHoldsNoRecord(@TempDir Path folder) {
        SettingsFixtures.runner(folder).withPropertyValues(LARGER_POOL).run(context -> {
            DataSource source = context.getBean("settingsDataSource", DataSource.class);
            PreLinkAdminRecords records = context.getBean(PreLinkAdminRecords.class);
            try (HeldWriter writer = HeldWriter.holding(source, "UPDATE sennard.pre_link_section SET closed = TRUE, closed_time = CURRENT_TIMESTAMP WHERE section_id = 1 AND closed = FALSE");
                    ExecutorService callers = Executors.newFixedThreadPool(1)) {
                Future<PreLinkAdminRecord> append = callers.submit(() -> records.append(SettingsFixtures.record("keys", "req-late")));
                writer.awaitBlocked(source, 1, "the append was not waiting for the close that is in flight", append);
                writer.release();
                ExecutionException refused = assertThrows(ExecutionException.class, () -> within(append));
                assertEquals(SettingsCode.PRE_LINK_CLOSED, assertInstanceOf(SettingsException.class, refused.getCause()).code());
            }
            assertTrue(records.closed());
            assertTrue(records.list().isEmpty(), "no record entered the closed section");
            assertEquals(0, count(source, "SELECT last_seq FROM sennard.pre_link_section"), "the refused append took no sequence number");
        });
    }

    @Test
    void r4ACloseThatWaitsForAnAppendInFlightLeavesTheRecordBeforeTheCloseAndTheClosedSectionTakesNoMore(@TempDir Path folder) {
        SettingsFixtures.runner(folder).withPropertyValues(LARGER_POOL).run(context -> {
            DataSource source = context.getBean("settingsDataSource", DataSource.class);
            PreLinkAdminRecords records = context.getBean(PreLinkAdminRecords.class);
            try (HeldWriter writer = HeldWriter.holding(source, "UPDATE sennard.pre_link_section SET last_seq = last_seq + 1 WHERE section_id = 1 AND closed = FALSE",
                    "INSERT INTO sennard.pre_link_admin_records (seq, target, intent, credential_id, authenticator_data, client_data_json, signature, record_hash, request_id, created_at) "
                            + "SELECT last_seq, 'keys', 'intent', 'cred-1', 'YXV0aA', 'e30', 'c2ln', 'hash-held', 'req-held', CURRENT_TIMESTAMP FROM sennard.pre_link_section WHERE section_id = 1");
                    ExecutorService callers = Executors.newFixedThreadPool(1)) {
                Future<?> close = callers.submit(records::close);
                writer.awaitBlocked(source, 1, "the close was not waiting for the append that is in flight", close);
                writer.release();
                within(close);
            }
            assertTrue(records.closed());
            assertEquals(List.of("req-held"), records.list().stream().map(held -> held.draft().requestId()).toList(), "the record that was ahead of the close is kept");
            assertEquals(SettingsCode.PRE_LINK_CLOSED, assertThrows(SettingsException.class, () -> records.append(SettingsFixtures.record("global", null))).code());
            assertEquals(1, records.list().size(), "the closed section took no more");
        });
    }

    @Test
    void r5TheScriptOfTheSectionStateFoldsTheStateOfTheEarlierTable(@TempDir Path folder) {
        SettingsFixtures.runner(folder).run(context -> {
            DataSource source = context.getBean("settingsDataSource", DataSource.class);
            SettingsMigrator migrator = context.getBean(SettingsMigrator.class);
            // The database as the first script left it: the earlier table that held only the closing, records of the section (the numbers 5 and 7) and one in the archive (the number 9).
            for (String sql : new String[] {"DROP TABLE sennard.pre_link_section",
                    "CREATE TABLE sennard.pre_link_closed (closed_id INTEGER NOT NULL, closed_at TIMESTAMP NOT NULL, PRIMARY KEY (closed_id))",
                    "INSERT INTO sennard.pre_link_closed (closed_id, closed_at) VALUES (1, TIMESTAMP '2026-11-01 00:00:00')",
                    insertRecord("pre_link_admin_records", 5, ""), insertRecord("pre_link_admin_records", 7, ""),
                    insertRecord("pre_link_admin_records_archive", 9, ", TIMESTAMP '2026-11-02 00:00:00'"), "DELETE FROM sennard.history WHERE version = 2"}) {
                execute(source, sql);
            }
            assertEquals(1, migrator.migrate(source), "the second script is applied again");
            assertEquals(List.of("9"), column(source, "SELECT CAST(last_seq AS VARCHAR(10)) FROM sennard.pre_link_section"), "the last number of the records and of the archive");
            assertEquals(List.of("TRUE"), column(source, "SELECT CAST(closed AS VARCHAR(10)) FROM sennard.pre_link_section"), "the closing is kept");
            assertEquals(0, count(source, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'SENNARD' AND TABLE_NAME = 'PRE_LINK_CLOSED'"), "the earlier table is gone");
            PreLinkAdminRecords records = context.getBean(PreLinkAdminRecords.class);
            assertTrue(records.closed());
            assertEquals(SettingsCode.PRE_LINK_CLOSED, assertThrows(SettingsException.class, () -> records.append(SettingsFixtures.record("global", null))).code());
        });
    }

    @Test
    void r5bAnOpenSectionStaysOpenAndTheNextRecordGoesOnCounting(@TempDir Path folder) {
        SettingsFixtures.runner(folder).run(context -> {
            DataSource source = context.getBean("settingsDataSource", DataSource.class);
            SettingsMigrator migrator = context.getBean(SettingsMigrator.class);
            for (String sql : new String[] {"DROP TABLE sennard.pre_link_section",
                    "CREATE TABLE sennard.pre_link_closed (closed_id INTEGER NOT NULL, closed_at TIMESTAMP NOT NULL, PRIMARY KEY (closed_id))",
                    insertRecord("pre_link_admin_records", 3, ""), "DELETE FROM sennard.history WHERE version = 2"}) {
                execute(source, sql);
            }
            assertEquals(1, migrator.migrate(source));
            PreLinkAdminRecords records = context.getBean(PreLinkAdminRecords.class);
            assertFalse(records.closed());
            assertEquals(4, records.append(SettingsFixtures.record("global", null)).seq());
        });
    }

    private static String insertRecord(String table, long seq, String archivedAt) {
        String columns = archivedAt.isEmpty() ? "" : ", archived_at";
        return "INSERT INTO sennard." + table + " (seq, target, intent, credential_id, authenticator_data, client_data_json, signature, record_hash, request_id, created_at" + columns + ") "
                + "VALUES (" + seq + ", 'keys', 'intent', 'cred-1', 'YXV0aA', 'e30', 'c2ln', 'hash-" + seq + "', NULL, TIMESTAMP '2026-10-31 00:00:00'" + archivedAt + ")";
    }
}
