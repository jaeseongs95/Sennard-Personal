// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

/** Where an execution context's model facts came from: the host's {@code RUNTIME} record or the {@code SPAWN_RESULT} of a delegated call. The execution context contract spells the wire text. */
public enum ExecutionSource {
    RUNTIME("runtime"),
    SPAWN_RESULT("spawn-result");

    private final String wire;

    ExecutionSource(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
