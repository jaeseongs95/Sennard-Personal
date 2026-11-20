// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

/** Where the limits of a new convergence root come from: the user's signed setting, or {@link ConvergenceLimits#DEFAULT} when none is signed. */
public interface ConvergenceLimitSource {
    /** The limits a root opened now is given. A setting that cannot be read is an error, never the default. */
    ConvergenceLimits current();
}
