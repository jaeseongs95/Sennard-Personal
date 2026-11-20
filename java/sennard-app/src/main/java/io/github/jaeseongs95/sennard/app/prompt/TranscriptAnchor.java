// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row of {@code sennard.transcript_anchor}: where in the host's transcript the entry of one prompt is. {@code entryKey} is the host's own identifier of the entry (Claude
 * Code: the line's {@code uuid}); nothing of the transcript is kept, only this position. The row is written once and never changed, so it has no version; its key is the
 * prompt's, so a second row for the same prompt meets the primary key ({@link TranscriptAnchorRepository#insert}).
 *
 * <p>The time is an {@code Instant} kept in a {@code TIMESTAMP} column as UTC (see {@link UserPrompt}).
 */
@Table(schema = "sennard", value = "transcript_anchor")
public record TranscriptAnchor(@Id String userPromptId, String entryKey, Instant locatedAt) {
}
