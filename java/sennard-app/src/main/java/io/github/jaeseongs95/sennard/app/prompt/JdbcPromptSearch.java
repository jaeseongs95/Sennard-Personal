// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.time.Instant;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.support.UtcTimestampColumns;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder;
import io.github.jaeseongs95.sennard.domain.prompt.LikePattern;

/**
 * The database's own answer to a search ({@link PromptSearch}): standard SQL on the archive's tables, through Boot's {@code NamedParameterJdbcTemplate}. The sessions are an
 * OR of (host, session) pairs because the set differs per call (one session, or every session of a role), so the statement is assembled from fixed pieces and everything that
 * varies is a bound value, never text. The text matches ignoring case, with
 * {@code LOWER(column) LIKE LOWER(pattern) ESCAPE '\'}; the person's wildcards are escaped ({@link LikePattern}).
 *
 * <p>Case folding is the database's own {@code LOWER}: "Ab" finds "ab", and how far it goes beyond the ASCII letters (an accented capital) is the database's locale. A text in Korean
 * has no case and is found exactly.
 */
@Component
public class JdbcPromptSearch implements PromptSearch {
    private static final String SELECT = "SELECT p.user_prompt_id, p.host, p.session_id, p.received_at, p.prompt_text, p.original_bytes, p.completeness, r.recap_text "
            + "FROM sennard.user_prompt p LEFT JOIN sennard.turn_recap r ON r.user_prompt_id = p.user_prompt_id "
            + "WHERE ";

    private final NamedParameterJdbcTemplate jdbc;
    private final UtcTimestampColumns timestamps;
    private final SqlBuilder limitSql;

    public JdbcPromptSearch(NamedParameterJdbcTemplate jdbc, UtcTimestampColumns timestamps, SqlBuilder limitSql) {
        this.jdbc = jdbc;
        this.timestamps = timestamps;
        this.limitSql = limitSql;
    }

    @Override
    public List<PromptHit> search(List<ArchivedSession> sessions, String text, int limit, Instant from, Instant to) {
        if (limit < 1) {
            throw new IllegalArgumentException("a search answers at least one prompt");
        }
        if (sessions.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource values = new MapSqlParameterSource("limit", limit);
        SqlBuilder.Statement statement = limitSql.query(SELECT)
                .orGroup("(p.host = :host%1$d AND p.session_id = :session%1$d)", sessions.size());
        for (int at = 0; at < sessions.size(); at++) {
            values.addValue("host" + at, sessions.get(at).host()).addValue("session" + at, sessions.get(at).sessionId());
        }
        if (from != null) {
            statement.and("p.received_at >= :from");
            values.addValue("from", timestamps.column(from));
        }
        if (to != null) {
            statement.and("p.received_at < :to");
            values.addValue("to", timestamps.column(to));
        }
        if (text != null && !text.isEmpty()) {
            statement.and("LOWER(p.prompt_text) LIKE LOWER(:pattern) ESCAPE '" + LikePattern.ESCAPE + "'");
            values.addValue("pattern", LikePattern.contains(text));
        }
        statement.orderBy("p.received_at DESC, p.user_prompt_id DESC");
        return List.copyOf(jdbc.query(statement.limit(":limit"), values, this::hit));
    }

    private PromptHit hit(ResultSet row, int rowNumber) throws SQLException {
        return new PromptHit(row.getString("user_prompt_id"), row.getString("host"), row.getString("session_id"),
                timestamps.instant(row, "received_at"), row.getString("prompt_text"), row.getLong("original_bytes"),
                Completeness.valueOf(row.getString("completeness")).isTruncated(), row.getString("recap_text"));
    }
}
