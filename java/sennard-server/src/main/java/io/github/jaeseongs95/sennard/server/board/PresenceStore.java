// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.board;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.jaeseongs95.sennard.app.support.UtcTimestampColumns;
import io.github.jaeseongs95.sennard.service.session.SessionKind;

/**
 * Which sessions run now, from the session identity tables: a live generation with an unexpired lease is online, one whose lease lapsed
 * within the last hour is unreachable. Only a session whose host named itself (a local id) is listed, and only a main one: a conversation seen as
 * a child and a session the host runs as an agent chosen at launch are not. The caller's own session comes first.
 */
@Repository
public class PresenceStore {
    private static final long UNREACHABLE_VISIBLE_MS = 3600_000;
    private static final int LIST_LIMIT = 64;

    public record Row(String host, String sessionId, String state, String startedAt, String heartbeatAt) {
    }

    public record CurrentPresence(List<Row> sessions, long truncated) {
    }

    private record Live(Row row, long leaseUntil) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final UtcTimestampColumns timestamps;

    public PresenceStore(@Qualifier("storeJdbc") JdbcClient jdbc, Clock clock, UtcTimestampColumns timestamps) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.timestamps = timestamps;
    }

    public CurrentPresence currentPresence(String selfHost, String selfSessionId) {
        long now = clock.millis();
        List<Live> live = jdbc.sql("SELECT r.host, r.local_session_id, r.created_at, g.heartbeat_at, g.lease_until "
                + "FROM sennard.session_ref r JOIN sennard.session_generation g ON g.session_ref = r.session_ref "
                + "WHERE r.local_session_id IS NOT NULL AND r.kind = ? AND g.ended_at IS NULL AND g.superseded_at IS NULL")
                .params(SessionKind.MAIN.name())
                .query((row, n) -> {
                    long lease = timestamps.instant(row, "lease_until").toEpochMilli();
                    return new Live(new Row(row.getString("host"), row.getString("local_session_id"), lease > now ? "online" : "unreachable",
                            // The session's first start: its session_ref row. A generation's started_at is when that generation was made, and a recovery after an expired lease makes a new one.
                            timestamps.instant(row, "created_at").toString(),
                            timestamps.instant(row, "heartbeat_at").toString()), lease);
                }).list();
        List<Row> visible = new ArrayList<>(live.stream().filter(item -> item.leaseUntil() > now - UNREACHABLE_VISIBLE_MS).map(Live::row).toList());
        visible.sort(Comparator.comparing(Row::heartbeatAt).reversed());
        List<Row> ordered = new ArrayList<>(visible.stream().filter(row -> isSelf(row, selfHost, selfSessionId)).toList());
        visible.stream().filter(row -> !isSelf(row, selfHost, selfSessionId)).forEach(ordered::add);
        int shown = Math.min(ordered.size(), LIST_LIMIT);
        return new CurrentPresence(List.copyOf(ordered.subList(0, shown)), ordered.size() - shown);
    }

    private static boolean isSelf(Row row, String host, String sessionId) {
        return row.host().equals(host) && row.sessionId().equals(sessionId);
    }
}
