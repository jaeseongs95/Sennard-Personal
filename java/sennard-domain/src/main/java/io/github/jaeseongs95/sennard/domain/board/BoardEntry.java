// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.board;

import java.time.Instant;

/** One session's entry as the board shows it. {@code summaryStale()} is null when no prompt was ever observed on the session. */
public record BoardEntry(String cwd, String summary, Instant summaryAt, String role, Instant lastPromptAt, String userTitle) {
    public BoardEntry(String cwd, String summary, Instant summaryAt, String role, Instant lastPromptAt) {
        this(cwd, summary, summaryAt, role, lastPromptAt, null);
    }

    /** The summary is older than the last prompt. */
    public Boolean summaryStale() {
        return lastPromptAt == null ? null : (summaryAt == null || summaryAt.isBefore(lastPromptAt));
    }
}
