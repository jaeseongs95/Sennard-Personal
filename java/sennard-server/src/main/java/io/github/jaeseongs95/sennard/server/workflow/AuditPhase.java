// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

/** The phase a mandatory audit is frozen to ({@code requiredAuditPhase}); the audit skill's own contract spells the wire text. */
public enum AuditPhase {
    PRE_EXECUTION("pre-execution", false),
    POST_EXECUTION("post-execution", true),
    PRE_DEPLOY("pre-deploy", false),
    POST_DEPLOY("post-deploy", true);

    private final String wire;
    private final boolean afterExecution;

    AuditPhase(String wire, boolean afterExecution) {
        this.wire = wire;
        this.afterExecution = afterExecution;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** The audit judges the state after the work ran, so it must say it verified that state. */
    public boolean isAfterExecution() {
        return afterExecution;
    }
}
