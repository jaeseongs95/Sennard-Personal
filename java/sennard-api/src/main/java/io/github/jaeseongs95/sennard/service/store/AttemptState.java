// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store;

/** The state of a convergence attempt ({@code convergence_attempts.state}); the outcome a run leaves is any of them but {@link #RUNNING}. */
public enum AttemptState {
    RUNNING,
    PASSED,
    FAILED,
    ABORTED;

    /** The attempt has an outcome. */
    public boolean isOutcome() {
        return this != RUNNING;
    }
}
