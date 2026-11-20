// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row of {@code sennard.recap_nudge}: the agent was reminded once to record the recap of this prompt. The row is written once and never changed, so it has no version;
 * its key is the prompt's, so a second reminder for the same prompt meets the primary key ({@link RecapNudgeRepository#insert}).
 */
@Table(schema = "sennard", value = "recap_nudge")
public record RecapNudge(@Id String userPromptId, Instant nudgedAt) {
}
