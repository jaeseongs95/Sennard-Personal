// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.util.Optional;

/**
 * What an upload is for (the design's 3, {@code purpose} of {@code artifact_begin}). {@code UPLOAD} (a transfer to a remote machine) and {@code STAGED} (waiting for a transfer) are bytes
 * that a use, a deletion or an expiry removes; {@code EVIDENCE} is the one that becomes an attachment that is kept. A product keeps the purposes it has a use for: Sennard keeps
 * {@code EVIDENCE} only and refuses the others as ARTIFACT_UNSUPPORTED_PURPOSE; {@code EVIDENCE} is also what a begin without a purpose means, because it is Sennard's main purpose.
 */
public enum ArtifactPurpose {
    UPLOAD("upload", false), STAGED("staged", false), EVIDENCE("evidence", true);

    private final String wire;
    private final boolean supported;

    ArtifactPurpose(String wire, boolean supported) {
        this.wire = wire;
        this.supported = supported;
    }

    /** The word the tool's input and output use. */
    public String wire() {
        return wire;
    }

    /** Whether Sennard keeps uploads of this purpose. */
    public boolean supported() {
        return supported;
    }

    /** The purpose of a word, or nothing for a word that is none of the three. */
    public static Optional<ArtifactPurpose> of(String word) {
        for (ArtifactPurpose purpose : values()) {
            if (purpose.wire.equals(word)) {
                return Optional.of(purpose);
            }
        }
        return Optional.empty();
    }
}
