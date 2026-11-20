// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

import io.github.jaeseongs95.sennard.service.store.RootState;

/**
 * Where an independent frame review sends a convergence root. The review skill's own contract spells the wire text; the constant holds the
 * root state it leaves the root in ({@code null} for {@code RESUME_NEW_EPOCH}, whose state depends on the epoch budget).
 */
public enum ReviewRoute {
    RESUME_NEW_EPOCH("resume-new-epoch", null),
    DIAGNOSE("diagnose", RootState.NEEDS_REVIEW),
    PANEL("panel", RootState.NEEDS_REVIEW),
    NEEDS_USER("needs-user", RootState.NEEDS_USER),
    STOP("stop", RootState.NEEDS_USER);

    private final String wire;
    private final RootState resultingState;

    ReviewRoute(String wire, RootState resultingState) {
        this.wire = wire;
        this.resultingState = resultingState;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** The state the root is left in, or null when the route does not decide it alone. */
    public RootState resultingState() {
        return resultingState;
    }
}
