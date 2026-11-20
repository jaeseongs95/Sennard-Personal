// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.journal;

import java.nio.file.Path;
import java.sql.Types;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.support.UtcTimestampColumns;
import io.github.jaeseongs95.sennard.domain.Digests;

/**
 * The rows of a captured baseline: one {@code baseline_file} row and one {@code observed_file} row for each file the first scan found. A workspace has tens of thousands of files, so
 * the rows go to the database in batches of {@link #BATCH} (one round trip for a batch, standard JDBC through Boot's {@code NamedParameterJdbcTemplate}), not one statement for each
 * row as the repositories' {@code save} would: what the transaction that writes them spends is the round trips, and they are the files divided by the batch, not the files. It joins the
 * transaction of the use case that calls it ({@link ChangeJournalService#captureBaseline}) and reads no file.
 *
 * <p>The value written for {@code version} is the one Spring Data JDBC writes for a new row of a versioned entity, so a later {@code save} of the same row by the repositories updates it
 * under the version it reads.
 */
@Component
public class BaselineRows {
    /** The rows of one round trip. ceiling: a fixed size; a database that limits a batch below it needs a smaller one. */
    static final int BATCH = 500;

    private static final String BASELINE = "INSERT INTO sennard.baseline_file (baseline_file_id, workflow_id, path, path_hash, size_bytes, modified_at, sha256, version) "
            + "VALUES (:id, :workflowId, :path, :pathHash, :size, :modifiedAt, :sha256, 0)";
    private static final String OBSERVED = "INSERT INTO sennard.observed_file (observed_file_id, workflow_id, path, path_hash, size_bytes, modified_at, sha256, version) "
            + "VALUES (:id, :workflowId, :path, :pathHash, :size, :modifiedAt, :sha256, 0)";

    private final NamedParameterJdbcTemplate jdbc;
    private final UtcTimestampColumns timestamps;

    public BaselineRows(NamedParameterJdbcTemplate jdbc, UtcTimestampColumns timestamps) {
        this.jdbc = jdbc;
        this.timestamps = timestamps;
    }

    /** Writes the baseline and the first observation of {@code files} for {@code workflowId}; each row has an identifier of {@code ids}. */
    void insert(String workflowId, Map<Path, JournalScanner.Metadata> files, Supplier<String> ids) {
        for (String sql : new String[] {BASELINE, OBSERVED}) {
            SqlParameterSource[] rows = files.entrySet().stream().map(file -> row(workflowId, file.getKey(), file.getValue(), ids.get())).toArray(SqlParameterSource[]::new);
            for (int from = 0; from < rows.length; from += BATCH) {
                jdbc.batchUpdate(sql, Arrays.copyOfRange(rows, from, Math.min(from + BATCH, rows.length)));
            }
        }
    }

    private SqlParameterSource row(String workflowId, Path path, JournalScanner.Metadata metadata, String id) {
        return new MapSqlParameterSource().addValue("id", id).addValue("workflowId", workflowId).addValue("path", path.toString()).addValue("pathHash", Digests.sha256Hex(path.toString()))
                .addValue("size", metadata.size()).addValue("modifiedAt", timestamps.column(metadata.modifiedAt())).addValue("sha256", metadata.sha256(), Types.VARCHAR);
    }
}
