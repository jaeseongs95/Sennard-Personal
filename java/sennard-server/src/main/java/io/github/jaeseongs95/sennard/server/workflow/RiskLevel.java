// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

/** The risk a task envelope declares ({@code riskLevel}); the task contract spells the wire text. {@code HIGH} and {@code CRITICAL} make a plan carry the mandatory audit. */
public enum RiskLevel {
    LOW("low", false),
    MEDIUM("medium", false),
    HIGH("high", true),
    CRITICAL("critical", true);

    private final String wire;
    private final boolean needsAudit;

    RiskLevel(String wire, boolean needsAudit) {
        this.wire = wire;
        this.needsAudit = needsAudit;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** A plan for a task of this risk always includes the independent audit stage. */
    public boolean needsAudit() {
        return needsAudit;
    }
}
