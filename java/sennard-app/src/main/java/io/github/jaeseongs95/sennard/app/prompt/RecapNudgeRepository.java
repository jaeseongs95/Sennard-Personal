// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import java.time.Instant;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** The reminders. The {@code Repository} marker: only what is declared here is offered (see {@link UserPromptRepository}). */
public interface RecapNudgeRepository extends Repository<RecapNudge, String> {
    boolean existsById(String userPromptId);

    /**
     * Writes the reminder of a prompt. A second one for the same prompt is a duplicate key (the primary key is the prompt's), however close the two calls are, which is how
     * two turn ends of one prompt ask the agent once.
     *
     * @return the rows written: 1
     */
    @Modifying
    @Query("INSERT INTO sennard.recap_nudge (user_prompt_id, nudged_at) VALUES (:userPromptId, :nudgedAt)")
    int insert(@Param("userPromptId") String userPromptId, @Param("nudgedAt") Instant nudgedAt);
}
