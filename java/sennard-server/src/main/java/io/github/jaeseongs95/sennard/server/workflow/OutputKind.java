// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

/** The kind of a provider's result: its {@code OUTPUT}, or an {@code ADAPTER_ERROR} the adapter reports instead. The providers' own result schema spells the wire text. */
public enum OutputKind {
    OUTPUT("output"),
    ADAPTER_ERROR("adapter-error");

    private final String wire;

    OutputKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
