// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

/** What kind of thing a stage result's evidence item points at; {@code stage-result.v1.schema.json} spells the wire text. */
public enum EvidenceKind {
    USER_INPUT("user-input"),
    FILE("file"),
    TEST("test"),
    DOCUMENT("document"),
    TOOL("tool");

    private final String wire;

    EvidenceKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
