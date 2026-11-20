// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.domain.Timestamps;

/**
 * The rows a PreToolUse hook leaves for the tool call it precedes. A claim reads the row and then deletes it by its key, and only the claimer whose DELETE changed a row
 * has the call: of any number of claimers exactly one gets the row, because a second DELETE of the same row finds nothing left to change.
 */
@Service
public class CallAdmissions {
    /** A row is kept this long if nothing claims it. */
    private static final Duration RETENTION = Duration.ofDays(1);
    private static final Duration PREFIX_MATCH_WINDOW = Duration.ofSeconds(30);
    private static final String COLUMNS = "call_id, tool, host_session_id, agent_id, model, effort, turn_id, transcript_path, created_at";

    /** One observed call. Nullable fields are the ones the host did not report. */
    public record Admission(String callId, String tool, String hostSessionId, String agentId, String model, String effort, String turnId, String transcriptPath) {
    }

    /** A row as the table has it (what {@link #COLUMNS} selects); Spring's row mapper fills it by the column names and reads the time as the TIMESTAMP value. */
    private record Row(String callId, String tool, String hostSessionId, String agentId, String model, String effort, String turnId, String transcriptPath, LocalDateTime createdAt) {
        Admission toAdmission() {
            return new Admission(callId, tool, hostSessionId, agentId, model, effort, turnId, transcriptPath);
        }
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public CallAdmissions(@Qualifier("storeJdbc") JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Leaves the row of a call, in one transaction: the old unclaimed rows are dropped, the row of this call takes the new facts when it is there already (the same call seen
     * again), and is inserted when it is not. Two writers of a call this server has not seen both find no row and both insert; the second one waits for the first one's commit and
     * ends with the store's translated unique-key failure ({@code STORE_UNIQUE_VIOLATION}), which this design accepts: nothing catches it and nothing runs the call again.
     */
    @Transactional
    public void leave(String host, Admission admission) {
        Instant now = clock.instant();
        LocalDateTime created = Timestamps.toTimestamp(now);
        jdbc.sql("DELETE FROM sennard.call_admission WHERE created_at < ?").params(Timestamps.toTimestamp(now.minus(RETENTION))).update();
        int replaced = jdbc.sql("UPDATE sennard.call_admission SET tool = ?, host_session_id = ?, agent_id = ?, model = ?, effort = ?, turn_id = ?, transcript_path = ?, created_at = ? "
                + "WHERE host = ? AND call_id = ?")
                .params(admission.tool(), admission.hostSessionId(), admission.agentId(), admission.model(), admission.effort(), admission.turnId(), admission.transcriptPath(),
                        created, host, admission.callId())
                .update();
        if (replaced == 0) {
            jdbc.sql("INSERT INTO sennard.call_admission (host, call_id, tool, host_session_id, agent_id, model, effort, turn_id, transcript_path, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(host, admission.callId(), admission.tool(), admission.hostSessionId(), admission.agentId(), admission.model(), admission.effort(), admission.turnId(),
                            admission.transcriptPath(), created)
                    .update();
        }
    }

    /** Takes the row of exactly this call; empty when it was never written or was already claimed. */
    @Transactional
    public Optional<Admission> claim(String host, String callId) {
        Optional<Row> row = jdbc.sql("SELECT " + COLUMNS + " FROM sennard.call_admission WHERE host = ? AND call_id = ?").params(host, callId).query(Row.class).optional();
        return row.filter(found -> delete(host, found.callId())).map(Row::toAdmission);
    }

    /** Claims a fresh call of a single conversation. Without a host call ID, simultaneous hooks with the same tool and arguments are ambiguous. */
    @Transactional
    public Optional<Admission> claimConversationCall(String host, String conversation, String tool, String callerKey, String callKey) {
        String delimiterAndArguments = callKey.substring(callerKey.length());
        LocalDateTime cutoff = Timestamps.toTimestamp(clock.instant().minus(PREFIX_MATCH_WINDOW));
        List<Row> found = jdbc.sql("SELECT " + COLUMNS + " FROM sennard.call_admission WHERE host = ? AND tool = ? AND created_at >= ? "
                + "AND SUBSTRING(call_id FROM ? FOR ?) = ? ORDER BY call_id")
                .params(host, tool, cutoff, callerKey.length() + 1, delimiterAndArguments.length(), delimiterAndArguments).query(Row.class).list();
        if (found.stream().anyMatch(row -> !conversation.equals(row.hostSessionId()))) {
            return Optional.empty();
        }
        return takeNewest(host, found.stream().filter(row -> row.callId().startsWith(callKey + ":")).toList());
    }

    private Optional<Admission> takeNewest(String host, List<Row> found) {
        List<Row> taken = new ArrayList<>();
        for (Row row : found) {
            if (delete(host, row.callId())) {
                taken.add(row);
            }
        }
        return taken.stream().max(Comparator.comparing(Row::createdAt).thenComparing(Row::callId)).map(Row::toAdmission);
    }

    /** True when this call removed the row: a claimer that reads the row and loses the race for it finds nothing to remove. */
    private boolean delete(String host, String callId) {
        return jdbc.sql("DELETE FROM sennard.call_admission WHERE host = ? AND call_id = ?").params(host, callId).update() == 1;
    }
}
