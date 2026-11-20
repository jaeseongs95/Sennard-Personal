// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import java.time.Instant;

/**
 * One prompt as the reader of its transcript needs it: who said it and where, what the archive kept of it, when the hook received it, and the transcript the host named.
 *
 * @param promptText the kept text: the whole prompt, or its first part when {@code truncated}
 * @param receivedAt when the hook received the prompt (the injected clock's instant, UTC)
 * @param transcriptPath where the host keeps the session's transcript, as the host named it to the hook; null when the host did not say
 */
public record PromptSource(String userPromptId, String host, String sessionId, Instant receivedAt, String promptText, boolean truncated, String transcriptPath) {
}
