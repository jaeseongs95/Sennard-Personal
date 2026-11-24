// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.execution;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.app.execution.ExecutionLogCandidates.Candidate;
import io.github.jaeseongs95.sennard.app.execution.ExecutionLogCandidates.Position;
import tools.jackson.databind.json.JsonMapper;

/**
 * The record of the programs that workflows had run ({@code sennard.execution}). Each method is one use case and one transaction; the repository opens none. It records and reads;
 * what a record means for the process (starting, ending, finding it again after a restart) is for whoever uses it ({@code ExecutionTracker}, {@code ExecutionRecovery}).
 *
 * <p>{@link #begin} and {@link #finish} are called from different threads (the one that starts a program and the one that notices its end), and the second must find the row the first
 * wrote: a caller that is inside a transaction of its own would keep the row uncommitted while the other thread looks for it, and the record would stay running. They refuse to run
 * inside a transaction ({@link Propagation#NEVER}), and each statement they run is its own commit. {@link #requireOutsideTransaction} is the same refusal as a question of its own,
 * for the caller that has something to do before it writes (starting the program) that it must not do when the write is going to be refused.
 */
@Service
public class ExecutionRecords {
    /**
     * An execution to record, as it is when it is first written: running, or already ended (a program that was not started, or that ended before the record was written).
     *
     * @param hostId the identity of the server installation that runs it
     * @param argv the program and its arguments; stored as a JSON array
     * @param logBytes what the log files take when the record is written already ended; {@code null} when running, when there are none, or when they could not be measured
     */
    public record NewExecution(String id, String runId, ExecutionKind kind, String requestedBy, String hostId, List<String> argv, String cwd, ExecutionStatus status, Integer exitCode,
            Instant startedAt, Instant endedAt, String logLocation, LogCompleteness logCompleteness, Long logBytes, Long pid, Instant processStartedAt) {
        public NewExecution {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(requestedBy, "requestedBy");
            Objects.requireNonNull(hostId, "hostId");
            Objects.requireNonNull(argv, "argv");
            argv = List.copyOf(argv);
            Objects.requireNonNull(cwd, "cwd");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(logCompleteness, "logCompleteness");
        }
    }

    private final ExecutionRepository executions;
    private final JsonMapper mapper;
    private final ExecutionLogCandidates candidates;

    public ExecutionRecords(ExecutionRepository executions, JsonMapper mapper, ExecutionLogCandidates candidates) {
        this.executions = executions;
        this.mapper = mapper;
        this.candidates = candidates;
    }

    /**
     * Writes nothing: refuses a caller that is inside a transaction, which {@link #begin} and {@link #finish} would refuse too. It is asked before the program is started, so that a
     * program is not started for a record that cannot be written.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException when the caller is inside a transaction
     */
    @Transactional(propagation = Propagation.NEVER)
    public void requireOutsideTransaction() {
        // nothing to do: the declaration is the whole of it
    }

    /**
     * Writes the record of an execution; a second record with the same id is a duplicate-key failure of the database.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException when the caller is inside a transaction
     */
    @Transactional(propagation = Propagation.NEVER)
    public void begin(NewExecution execution) {
        // the age counts from the end (from the start for one with none), and the log files are the retention's to judge once the execution has ended and has a folder (nothing is deleted
        // or out of reach in a row that is just written: ExecutionRepository.LOG_FILES_LEFT is the same condition for the statements that change a row): the table checks both
        Instant agedAt = execution.endedAt() != null ? execution.endedAt() : execution.startedAt();
        int logPending = execution.status() != ExecutionStatus.RUNNING && execution.logLocation() != null ? 1 : 0;
        executions.insert(execution.id(), execution.runId(), execution.kind().name(), execution.requestedBy(), execution.hostId(), mapper.writeValueAsString(execution.argv()),
                execution.cwd(), execution.status().name(), execution.exitCode(), execution.startedAt(), execution.endedAt(), execution.logLocation(),
                execution.logCompleteness().name(), execution.logBytes(), execution.pid(), execution.processStartedAt(), agedAt, logPending);
    }

    /**
     * Closes a record that is running. A record that is not running (it ended, or does not exist) is left as it is.
     *
     * @param status the end; not {@link ExecutionStatus#RUNNING}
     * @param endedAt when it ended; {@code null} exactly for {@link ExecutionStatus#LOST}, whose end is not known
     * @param logBytes what the log files take now; {@code null} when there are none or they could not be measured
     * @return whether this call closed it
     * @throws org.springframework.transaction.IllegalTransactionStateException when the caller is inside a transaction
     */
    @Transactional(propagation = Propagation.NEVER)
    public boolean finish(String id, ExecutionStatus status, Integer exitCode, Instant endedAt, LogCompleteness logCompleteness, Long logBytes) {
        if (!status.finished()) {
            throw new IllegalArgumentException("A record is closed with an end, not " + status);
        }
        if ((status == ExecutionStatus.LOST) != (endedAt == null)) {
            throw new IllegalArgumentException("A record has an end time unless it is LOST (its end is not known): " + status);
        }
        return executions.close(id, status.name(), exitCode, endedAt, logCompleteness.name(), logBytes) == 1;
    }

    /**
     * Notes that the log files of an ended execution were deleted. A record that is running, was noted already (deleted, or out of reach) or does not exist is left as it is. The
     * record's {@code logCompleteness} stays as it was.
     *
     * @return whether this call noted it
     */
    @Transactional
    public boolean logRemoved(String id, Instant at) {
        return executions.markLogRemoved(id, at) == 1;
    }

    /**
     * Notes that the log folder of an execution is not one of this server's (it is outside its log folder): its files are not this server's to delete and the record is not looked at
     * again by the retention. A record that is running (it is noted once it has ended), was noted already, either way, or does not exist is left as it is.
     *
     * @return whether this call noted it
     */
    @Transactional
    public boolean logUnreachable(String id, Instant at) {
        return executions.markLogUnreachable(id, at) == 1;
    }

    /**
     * Notes what the log files of an ended execution take, for a record that has no size (it could not be measured when the execution ended), so that the retention does not measure it
     * again and the sum of the sizes has it. A record that has a size already, is running, or whose files were deleted or are out of reach is left as it is.
     *
     * @return whether this call noted it
     */
    @Transactional
    public boolean logBytesNoted(String id, long bytes) {
        return executions.markLogBytes(id, bytes) == 1;
    }

    @Transactional(readOnly = true)
    public Optional<ExecutionRecord> find(String id) {
        return executions.findById(id);
    }

    @Transactional(readOnly = true)
    public List<ExecutionRecord> ofRun(String runId) {
        return executions.findByRunId(runId);
    }

    /** The records of one server installation that have not been closed. */
    @Transactional(readOnly = true)
    public List<ExecutionRecord> running(String hostId) {
        return executions.findRunning(hostId);
    }

    /** At most {@code limit} ended executions of one installation whose log files are still there, oldest first, after {@code after} when it is given. */
    @Transactional(readOnly = true)
    public List<Candidate> logCandidates(String hostId, Position after, int limit) {
        return candidates.page(hostId, after, limit);
    }

    /** What the log files of the ended executions of one installation take together, by the bytes noted when each ended. */
    @Transactional(readOnly = true)
    public long loggedBytes(String hostId) {
        return candidates.bytes(hostId);
    }

    /** Which of {@code ids} are executions of one installation with log files that are still there and in its reach (running ones too). */
    @Transactional(readOnly = true)
    public Set<String> withLogFolders(String hostId, Collection<String> ids) {
        return candidates.recorded(hostId, ids);
    }

    /** The program and its arguments of a record. */
    public List<String> argvOf(ExecutionRecord record) {
        return List.of(mapper.readValue(record.argv(), String[].class));
    }
}
