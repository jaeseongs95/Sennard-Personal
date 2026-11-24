// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.support.UtcTimestampColumns;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder;

/**
 * The uploads the cleaner looks at, a batch at a time: standard SQL through Boot's {@code NamedParameterJdbcTemplate}, with the row limit the business database's own dialect says
 * ({@link SqlBuilder#limit}). It reads and joins the transaction of the service method that calls it ({@link AttachmentService}); what it lists is looked at again by the
 * conditional {@code DELETE} of the cleaner, so a row that changed since is not touched.
 */
@Component
public class UploadCandidates {
    /** An upload to clean up: its id, and the storage key of its file. */
    public record Candidate(String id, String storageKey) {
    }

    private static final String SELECT = "SELECT id, storage_key FROM sennard.upload_staging WHERE ";

    private final NamedParameterJdbcTemplate jdbc;
    private final UtcTimestampColumns timestamps;
    private final SqlBuilder sql;

    public UploadCandidates(NamedParameterJdbcTemplate jdbc, UtcTimestampColumns timestamps, SqlBuilder sql) {
        this.jdbc = jdbc;
        this.timestamps = timestamps;
        this.sql = sql;
    }

    /** At most {@code limit} uploads in progress whose time is over, the oldest first. */
    public List<Candidate> expired(Instant now, int limit) {
        MapSqlParameterSource values = new MapSqlParameterSource("limit", limit).addValue("now", timestamps.column(now));
        String statement = sql.query(SELECT + "state = 'STAGING' AND expires_at <= :now").orderBy("expires_at, id").limit(":limit");
        return List.copyOf(jdbc.query(statement, values, (row, number) -> new Candidate(row.getString("id"), row.getString("storage_key"))));
    }
}
