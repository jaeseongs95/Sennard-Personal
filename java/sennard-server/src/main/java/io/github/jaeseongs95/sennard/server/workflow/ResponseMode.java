// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

/** How much of an answer a workflow tool returns; the response mode contract spells the wire text. */
enum ResponseMode {
    COMPACT("compact"),
    FULL("full");

    private final String wire;

    ResponseMode(String wire) {
        this.wire = wire;
    }

    @JsonValue
    String wire() {
        return wire;
    }

    /** The mode a tool argument names by its wire text; null when the argument is not text or names none. */
    static ResponseMode of(Object argument) {
        for (ResponseMode mode : values()) {
            if (mode.wire.equals(argument)) {
                return mode;
            }
        }
        return null;
    }
}
