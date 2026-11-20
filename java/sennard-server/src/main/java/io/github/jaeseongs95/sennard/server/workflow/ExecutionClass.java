// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The class of workflow a skill's descriptor says it runs in ({@code executionClass}). A {@code BOOTSTRAP} skill never enters a plan; plans do
 * not mix {@code WORKFLOW} and {@code RECOVERY} stages. The wire text is fixed by the skill descriptors.
 */
public enum ExecutionClass {
    BOOTSTRAP("bootstrap"),
    WORKFLOW("workflow"),
    RECOVERY("recovery");

    private final String wire;

    ExecutionClass(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
