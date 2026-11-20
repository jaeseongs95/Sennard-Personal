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

/** The recaps. The {@code Repository} marker: only what is declared here is offered (see {@link UserPromptRepository}). */
public interface TurnRecapRepository extends Repository<TurnRecap, String> {
    Optional<TurnRecap> findById(String userPromptId);

    boolean existsById(String userPromptId);

    /** A row written by a test as the aggregate; the service writes the first recap with {@link #insert} and later ones with {@link #replace}. */
    TurnRecap save(TurnRecap recap);

    /** The first recap of a prompt, one INSERT statement; a second one for the same prompt is a duplicate key (the primary key is the prompt's). */
    @Modifying
    @Query("INSERT INTO sennard.turn_recap (user_prompt_id, recap_text, recorded_at, version) VALUES (:userPromptId, :recapText, :recordedAt, 0)")
    int insert(@Param("userPromptId") String userPromptId, @Param("recapText") String recapText, @Param("recordedAt") Instant recordedAt);

    /**
     * A later recording of the same prompt, in one statement: the recap and the time are replaced. The database applies it to the row as the last committed writer left it, so two
     * replacements both take effect (the later commit is the one that stays) and neither fails.
     *
     * @return the rows changed: 1, or 0 when the prompt has no recap yet
     */
    @Modifying
    @Query("UPDATE sennard.turn_recap SET recap_text = :recapText, recorded_at = :recordedAt, version = version + 1 WHERE user_prompt_id = :userPromptId")
    int replace(@Param("userPromptId") String userPromptId, @Param("recapText") String recapText, @Param("recordedAt") Instant recordedAt);
}
