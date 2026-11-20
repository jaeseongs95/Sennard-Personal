// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.transcript;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A place in a transcript: the file and the host's identifier of one entry in it. It is a position, not a copy of anything: the archive stores {@code entryKey} so that the
 * next read starts there without searching again.
 *
 * @param host the host's name for itself
 * @param sessionId the host's own session id
 * @param file the transcript file
 * @param entryKey the host's identifier of the entry (Claude Code: the entry's {@code uuid})
 */
public record TranscriptLocation(String host, String sessionId, Path file, String entryKey) {
    public TranscriptLocation {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(entryKey, "entryKey");
    }
}
