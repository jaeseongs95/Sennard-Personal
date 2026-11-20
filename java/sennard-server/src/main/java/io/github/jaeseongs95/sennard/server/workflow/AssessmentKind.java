// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

/** The kind a skill's stage check names in its assessment; each skill's own report spells the wire text. */
enum AssessmentKind {
    CS_BUNDLE_CHECK("cs-bundle-check"),
    ENGINEERING_STAGE_CHECK("engineering-stage-check");

    private final String wire;

    AssessmentKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    String wire() {
        return wire;
    }
}
