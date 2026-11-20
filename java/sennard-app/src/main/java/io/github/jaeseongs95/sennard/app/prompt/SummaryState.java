// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import io.github.jaeseongs95.sennard.domain.board.BoardEntry;

/**
 * What the session board shows of a session's summary, which is what a person reads to know what the session does now: nothing, a summary older than the last prompt, or a current one.
 * The staleness is the board's own judgment ({@link BoardEntry#summaryStale()}), not a second one.
 */
public enum SummaryState {
    /** The session has no summary on the board. */
    MISSING,
    /** The summary is older than the session's last prompt. */
    STALE,
    /** The summary is there and is not older than the last prompt. */
    CURRENT;

    public static SummaryState of(BoardEntry found) {
        return found.summary() == null || found.summary().isBlank() ? MISSING : Boolean.TRUE.equals(found.summaryStale()) ? STALE : CURRENT;
    }

    /** Whether the agent is to be asked to write the summary. */
    public boolean due() {
        return this != CURRENT;
    }
}
