// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row of {@code sennard.turn_recap}: the agent's short note of what it did after one prompt. There is at most one per prompt (the key is the prompt's), and a second
 * recording replaces it. The aggregate is an immutable record, a change is a new record saved with {@code save}, and {@code version} makes two writers of one row fail with an
 * optimistic-lock failure instead of overwriting each other (see {@code BoardSession}).
 */
@Table(schema = "sennard", value = "turn_recap")
public record TurnRecap(@Id String userPromptId, String recapText, Instant recordedAt, @Version Long version) {

    /** The first recap of a prompt. */
    public static TurnRecap first(String userPromptId, String recapText, Instant recordedAt) {
        return new TurnRecap(userPromptId, recapText, recordedAt, null);
    }

    /** The same prompt's recap, written again. */
    public TurnRecap replacedBy(String recapText, Instant recordedAt) {
        return new TurnRecap(userPromptId, recapText, recordedAt, version);
    }
}
