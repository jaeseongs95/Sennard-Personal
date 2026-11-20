// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How strongly a skill's descriptor ({@code gate.policy}) and a planned stage ({@code riskGate}) gate the workflow. The wire text is fixed by the
 * skill descriptors and their own checks, so the constant carries it ({@code @JsonValue}) instead of Jackson's default name.
 */
public enum RiskGate {
    NONE("none"),
    CONDITIONAL("conditional"),
    MANDATORY("mandatory");

    private final String wire;

    RiskGate(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** The stage must carry a gate verdict from a separate auditor before it can pass. */
    public boolean isMandatory() {
        return this == MANDATORY;
    }
}
