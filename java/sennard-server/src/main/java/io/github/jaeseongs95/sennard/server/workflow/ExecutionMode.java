// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

/** Whether a plan is run stage by stage through Sennard ({@code ORCHESTRATED}) or only recorded ({@code DIRECT}); the plan contract spells the wire text. */
public enum ExecutionMode {
    DIRECT("direct"),
    ORCHESTRATED("orchestrated");

    private final String wire;

    ExecutionMode(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
