// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whether the session that owns a convergence root is there: {@code ONLINE}, {@code UNREACHABLE} (listed but not answering), {@code ENDED}, or
 * {@code UNKNOWN} (presence could not be read, which is never taken as offline). The wire text is what the conflict messages select on.
 */
public enum PresenceState {
    ONLINE("online"),
    UNREACHABLE("unreachable"),
    ENDED("ended"),
    UNKNOWN("unknown");

    private final String wire;

    PresenceState(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
