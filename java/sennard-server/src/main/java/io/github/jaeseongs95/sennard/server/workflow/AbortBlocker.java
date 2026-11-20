// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.Collection;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A blocker Sennard itself writes into a run it closes without a stage verdict: the caller {@code ABORTED_BY_CALLER}, or the run's convergence root was
 * {@code ROOT_RELEASED}. The wire text is what the receipts and the stored runs already carry.
 */
enum AbortBlocker {
    ABORTED_BY_CALLER("aborted-by-caller"),
    ROOT_RELEASED("root-released");

    private final String wire;

    AbortBlocker(String wire) {
        this.wire = wire;
    }

    @JsonValue
    String wire() {
        return wire;
    }

    /** True when a run's blockers include one Sennard wrote to close it. */
    static boolean anyIn(Collection<String> blockers) {
        for (AbortBlocker blocker : values()) {
            if (blockers.contains(blocker.wire)) {
                return true;
            }
        }
        return false;
    }
}
