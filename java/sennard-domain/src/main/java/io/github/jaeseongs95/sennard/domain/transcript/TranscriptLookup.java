// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.transcript;

import java.util.Objects;

/** The answer of {@link Transcript#locate}: where the message is, or why that is not known. */
public sealed interface TranscriptLookup {
    /** The entry of the message was found. */
    record Located(TranscriptLocation location) implements TranscriptLookup {
        public Located {
            Objects.requireNonNull(location, "location");
        }
    }

    /** The file is there but the entry of the message is not in it yet (the host writes the entry after the hook ran, or has not flushed it). A later call may find it. */
    record NotYetWritten() implements TranscriptLookup {
    }

    /** No implementation reads the transcripts of this host. */
    record Unsupported(String host) implements TranscriptLookup {
        public Unsupported {
            Objects.requireNonNull(host, "host");
        }
    }

    /** The file cannot be read: it is not there, it is not allowed, or it is not in the format of the host. */
    record Unreadable(UnreadableReason reason) implements TranscriptLookup {
        public Unreadable {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
