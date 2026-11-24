// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

/** Where an execution is in its life. Only {@link #RUNNING} is not final. */
public enum ExecutionState {
    /** The program was started and has not ended. */
    RUNNING,
    /** The program ended by itself; its exit code is in the snapshot. */
    EXITED,
    /** The program was still running at its time limit; it and every process it started were killed. */
    TIMED_OUT,
    /** The program was killed before its end by a cancel or by the stop of the server; it has no exit code. */
    KILLED,
    /** The program was never started (it does not exist, the working directory is not usable, the log could not be opened); the snapshot says why. */
    NOT_STARTED;

    /** Whether the execution has reached its end and will not change any more. */
    public boolean finished() {
        return this != RUNNING;
    }
}
