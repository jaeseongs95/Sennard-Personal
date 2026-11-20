// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.transcript;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * What it takes to find one user's message in a host's transcript: the session it was said in, the file the host named for that session, the text the archive kept and when
 * the hook received it. Everything comes from the archive's row of the prompt; nothing here is something the calling agent typed.
 *
 * @param host the host's name for itself, as in the session key
 * @param sessionId the host's own session id
 * @param file the transcript file as the host named it to the hook
 * @param promptText the text the archive holds: the whole prompt, or its first part when {@code truncated}
 * @param receivedAt when the hook received the prompt; the entry cannot have been written long before it
 * @param truncated whether {@code promptText} is only the first part of what the user said, so that the entry is found by its beginning
 */
public record TranscriptQuery(String host, String sessionId, Path file, String promptText, Instant receivedAt, boolean truncated) {
    public TranscriptQuery {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(promptText, "promptText");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
