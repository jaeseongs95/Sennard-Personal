// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Where in the host's transcript a prompt's entry is, kept so that the next read of that prompt starts there (the first read finds it by searching the file). Only the position is
 * kept: the transcript's text is never stored. This service reads and writes the anchor table and nothing that holds the user's words.
 *
 * <p>The first position found is the one kept. Two first readings of one prompt at the same moment meet the primary key: the second is a duplicate key, which is not caught
 * and not run again, and fails that call (the position is found again by the next read). Nothing here touches a file: the transcript is read before and after, never inside
 * the transaction.
 */
@Service
@Validated
public class TranscriptAnchorService {
    /** The longest entry key kept, in characters: the column's size. */
    public static final int ENTRY_KEY_MAX_LENGTH = 512;

    private final TranscriptAnchorRepository anchors;
    private final Clock clock;

    public TranscriptAnchorService(TranscriptAnchorRepository anchors, Clock clock) {
        this.anchors = anchors;
        this.clock = clock;
    }

    /** @return the kept entry key of the prompt, or nothing when its position has not been found yet */
    @Transactional(readOnly = true)
    public Optional<String> find(@NotBlank String userPromptId) {
        return anchors.findById(userPromptId).map(TranscriptAnchor::entryKey);
    }

    /**
     * Keeps the position of a prompt, unless one is kept already.
     *
     * @return the entry key now kept for the prompt: this one, or the one another call kept first
     */
    @Transactional
    public String remember(@NotBlank String userPromptId, @NotBlank @Size(max = ENTRY_KEY_MAX_LENGTH) String entryKey) {
        return anchors.findById(userPromptId).map(TranscriptAnchor::entryKey).orElseGet(() -> {
            anchors.insert(userPromptId, entryKey, clock.instant().truncatedTo(ChronoUnit.MILLIS));
            return entryKey;
        });
    }
}
