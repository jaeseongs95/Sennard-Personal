// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.execution;

/**
 * Whether the log files of an execution held all that the program wrote when it ended (the {@code log_completeness} column; a CHECK lists the values). It is a fact of the
 * execution and never changes after its end: what became of the files later (deleted after their retention, out of this server's reach) is in the columns {@code log_removed_at}
 * and {@code log_unreachable_at}, not here.
 */
public enum LogCompleteness {
    COMPLETE,
    /** A log file stopped at its limit, or could not be written to: the end of the output is not in it. */
    TRUNCATED;

    public static LogCompleteness of(boolean truncated) {
        return truncated ? TRUNCATED : COMPLETE;
    }
}
