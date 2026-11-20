// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

/** What an evaluation-validity audit stage is for; the audit skill's own request spells the wire text. */
public enum EvaluationAuditPurpose {
    DESIGN_READINESS("design-readiness"),
    QUALITY_OR_RELEASE("quality-or-release");

    private final String wire;

    EvaluationAuditPurpose(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
