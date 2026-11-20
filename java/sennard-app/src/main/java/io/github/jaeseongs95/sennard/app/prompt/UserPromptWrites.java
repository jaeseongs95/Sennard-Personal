// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import java.time.Instant;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The one way a prompt gets into the archive: a plain INSERT. It is a repository of its own, apart from {@link UserPromptRepository} (which only reads), so that the part of the
 * application that can write the user's words is one class ({@link PromptArchiveWriter}) and the machine check can say who that is. There is no {@code save}, no update and no
 * delete: a prompt is written once.
 */
public interface UserPromptWrites extends Repository<UserPrompt, String> {
    /** @return the rows written: 1 */
    @Modifying
    @Query("INSERT INTO sennard.user_prompt (user_prompt_id, host, session_id, received_at, prompt_text, original_bytes, prompt_digest, completeness, transcript_path) "
            + "VALUES (:userPromptId, :host, :sessionId, :receivedAt, :promptText, :originalBytes, :promptDigest, :completeness, :transcriptPath)")
    int insert(@Param("userPromptId") String userPromptId, @Param("host") String host, @Param("sessionId") String sessionId,
            @Param("receivedAt") Instant receivedAt, @Param("promptText") String promptText, @Param("originalBytes") long originalBytes,
            @Param("promptDigest") String promptDigest, @Param("completeness") String completeness, @Param("transcriptPath") String transcriptPath);
}
