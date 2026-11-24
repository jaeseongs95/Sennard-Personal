// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.execution;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.support.UtcTimestampColumns;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder;

/**
 * What the retention of the log files asks of the executions, and none of it reads a whole table: a page of the ended executions whose log files are still there (oldest first), the
 * sum of what those files take (from the bytes noted when each ended), and which of some ids are such executions. Standard SQL through Boot's {@code NamedParameterJdbcTemplate}, with
 * the row limit the business database's own dialect says ({@link SqlBuilder#limit}). It reads and joins the transaction of the service method that calls it
 * ({@link ExecutionRecords}).
 *
 * <p>An execution counts here while it has ended, has a log folder, and that folder is neither deleted ({@code log_removed_at}) nor out of this server's reach
 * ({@code log_unreachable_at}): the column {@code log_pending} is 1 exactly then (the database checks it), so the index {@code (host_id, log_pending, aged_at, id)} holds only the
 * executions that count and in the order they are read, and a record whose files were deleted or are out of reach is not read again. Its age, {@code aged_at}, is counted from its
 * end, and from its start for one with no end time (LOST); it is never null.
 */
@Component
public class ExecutionLogCandidates {
    /** An execution whose log files are to be judged: its id, the folder, the bytes noted when it ended ({@code null} when none), and when its age counts from. */
    public record Candidate(String id, String location, Long bytes, Instant agedAt) {
    }

    /** Where a page ends, for the next one to start after it. */
    public record Position(Instant agedAt, String id) {
        public static Position of(Candidate candidate) {
            return new Position(candidate.agedAt(), candidate.id());
        }
    }

    /** How many ids one statement is given: the products limit the number of parameters of a statement. */
    private static final int ID_CHUNK = 500;
    private static final String AGED_AT = "aged_at";
    private static final String CANDIDATES = "FROM sennard.execution WHERE host_id = :hostId AND log_pending = 1";

    private final NamedParameterJdbcTemplate jdbc;
    private final UtcTimestampColumns timestamps;
    private final SqlBuilder sql;

    public ExecutionLogCandidates(NamedParameterJdbcTemplate jdbc, UtcTimestampColumns timestamps, SqlBuilder sql) {
        this.jdbc = jdbc;
        this.timestamps = timestamps;
        this.sql = sql;
    }

    /** At most {@code limit} candidates of one installation, the one that is oldest first; after {@code after} when it is given. */
    public List<Candidate> page(String hostId, Position after, int limit) {
        MapSqlParameterSource values = new MapSqlParameterSource("hostId", hostId).addValue("limit", limit);
        SqlBuilder.Statement statement = sql.query("SELECT id, log_location, log_bytes, " + AGED_AT + " AS aged_at " + CANDIDATES);
        if (after != null) {
            values.addValue("after", timestamps.column(after.agedAt())).addValue("afterId", after.id());
            // the first condition is the one the index can narrow the range by ((host_id, log_pending, aged_at, id): a range of aged_at); the second is what makes the position exact, and an
            // OR on its own would be read from the start of the index's range
            statement.and(AGED_AT + " >= :after").and("(" + AGED_AT + " > :after OR (" + AGED_AT + " = :after AND id > :afterId))");
        }
        return List.copyOf(jdbc.query(statement.orderBy(AGED_AT + ", id").limit(":limit"), values, (row, number) -> {
            long bytes = row.getLong("log_bytes");
            Long noted = row.wasNull() ? null : bytes; // asked right after the column: wasNull says of the one read last
            return new Candidate(row.getString("id"), row.getString("log_location"), noted, instant(row, "aged_at"));
        }));
    }

    /** What the log files of all the candidates of one installation take, by the bytes noted when each ended; one with none noted counts as nothing. */
    public long bytes(String hostId) {
        Long total = jdbc.queryForObject("SELECT COALESCE(SUM(log_bytes), 0) " + CANDIDATES, new MapSqlParameterSource("hostId", hostId), Long.class);
        return total == null ? 0 : total;
    }

    /** Which of {@code ids} are executions of one installation that have a log folder that is not deleted and not out of reach (running ones too). */
    public Set<String> recorded(String hostId, Collection<String> ids) {
        Set<String> found = new HashSet<>();
        List<String> all = List.copyOf(ids);
        for (int from = 0; from < all.size(); from += ID_CHUNK) {
            MapSqlParameterSource values = new MapSqlParameterSource("hostId", hostId).addValue("ids", all.subList(from, Math.min(all.size(), from + ID_CHUNK)));
            found.addAll(jdbc.queryForList("SELECT id FROM sennard.execution WHERE host_id = :hostId AND log_location IS NOT NULL AND log_removed_at IS NULL "
                    + "AND log_unreachable_at IS NULL AND id IN (:ids)", values, String.class));
        }
        return found;
    }

    private Instant instant(java.sql.ResultSet row, String column) throws SQLException {
        return timestamps.instant(row, column);
    }
}
