// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

/**
 * The calling actor as the server observed it, the form a review and a stage result record: who called, the actors above them, and how Sennard came to know
 * ({@link Provenance}). Nothing in it comes from tool arguments.
 */
public record ObservedCaller(String actor, List<String> parentChain, Provenance provenance) {

    /** The host supplied this identity itself, so the caller cannot have forged it. */
    boolean hostObserved() {
        return provenance == Provenance.HOST_OBSERVED;
    }
}
