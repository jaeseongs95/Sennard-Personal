// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row of {@code sennard.user_prompt}: one prompt of a main session, word for word (or its first part when it was over the size limit: {@code completeness}).
 * {@code originalBytes} and {@code promptDigest} are those of the whole prompt. The row is never changed: this record has no version and nothing here saves it; the code has one
 * write path, one statement ({@link UserPromptWrites}), and reads it back through {@link UserPromptRepository}; the machine check ({@code PromptArchiveRules}) holds the
 * code to that.
 *
 * <p>The time is an {@code Instant} kept in a {@code TIMESTAMP} column as UTC ({@code UtcTimestampColumns} converts at the database boundary); the digest is SHA-256 of the UTF-8 bytes in lower-case hex.
 */
@Table(schema = "sennard", value = "user_prompt")
public record UserPrompt(@Id String userPromptId, String host, String sessionId, Instant receivedAt, String promptText,
        long originalBytes, String promptDigest, Completeness completeness, String transcriptPath) {
}
