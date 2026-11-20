// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** The anchors. The {@code Repository} marker: only what is declared here is offered (see {@link UserPromptRepository}); the SQL is ANSI. */
public interface TranscriptAnchorRepository extends Repository<TranscriptAnchor, String> {
    Optional<TranscriptAnchor> findById(String userPromptId);

    /**
     * Writes the anchor of a prompt. A second one for the same prompt is a duplicate key (the primary key is the prompt's), however close the two calls are.
     *
     * @return the rows written: 1
     */
    @Modifying
    @Query("INSERT INTO sennard.transcript_anchor (user_prompt_id, entry_key, located_at) VALUES (:userPromptId, :entryKey, :locatedAt)")
    int insert(@Param("userPromptId") String userPromptId, @Param("entryKey") String entryKey, @Param("locatedAt") Instant locatedAt);
}
