// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.execution;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The executions. The {@code Repository} marker: only what is declared here is offered. Every change is one conditional statement: what decides a race is the number of rows it
 * changed, and the repository joins the transaction of the service method that calls it and opens none. The log retention's queries (a page of candidates, a sum) are
 * {@link ExecutionLogCandidates}'.
 */
public interface ExecutionRepository extends Repository<ExecutionRecord, String> {
    String COLUMNS = "id, run_id, kind, requested_by, host_id, argv, cwd, status, exit_code, started_at, ended_at, log_location, log_completeness, log_bytes, log_removed_at, "
            + "log_unreachable_at, pid, process_started_at";

    /**
     * When the log files of a row are for the retention to judge ({@code log_pending} is 1): the execution has ended ({@code status} is not RUNNING), has a log folder, and the files are
     * neither deleted nor out of reach. It is what the table's CHECK {@code execution_log_pending} says, and its text is written here once: the statement that closes a row uses it
     * ({@link #LOG_PENDING_AFTER_CLOSE}), a statement that notes the files as deleted or out of reach makes the condition false by itself and sets 0, and the value of a row that is
     * written ({@code ExecutionRecords#begin}) is the same condition on values that nothing has deleted yet. The database checks every one of them.
     */
    String LOG_FILES_LEFT = "log_location IS NOT NULL AND log_removed_at IS NULL AND log_unreachable_at IS NULL";

    /** {@code log_pending} of a row that is closed with the status {@code :status}: the status the row will have, and what {@link #LOG_FILES_LEFT} says of the row as it is. */
    String LOG_PENDING_AFTER_CLOSE = "CASE WHEN :status <> 'RUNNING' AND " + LOG_FILES_LEFT + " THEN 1 ELSE 0 END";

    Optional<ExecutionRecord> findById(String id);

    /** The executions of one workflow run, the earliest first. */
    @Query("SELECT " + COLUMNS + " FROM sennard.execution WHERE run_id = :runId ORDER BY started_at, id")
    List<ExecutionRecord> findByRunId(@Param("runId") String runId);

    /** The executions of one server installation that have not ended, the earliest first: what that server, restarted, has to account for. */
    @Query("SELECT " + COLUMNS + " FROM sennard.execution WHERE status = 'RUNNING' AND host_id = :hostId ORDER BY started_at, id")
    List<ExecutionRecord> findRunning(@Param("hostId") String hostId);

    /**
     * Writes a record. {@code agedAt} and {@code logPending} are what the table checks them to be ({@code COALESCE(ended_at, started_at)}, and 1 exactly for an ended execution that has a
     * log folder): the service gives them with the row, since a column that the database derives from others is not one it can fill by default.
     */
    @Modifying
    @Query("INSERT INTO sennard.execution (" + COLUMNS + ", aged_at, log_pending) VALUES (:id, :runId, :kind, :requestedBy, :hostId, :argv, :cwd, :status, :exitCode, :startedAt, :endedAt, "
            + ":logLocation, :logCompleteness, :logBytes, NULL, NULL, :pid, :processStartedAt, :agedAt, :logPending)")
    int insert(@Param("id") String id, @Param("runId") String runId, @Param("kind") String kind, @Param("requestedBy") String requestedBy, @Param("hostId") String hostId,
            @Param("argv") String argv, @Param("cwd") String cwd, @Param("status") String status, @Param("exitCode") Integer exitCode, @Param("startedAt") Instant startedAt,
            @Param("endedAt") Instant endedAt, @Param("logLocation") String logLocation, @Param("logCompleteness") String logCompleteness, @Param("logBytes") Long logBytes,
            @Param("pid") Long pid, @Param("processStartedAt") Instant processStartedAt, @Param("agedAt") Instant agedAt, @Param("logPending") int logPending);

    /**
     * Closes an execution that is still running; applied only while its status is RUNNING, so the end that came first stays (a late end of a process that a restarted server already
     * closed does not overwrite it). The age counts from the end from now on ({@code aged_at}; a LOST record has no end and keeps the start), and the log files of one that has a
     * folder become the retention's to judge ({@code log_pending}).
     *
     * @return 1 when this call closed it; 0 when it was closed already or does not exist
     */
    @Modifying
    @Query("UPDATE sennard.execution SET status = :status, exit_code = :exitCode, ended_at = :endedAt, aged_at = COALESCE(:endedAt, aged_at), log_completeness = :logCompleteness, "
            + "log_bytes = :logBytes, log_pending = " + LOG_PENDING_AFTER_CLOSE + " WHERE id = :id AND status = 'RUNNING'")
    int close(@Param("id") String id, @Param("status") String status, @Param("exitCode") Integer exitCode, @Param("endedAt") Instant endedAt,
            @Param("logCompleteness") String logCompleteness, @Param("logBytes") Long logBytes);

    /**
     * Notes that the log files of an ended execution were deleted; a running one is never touched, and neither is one that was noted (as deleted or as out of reach) already.
     *
     * @return 1 when this call noted it; 0 when it is running, was noted already or does not exist
     */
    @Modifying
    @Query("UPDATE sennard.execution SET log_removed_at = :at, log_pending = 0 WHERE id = :id AND status <> 'RUNNING' AND log_removed_at IS NULL AND log_unreachable_at IS NULL")
    int markLogRemoved(@Param("id") String id, @Param("at") Instant at);

    /**
     * Notes that the log folder of an execution is not one this server can delete (it is not in the server's own log folder); the files are not touched. Only an execution that has
     * ended is noted, as for {@link #markLogRemoved} (the closing of a running one would then have to find the row already noted); a record that was noted already, either way, is left
     * as it is.
     *
     * @return 1 when this call noted it; 0 when it is running, was noted already or does not exist
     */
    @Modifying
    @Query("UPDATE sennard.execution SET log_unreachable_at = :at, log_pending = 0 WHERE id = :id AND status <> 'RUNNING' AND log_removed_at IS NULL AND log_unreachable_at IS NULL")
    int markLogUnreachable(@Param("id") String id, @Param("at") Instant at);

    /**
     * Notes how many bytes the log files of an ended execution take, for a record that had no size (it could not be measured when the execution ended). A record that has a size, is
     * running, or whose files are deleted or out of reach is left as it is.
     *
     * @return 1 when this call noted it; 0 otherwise
     */
    @Modifying
    @Query("UPDATE sennard.execution SET log_bytes = :bytes WHERE id = :id AND log_pending = 1 AND log_bytes IS NULL")
    int markLogBytes(@Param("id") String id, @Param("bytes") long bytes);
}
