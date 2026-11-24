// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.service.migration.MigrationScript;

@BusinessDbTest
class BoardTitleMigrationTest {
    @Test
    void expandingAnExistingDatabaseKeepsTheBoardRowAndLeavesItsTitleUnset() throws Exception {
        BusinessDb.dropBusinessSchemas();
        var config = BusinessDb.config();
        var runner = new MigrationRunner(Clock.systemUTC(), BusinessDb.dataSources());
        var expand = MigrationScript.load("db/expand");
        var baseline = expand.stream().filter(script -> script.version() <= 30).toList();
        assertEquals(baseline.size(), runner.run(config, MigrationRunner.Phase.EXPAND, baseline));
        try (var connection = BusinessDb.connect(); var statements = connection.createStatement()) {
            statements.executeUpdate("""
                    INSERT INTO sennard.board_sessions (host, session_id, cwd, summary, summary_at, last_prompt_at, seen_at, role, version)
                    VALUES ('codex', 'before-title', '/work/old', 'Existing summary', TIMESTAMP '2026-01-01 00:01:00',
                            TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:02:00', 'developer', 7)
                    """);
            assertTrue(runner.run(config, MigrationRunner.Phase.EXPAND, expand) > 0, "the title is added by an expand migration after V30");
            try (var columns = statements.executeQuery("""
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_schema = 'sennard' AND table_name = 'board_sessions'
                    AND column_name IN ('user_title', 'user_title_observed_at')
                    ORDER BY column_name
                    """)) {
                assertTrue(columns.next(), "the expand migration adds user_title to the session board");
                assertEquals("YES", columns.getString(1));
                assertTrue(columns.next(), "the expand migration adds user_title_observed_at to the session board");
                assertEquals("YES", columns.getString(1));
            }
            try (var rows = statements.executeQuery("SELECT * FROM sennard.board_sessions WHERE host = 'codex' AND session_id = 'before-title'")) {
                assertTrue(rows.next());
                assertNull(rows.getString("user_title"));
                assertNull(rows.getTimestamp("user_title_observed_at"));
                assertEquals("/work/old", rows.getString("cwd"));
                assertEquals("Existing summary", rows.getString("summary"));
                assertEquals("developer", rows.getString("role"));
                assertEquals(LocalDateTime.of(2026, 1, 1, 0, 1), rows.getTimestamp("summary_at").toLocalDateTime());
                assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), rows.getTimestamp("last_prompt_at").toLocalDateTime());
                assertEquals(LocalDateTime.of(2026, 1, 1, 0, 2), rows.getTimestamp("seen_at").toLocalDateTime());
                assertEquals(7, rows.getLong("version"));
            }
        }
        assertEquals(0, runner.run(config, MigrationRunner.Phase.EXPAND, expand), "rerunning expand also checks the existing migration checksums");
    }
}
