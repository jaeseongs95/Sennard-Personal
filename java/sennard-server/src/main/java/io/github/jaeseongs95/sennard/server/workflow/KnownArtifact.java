// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

/**
 * The artifacts the engine itself has to recognise, by the ID the providing skill writes. An artifact ID is a skill's own name and the set of them is open: every other ID
 * passes through as the text it is.
 */
enum KnownArtifact {
    /** The artifact an audit stage must produce to carry its verdict. */
    GATE_VERDICT("gate-verdict"),
    /** The frozen request an acceptance-evidence stage verifies. */
    ACCEPTANCE_EVIDENCE_REQUEST("acceptance-evidence-request");

    private final String wire;

    KnownArtifact(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }

    /** Whether the artifact ID, as a stage result writes it, is this one. */
    boolean is(String artifactId) {
        return wire.equals(artifactId);
    }
}
