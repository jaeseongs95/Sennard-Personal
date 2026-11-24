// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.board;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The board rows. Extending the {@code Repository} marker limits the operations to the methods declared here.
 */
public interface BoardSessionRepository extends Repository<BoardSession, BoardSessionKey> {
    Optional<BoardSession> findByKeyHostAndKeySessionId(String host, String sessionId);

    /** Saves the aggregate using optimistic locking. The service uses atomic updates that advance the aggregate version. */
    BoardSession save(BoardSession session);

    /** The first write of a row, with what the first change carries (the columns it does not carry are null); a second one for the same session is a duplicate key. */
    @Modifying
    @Query("INSERT INTO sennard.board_sessions (host, session_id, cwd, last_prompt_at, summary, summary_at, role, seen_at, version) "
            + "VALUES (:host, :sessionId, :cwd, :lastPromptAt, :summary, :summaryAt, :role, :seenAt, 0)")
    int insert(@Param("host") String host, @Param("sessionId") String sessionId, @Param("cwd") String cwd, @Param("lastPromptAt") Instant lastPromptAt,
            @Param("summary") String summary, @Param("summaryAt") Instant summaryAt, @Param("role") String role, @Param("seenAt") Instant seenAt);

    /**
     * One input the hook observed, in one statement: the working directory and the prompt time are replaced only when given, and the time seen is set. The database applies it to
     * the row as the last committed writer left it, so two writers of one row both take effect and neither fails.
     *
     * @return the rows changed: 1, or 0 when the session has no row yet
     */
    @Modifying
    @Query("UPDATE sennard.board_sessions SET cwd = COALESCE(:cwd, cwd), last_prompt_at = COALESCE(:promptAt, last_prompt_at), seen_at = :seenAt, version = version + 1 "
            + "WHERE host = :host AND session_id = :sessionId")
    int touch(@Param("host") String host, @Param("sessionId") String sessionId, @Param("cwd") String cwd, @Param("promptAt") Instant promptAt, @Param("seenAt") Instant seenAt);

    /**
     * The summary and, when given, the role, in one statement (an omitted role keeps the one written before).
     *
     * @return the rows changed: 1, or 0 when the session has no row yet
     */
    @Modifying
    @Query("UPDATE sennard.board_sessions SET summary = :summary, summary_at = :at, seen_at = :at, role = COALESCE(:role, role), version = version + 1 "
            + "WHERE host = :host AND session_id = :sessionId")
    int summarize(@Param("host") String host, @Param("sessionId") String sessionId, @Param("summary") String summary, @Param("at") Instant at, @Param("role") String role);

    /** Applies a newer title observation to an existing row. An explicit removal leaves its timestamp in place. */
    @Modifying
    @Query("UPDATE sennard.board_sessions SET user_title = :title, user_title_observed_at = :observedAt, version = version + 1 "
            + "WHERE host = :host AND session_id = :sessionId AND (user_title_observed_at IS NULL OR user_title_observed_at < :observedAt)")
    void observeTitle(@Param("host") String host, @Param("sessionId") String sessionId, @Param("title") String title, @Param("observedAt") Instant observedAt);

    /** Rows last seen before {@code before} (UTC); returns how many were removed. */
    long deleteBySeenAtLessThan(Instant before);
}
