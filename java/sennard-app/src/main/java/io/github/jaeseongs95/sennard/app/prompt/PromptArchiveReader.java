// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What the agent may do with the user's words: read them. Everything here is {@code @Transactional(readOnly = true)} and reaches the archive only through the read-side
 * pieces ({@link PromptSearch}, {@link ArchivedSessions}, the read-only {@link UserPromptRepository}); the class that writes a prompt ({@link PromptArchiveWriter}) is not one of its parts. Which sessions a caller may
 * read is the caller's to decide (the server's, from the signed role binding); this service reads the sessions it is given.
 */
@Service
@Validated
public class PromptArchiveReader {
    /** How many prompts a recall answers when the caller names no number. */
    public static final int DEFAULT_LIMIT = 20;
    /** The most prompts one recall answers. */
    public static final int MAX_LIMIT = 100;
    /** The longest text a recall searches for, in characters. */
    public static final int QUERY_MAX_LENGTH = 200;

    private final PromptSearch search;
    private final ArchivedSessions sessions;
    private final UserPromptRepository prompts;

    public PromptArchiveReader(PromptSearch search, ArchivedSessions sessions, UserPromptRepository prompts) {
        this.search = search;
        this.sessions = sessions;
        this.prompts = prompts;
    }

    /**
     * One prompt by its identifier, as the reader of its transcript needs it: who said it, what was kept, when it was received, and the transcript path the host named. Whether the
     * caller may read that transcript is the server's to judge ({@code TranscriptAccess}); this reads the row.
     *
     * @return the prompt, or nothing when the archive has none of this identifier
     */
    @Transactional(readOnly = true)
    public Optional<PromptSource> source(@NotBlank String userPromptId) {
        return prompts.findById(userPromptId).map(row -> new PromptSource(row.userPromptId(), row.host(), row.sessionId(),
                row.receivedAt(), row.promptText(), row.completeness().isTruncated(), row.transcriptPath()));
    }

    /**
     * The prompts of these sessions, newest first, with their recaps.
     *
     * @param query a part of the prompt to find, ignoring only the case of the ASCII letters A-Z; null, empty or only white space takes every prompt
     */
    @Transactional(readOnly = true)
    public List<PromptHit> recall(@NotNull List<ArchivedSession> from, @Size(max = QUERY_MAX_LENGTH) String query, @Min(1) @Max(MAX_LIMIT) int limit) {
        return recall(from, query, limit, null, null);
    }

    @Transactional(readOnly = true)
    public List<PromptHit> recall(@NotNull List<ArchivedSession> sessions, @Size(max = QUERY_MAX_LENGTH) String query,
            @Min(1) @Max(MAX_LIMIT) int limit, Instant from, Instant to) {
        return search.search(sessions, query == null || query.isBlank() ? null : query, limit, from, to);
    }

    /** The sessions that have prompts in the archive, the most recently active first (at most {@link ArchivedSessions#MOST}). */
    @Transactional(readOnly = true)
    public List<ArchivedSession> sessions() {
        return sessions.recent();
    }
}
