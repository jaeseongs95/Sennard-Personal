// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.execution;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row of {@code sennard.execution}: a program that a workflow had run. The row says who asked and where the program ran, how it ended and where its output is; the output
 * itself is in the log files of the folder {@code logLocation}, never in the database. There is no foreign key: {@code runId} and {@code requestedBy} are the text of the
 * workflow run's id and the server-issued reference of the session. The times are UTC fields of a {@code TIMESTAMP} column.
 *
 * @param id the execution id
 * @param runId the id of the workflow run the execution belongs to
 * @param kind the kind of that workflow
 * @param requestedBy the reference of the session that asked
 * @param hostId the identity of the server installation that ran it ({@code HostIdentity#id()}): several servers on several computers may share one database, and a server accounts
 *     only for the records of its own installation
 * @param argv the program and its arguments as a JSON array of strings
 * @param cwd the working folder
 * @param status how the execution is or ended
 * @param exitCode the exit code, only for {@link ExecutionStatus#EXITED}
 * @param startedAt when it was asked for
 * @param endedAt when it ended; {@code null} while {@link ExecutionStatus#RUNNING} and for {@link ExecutionStatus#LOST} (when it ended is not known)
 * @param logLocation the folder of the log files; {@code null} when none was made
 * @param logCompleteness whether the log files held all the output when the execution ended; never changed after that
 * @param logBytes how many bytes the log files took when the execution ended; {@code null} while running and when the folder could not be measured
 * @param logRemovedAt when the log files were deleted after their retention; {@code null} until then
 * @param logUnreachableAt when the server found the log folder outside its own log folder and so out of its reach; {@code null} otherwise. Not set together with {@code logRemovedAt}
 * @param pid the process id; {@code null} when no process was started
 * @param processStartedAt when that process started, to tell it from another one that has the id later; {@code null} when none or unknown
 */
@Table(schema = "sennard", value = "execution")
public record ExecutionRecord(@Id String id, String runId, ExecutionKind kind, String requestedBy, String hostId, String argv, String cwd, ExecutionStatus status, Integer exitCode,
        Instant startedAt, Instant endedAt, String logLocation, LogCompleteness logCompleteness, Long logBytes, Instant logRemovedAt, Instant logUnreachableAt, Long pid,
        Instant processStartedAt) {
}
