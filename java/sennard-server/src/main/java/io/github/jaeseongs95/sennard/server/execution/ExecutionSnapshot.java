// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.time.Instant;
import java.util.Objects;

/**
 * An execution as it is at one moment. A running execution's output is what has been read so far.
 *
 * @param id the execution id, made when the execution was asked for
 * @param state where the execution is
 * @param exitCode the exit code of the program, only for {@link ExecutionState#EXITED}; {@code null} otherwise
 * @param requestedAt when the execution was asked for
 * @param endedAt when it ended; {@code null} while it runs
 * @param process the operating system process; {@code null} when none was started
 * @param logLocation the folder that holds {@value #STDOUT_LOG} and {@value #STDERR_LOG} of this execution; {@code null} when none was made
 * @param stdout what is kept in memory of the standard output
 * @param stderr what is kept in memory of the standard error
 * @param failure why the execution was not started, for {@link ExecutionState#NOT_STARTED}; {@code null} otherwise
 */
public record ExecutionSnapshot(String id, ExecutionState state, Integer exitCode, Instant requestedAt, Instant endedAt, ProcessIdentity process, String logLocation, StreamOutput stdout,
        StreamOutput stderr, String failure) {
    /** The name of the file of the standard output in the log folder. */
    public static final String STDOUT_LOG = "stdout.log";
    /** The name of the file of the standard error in the log folder. */
    public static final String STDERR_LOG = "stderr.log";

    public ExecutionSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        if (exitCode != null && state != ExecutionState.EXITED) {
            throw new IllegalArgumentException("Only an execution that exited has an exit code, not " + state);
        }
        if (state.finished() != (endedAt != null)) {
            throw new IllegalArgumentException("An execution has an end time exactly when it is finished: " + state);
        }
    }

    /** Whether any of the two streams has output that is not in memory. */
    public boolean outputOmitted() {
        return stdout.omitted() || stderr.omitted();
    }

    /** Whether the log file of either stream stopped taking bytes at its limit. */
    public boolean logTruncated() {
        return stdout.logTruncated() || stderr.logTruncated();
    }
}
