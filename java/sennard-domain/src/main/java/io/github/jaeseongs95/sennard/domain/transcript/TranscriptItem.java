// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.transcript;

import java.util.Objects;

/**
 * One entry of a read together with its key, the host's identifier of that entry. The key is a position, not a copy of anything: a later read names it to continue after this entry
 * ({@link Transcript#continueAfter}). It is the host's own value and means nothing outside the transcript it came from; the caller only hands it back.
 *
 * @param key the host's identifier of the entry; unique within its transcript, and the same on every read of it
 * @param entry what the entry holds, in the shape every host's transcript is reduced to
 */
public record TranscriptItem(String key, TranscriptEntry entry) {
    public TranscriptItem {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entry, "entry");
    }
}
