// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import io.github.jaeseongs95.sennard.app.support.UtcTimestampColumns;

/**
 * The write of a captured baseline spends round trips, and a transaction has a fixed time, so the count of the trips is what must not follow the count of the files. The file I/O is
 * not here at all (the scan is done before the transaction), and the rows are given as the scan returned them; what is asserted is the structure of the write, not how long it takes.
 */
class BaselineRowsTest {
    /** Records the size of every batch that reaches the database; the statements are the real ones. */
    private static final class CountingTemplate extends NamedParameterJdbcTemplate {
        final List<Integer> batches = new ArrayList<>();

        CountingTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public int[] batchUpdate(String sql, SqlParameterSource[] batchArgs) {
            batches.add(batchArgs.length);
            return super.batchUpdate(sql, batchArgs);
        }
    }

    private static EmbeddedDatabase journalDatabase() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName(UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE").build();
        JdbcTemplate sql = new JdbcTemplate(database);
        sql.execute("CREATE SCHEMA sennard");
        sql.execute("CREATE TABLE sennard.baseline_file (baseline_file_id VARCHAR PRIMARY KEY, workflow_id VARCHAR, path VARCHAR, path_hash VARCHAR, size_bytes BIGINT, modified_at TIMESTAMP, sha256 VARCHAR, version BIGINT)");
        sql.execute("CREATE TABLE sennard.observed_file (observed_file_id VARCHAR PRIMARY KEY, workflow_id VARCHAR, path VARCHAR, path_hash VARCHAR, size_bytes BIGINT, modified_at TIMESTAMP, sha256 VARCHAR, version BIGINT)");
        return database;
    }

    private static Map<Path, JournalScanner.Metadata> files(int count) {
        Map<Path, JournalScanner.Metadata> files = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            files.put(Path.of("/workspace/file-" + i), new JournalScanner.Metadata(i, Instant.parse("2026-01-01T00:00:00Z"), i % 2 == 0 ? null : "a".repeat(64)));
        }
        return files;
    }

    private static List<Integer> write(EmbeddedDatabase database, int count) {
        CountingTemplate counting = new CountingTemplate(database);
        AtomicInteger ids = new AtomicInteger();
        new BaselineRows(counting, new UtcTimestampColumns()).insert("wf", files(count), () -> "id-" + ids.incrementAndGet());
        return counting.batches;
    }

    @Test
    void theRoundTripsOfABaselineAreTheFilesOverTheBatchNotOneForEachFile() {
        EmbeddedDatabase database = journalDatabase();
        try {
            int count = BaselineRows.BATCH * 3 + 7;
            List<Integer> batches = write(database, count);
            JdbcTemplate sql = new JdbcTemplate(database);
            for (String table : List.of("baseline_file", "observed_file")) {
                assertEquals(count, sql.queryForObject("SELECT COUNT(*) FROM sennard." + table + " WHERE workflow_id = 'wf'", Integer.class), table);
                assertEquals((count + 1) / 2, sql.queryForObject("SELECT COUNT(*) FROM sennard." + table + " WHERE sha256 IS NULL", Integer.class), table + ": a file that was not hashed has no hash");
                assertEquals(count, sql.queryForObject("SELECT COUNT(DISTINCT path_hash) FROM sennard." + table, Integer.class), table);
            }
            assertEquals(8, batches.size(), "four batches for each of the two tables, not " + 2 * count + " statements");
            assertTrue(batches.stream().allMatch(size -> size <= BaselineRows.BATCH), batches.toString());
            assertEquals(2 * count, batches.stream().mapToInt(Integer::intValue).sum());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void aBaselineOfOneBatchOrLessIsOneRoundTripForEachTableAndAnEmptyOneWritesNothing() {
        EmbeddedDatabase database = journalDatabase();
        try {
            assertEquals(List.of(3, 3), write(database, 3));
            assertEquals(List.of(), write(database, 0), "no file, no statement");
        } finally {
            database.shutdown();
        }
    }
}
