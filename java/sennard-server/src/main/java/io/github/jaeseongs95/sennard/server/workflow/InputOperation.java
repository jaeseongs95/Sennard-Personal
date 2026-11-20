// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

/** What a skill descriptor's input binding does with the artifact it names ({@code operation}); {@code REQUIRE_EXTERNAL} wants it from outside the run. The wire text is fixed by the descriptors. */
public enum InputOperation {
    SELECT("select"),
    COLLECT("collect"),
    COMBINE("combine"),
    REQUIRE_EXTERNAL("require-external");

    private final String wire;

    InputOperation(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
