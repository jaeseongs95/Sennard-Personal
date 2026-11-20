// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.EnumSet;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What an independent frame review found a proposed change does to the task's meaning. The review skill's own contract spells the wire text.
 * The classification decides which routes the review may take.
 */
public enum ReviewClassification {
    SEMANTICS_PRESERVING("semantics-preserving"),
    SEMANTICS_CHANGING("semantics-changing"),
    AMBIGUOUS("ambiguous");

    private final String wire;

    ReviewClassification(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** The routes a review of this classification may take: a preserving change resumes or diagnoses, a changing one goes to the user, an unclear one to a panel, the user or a stop. */
    public Set<ReviewRoute> allowedRoutes() {
        return switch (this) {
            case SEMANTICS_PRESERVING -> EnumSet.of(ReviewRoute.RESUME_NEW_EPOCH, ReviewRoute.DIAGNOSE, ReviewRoute.STOP);
            case SEMANTICS_CHANGING -> EnumSet.of(ReviewRoute.NEEDS_USER);
            case AMBIGUOUS -> EnumSet.of(ReviewRoute.PANEL, ReviewRoute.NEEDS_USER, ReviewRoute.STOP);
        };
    }
}
