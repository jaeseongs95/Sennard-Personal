// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.board;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row of {@code sennard.board_sessions}: what a session wrote on the board (summary, role) and what the hooks saw (working directory, last prompt).
 * The aggregate is an immutable record; a change is a new record saved with {@code save}. The key is composite, so it is an {@code @Embedded @Id}
 * record, and {@code @Version} tells Spring Data JDBC whether the row is new (version null: INSERT) and makes a concurrent change of the same row fail
 * with an {@code OptimisticLockingFailureException} instead of overwriting it.
 *
 * <p>The times are {@code Instant}s. The column is {@code TIMESTAMP} without a time zone and holds the UTC fields of the instant; {@code UtcTimestampColumns} (the one
 * conversion of the database boundary, registered with Spring Data JDBC) writes and reads them, so the JVM's zone never takes part.
 */
@Table(schema = "sennard", value = "board_sessions")
public record BoardSession(@Id @Embedded(onEmpty = Embedded.OnEmpty.USE_NULL) BoardSessionKey key, String cwd, String summary, Instant summaryAt, Instant lastPromptAt,
        Instant seenAt, String role, String userTitle, Instant userTitleObservedAt, @Version Long version) {

    /** A session seen for the first time. */
    public static BoardSession first(BoardSessionKey key, Instant seenAt) {
        return new BoardSession(key, null, null, null, null, seenAt, null, null, null, null);
    }

    public BoardSession seen(String cwd, Instant promptAt, Instant seenAt) {
        return new BoardSession(key, cwd != null ? cwd : this.cwd, summary, summaryAt, promptAt != null ? promptAt : lastPromptAt, seenAt, role,
                userTitle, userTitleObservedAt, version);
    }

    public BoardSession summarized(String summary, Instant now, String role) {
        return new BoardSession(key, cwd, summary, now, lastPromptAt, now, role != null ? role : this.role, userTitle, userTitleObservedAt, version);
    }
}
