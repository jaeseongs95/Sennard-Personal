// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.modules;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.jaeseongs95.sennard.service.store.SqlMerge;

/** Which skill each host called. */
@Repository
public class SkillUsageStore extends ModuleStore {
    public static final int RETAIN_DAYS = 90;
    private static final long DAY_MS = 24 * 3600_000L;

    public SkillUsageStore(@Qualifier("storeJdbc") JdbcClient jdbc) {
        super(jdbc);
    }

    public record Call(String host, String sessionId, String skillId, boolean subagent, String basis, String callId, Instant at) {
    }

    public record UsageRow(String skillId, String host, String basis, boolean subagent, long calls, String lastAt) {
    }

    public record HostFirst(String host, String firstAt) {
    }

    public record Summary(List<UsageRow> rows, List<HostFirst> hosts) {
    }

    /** Who made a call: the constant's name is what the column holds (a yes-or-no fact is a name that says what it means). */
    public enum CallerKind {
        MAIN, SUBAGENT;

        static CallerKind of(boolean subagent) {
            return subagent ? SUBAGENT : MAIN;
        }
    }

    /**
     * One call, deduplicated by the host's call id: a call that has an id is written once, and the ids are kept in their own table whose primary key is the dedup (a UNIQUE index over
     * the calls that have an id would be a partial index). The rows older than the retention are dropped on the same write. It joins the transaction of {@link SkillUsageService}; the id's row is
     * the first thing it writes, so two writers of one id race for it before anything else is written, and the second ends in the translated unique violation.
     */
    public void record(Call call) {
        boolean first = call.callId() == null
                || insertIfAbsent(SqlMerge.into("sennard.skill_call_ids").key("host", call.host()).key("session_id", call.sessionId()).key("call_id", call.callId())
                        .set("at", StoreValues.ts(call.at()))) == 1;
        if (first) {
            update("INSERT INTO sennard.skill_calls (at, host, session_id, skill_id, caller, basis, call_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    StoreValues.ts(call.at()), call.host(), call.sessionId(), call.skillId(), CallerKind.of(call.subagent()), call.basis(), call.callId());
        }
        LocalDateTime cutoff = StoreValues.ts(call.at().toEpochMilli() - RETAIN_DAYS * DAY_MS);
        update("DELETE FROM sennard.skill_calls WHERE at < ?", cutoff);
        update("DELETE FROM sennard.skill_call_ids WHERE at < ?", cutoff);
    }

    /** The skills this session was seen calling, at any time. */
    public List<String> calledInSession(String sessionId) {
        return jdbc.sql("SELECT DISTINCT skill_id FROM sennard.skill_calls WHERE session_id = ?")
                .params(sessionId).query(String.class).list();
    }

    /** Calls of the last {@code days} days. */
    public Summary summary(int days, Instant now) {
        LocalDateTime since = StoreValues.ts(now.toEpochMilli() - days * DAY_MS);
        List<UsageRow> usage = jdbc.sql("SELECT skill_id, host, basis, caller, count(*) AS calls, max(at) AS last_at FROM sennard.skill_calls "
                + "WHERE at >= ? GROUP BY skill_id, host, basis, caller ORDER BY skill_id, host, basis, caller")
                .params(since).query(UsageValue.class).list().stream()
                .map(row -> new UsageRow(row.skillId(), row.host(), row.basis(), row.caller() == CallerKind.SUBAGENT, row.calls(), StoreValues.isoOrNull(row.lastAt()))).toList();
        List<HostFirst> hosts = jdbc.sql("SELECT host, min(at) AS first_at FROM sennard.skill_calls GROUP BY host ORDER BY host")
                .query(HostValue.class).list().stream().map(row -> new HostFirst(row.host(), StoreValues.isoOrNull(row.firstAt()))).toList();
        return new Summary(usage, hosts);
    }

    /** The rows of the two summary queries as the database answers them: the times are TIMESTAMP values, the caller column a constant name (Spring's row mapper reads them by the column names). */
    private record UsageValue(String skillId, String host, String basis, CallerKind caller, long calls, LocalDateTime lastAt) {
    }

    private record HostValue(String host, LocalDateTime firstAt) {
    }
}
