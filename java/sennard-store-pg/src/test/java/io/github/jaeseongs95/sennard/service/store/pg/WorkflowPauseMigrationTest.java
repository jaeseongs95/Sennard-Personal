// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.service.migration.MigrationScript;

/** An existing V23 database gains PAUSED without rewriting run receipts or old migration history. */
@BusinessDbTest
class WorkflowPauseMigrationTest {
    @Test
    void v24PreservesOldRunsAndOnlyExpandsTheRunStateConstraint() throws Exception {
        BusinessDb.dropBusinessSchemas();
        var config = BusinessDb.config();
        var runner = new MigrationRunner(Clock.systemUTC(), BusinessDb.dataSources());
        List<MigrationScript> throughV24 = MigrationScript.load("db/expand").stream().filter(script -> script.version() <= 24).toList();
        assertTrue(throughV24.stream().anyMatch(script -> script.version() == 24));
        assertEquals(23, runner.run(config, MigrationRunner.Phase.EXPAND, throughV24.stream().filter(script -> script.version() <= 23).toList()));
        try (Connection connection = BusinessDb.connect(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO sennard.workflow_runs (run_id, revision, state, receipt_json, created_at, updated_at) VALUES ('before-v24', 7, 'RUNNING', '{\"before\":true}', TIMESTAMP '2026-02-06 00:00:00', TIMESTAMP '2026-02-07 00:00:00')");
            assertEquals(1, runner.run(config, MigrationRunner.Phase.EXPAND, throughV24));
            try (var rows = statement.executeQuery("SELECT revision, state, receipt_json, created_at, updated_at FROM sennard.workflow_runs WHERE run_id = 'before-v24'")) {
                assertTrue(rows.next());
                assertEquals(7, rows.getLong(1));
                assertEquals("RUNNING", rows.getString(2));
                assertEquals("{\"before\":true}", rows.getString(3));
                assertEquals(LocalDateTime.of(2026, 2, 6, 0, 0), rows.getTimestamp(4).toLocalDateTime());
                assertEquals(LocalDateTime.of(2026, 2, 7, 0, 0), rows.getTimestamp(5).toLocalDateTime());
            }
            assertEquals(1, statement.executeUpdate("UPDATE sennard.workflow_runs SET state = 'PAUSED' WHERE run_id = 'before-v24'"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE sennard.workflow_runs SET state = 'UNKNOWN' WHERE run_id = 'before-v24'"));
        }
        assertEquals(0, runner.run(config, MigrationRunner.Phase.EXPAND, throughV24), "history checksums still match, including untouched V8");
    }
}
