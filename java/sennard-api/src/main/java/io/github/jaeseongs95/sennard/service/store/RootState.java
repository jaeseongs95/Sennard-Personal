// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store;

/**
 * The state of a convergence root ({@code convergence_roots.state}, the root document's {@code state}). A root is active until it is
 * completed or abandoned; the two gated states wait for a frame review or for the user.
 */
public enum RootState {
    OPEN(true, false),
    NEEDS_REVIEW(true, true),
    NEEDS_USER(true, true),
    COMPLETED(false, false),
    ABANDONED(false, false);

    private final boolean active;
    private final boolean gated;

    RootState(boolean active, boolean gated) {
        this.active = active;
        this.gated = gated;
    }

    /** The root still holds its write targets: it is neither completed nor abandoned. */
    public boolean isActive() {
        return active;
    }

    /** The root waits for a review or the user to resolve its gate. */
    public boolean isGated() {
        return gated;
    }
}
