// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.service.database.SqlBuilder;

/**
 * Which sessions have prompts in the archive, newest activity first: the list a recall for a role starts from (which of these sessions the user bound to the role is the
 * server's to judge). Standard SQL through Boot's {@code NamedParameterJdbcTemplate}.
 */
@Component
public class ArchivedSessions {
    /** The most sessions listed: the most recently active ones. A session whose prompts are older than all of these has not been in use for long. */
    public static final int MOST = 500;

    private static final String SESSIONS = "SELECT host, session_id FROM sennard.user_prompt GROUP BY host, session_id "
            + "ORDER BY MAX(received_at) DESC, host ASC, session_id ASC ";

    private final NamedParameterJdbcTemplate jdbc;
    private final SqlBuilder limit;

    public ArchivedSessions(NamedParameterJdbcTemplate jdbc, SqlBuilder limit) {
        this.jdbc = jdbc;
        this.limit = limit;
    }

    public List<ArchivedSession> recent() {
        return jdbc.query(limit.applyLimit(SESSIONS, ":most"), new MapSqlParameterSource("most", MOST),
                (row, rowNumber) -> new ArchivedSession(row.getString("host"), row.getString("session_id")));
    }
}
