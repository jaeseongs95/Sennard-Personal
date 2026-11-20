// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

/** What a stage's execution requirement asks of the model's work: a {@code DETERMINISTIC} check, or {@code SEMANTIC} judgment that needs an observed execution context. */
public enum ExecutionKind {
    DETERMINISTIC("deterministic"),
    SEMANTIC("semantic");

    private final String wire;

    ExecutionKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
