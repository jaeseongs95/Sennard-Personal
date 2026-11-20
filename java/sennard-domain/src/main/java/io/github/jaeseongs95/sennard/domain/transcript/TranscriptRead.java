// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.transcript;

import java.util.List;
import java.util.Objects;

/** The answer of {@link Transcript#readAfter} and {@link Transcript#continueAfter}: the entries that follow, or why they cannot be read. */
public sealed interface TranscriptRead {
    /**
     * @param items what follows the message (or the entry a read continued after), each with the host's key of it, in the order the host wrote it; at most the limit
     * @param more whether the turn goes on beyond these entries: true when the end of the turn (the user's next message) was not reached, because the limit stopped the read or
     *     because the file ends first (the turn is still being written). False only when the next message of the user was reached.
     */
    record Entries(List<TranscriptItem> items, boolean more) implements TranscriptRead {
        public Entries {
            items = List.copyOf(items);
        }

        /** The entries of the items, in order. */
        public List<TranscriptEntry> entries() {
            return items.stream().map(TranscriptItem::entry).toList();
        }
    }

    /**
     * The read was to continue after an entry, and that entry is not in the turn of the message: it is not in the transcript at all, or it belongs to an earlier or a later turn.
     *
     * @param entryKey the key the caller named
     */
    record NotInTurn(String entryKey) implements TranscriptRead {
        public NotInTurn {
            Objects.requireNonNull(entryKey, "entryKey");
        }
    }

    /** No implementation reads the transcripts of this host. */
    record Unsupported(String host) implements TranscriptRead {
        public Unsupported {
            Objects.requireNonNull(host, "host");
        }
    }

    /** The file cannot be read, or the position is no longer in it. */
    record Unreadable(UnreadableReason reason) implements TranscriptRead {
        public Unreadable {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
