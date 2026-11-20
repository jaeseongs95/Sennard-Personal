// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import jakarta.validation.constraints.Min;

/**
 * How far a convergence root may go: the full attempts one epoch allows and the epochs the root may open. Both are at least 1 and have no upper bound. The user sets
 * them on the admin page and a root opened afterwards takes them into its frame ({@link ConvergenceFrame.OperationalSettings}); a root that is already open keeps the
 * values it was opened with.
 */
public record ConvergenceLimits(@Min(1) int maxAttemptsPerEpoch, @Min(1) int maxEpochs) {
    /** What a root gets while the user has set nothing. */
    public static final ConvergenceLimits DEFAULT = new ConvergenceLimits(3, 2);
}
