// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import java.time.Instant;

/**
 * One prompt of a recall, with the recap that was recorded for it (null when none was).
 *
 * @param promptText the kept text: the whole prompt, or its first part when {@code truncated}
 * @param originalBytes the UTF-8 length of the whole prompt, which {@code promptText} is shorter than when {@code truncated}
 * @param receivedAt when the hook received the prompt (the injected clock's instant, UTC)
 */
public record PromptHit(String userPromptId, String host, String sessionId, Instant receivedAt, String promptText, long originalBytes, boolean truncated, String recapText) {
}
