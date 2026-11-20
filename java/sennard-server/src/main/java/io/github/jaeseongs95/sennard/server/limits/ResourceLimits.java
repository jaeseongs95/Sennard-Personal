// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.limits;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Server limits for CS bundle files and concurrently open workflows in a session. */
public record ResourceLimits(@Min(ResourceLimits.MIN_CS_ARTIFACT_BYTES) @Max(ResourceLimits.MAX_CS_ARTIFACT_BYTES) int csArtifactMaxBytes,
        @Min(1) int maxOpenWorkflowsPerSession) {
    public static final int MIN_CS_ARTIFACT_BYTES = 1024 * 1024;
    public static final int MAX_CS_ARTIFACT_BYTES = 64 * 1024 * 1024;
    public static final ResourceLimits DEFAULT = new ResourceLimits(8 * 1024 * 1024, 5);
}
