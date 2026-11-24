// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.execution;

/**
 * What the {@code status} column of an execution holds (a CHECK lists the values). Four of them are how an execution ended while the server that ran it was up
 * ({@link #EXITED}, {@link #TIMED_OUT}, {@link #KILLED}, {@link #NOT_STARTED}); {@link #KILLED} is also what a restarted server writes when it found the process of a record that
 * a server before it left running, and killed it. {@link #LOST} is what a restarted server writes when it could not tell what became of the process.
 */
public enum ExecutionStatus {
    /** The program was started and the record has not been closed. */
    RUNNING,
    /** The program ended by itself; the exit code is in the record. */
    EXITED,
    /** The program was still running at its time limit and was killed. */
    TIMED_OUT,
    /** The program was killed before its end: by a cancel or the stop of the server that ran it, or by the restarted server that found it still alive. It has no exit code. */
    KILLED,
    /** The program was never started. */
    NOT_STARTED,
    /**
     * The server that ran the program did not record how it ended, and the restarted server could not find out: the process was not found, or it cannot be told from another
     * process (no start time was recorded). The result is unknown and it is neither a success nor a failure; the process <b>may still be alive</b> (it is only not touched when
     * it cannot be told from another). Nothing is known of when it ended, so a {@code LOST} record has no end time.
     */
    LOST;

    public boolean finished() {
        return this != RUNNING;
    }
}
