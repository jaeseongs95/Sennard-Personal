// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

/** A request that changes a convergence root and may need the user's decision: opening a root that replaces a parent, or releasing a root. */
public sealed interface RootRequest permits OpenConvergenceRootRequest, ReleaseRequest {

    /** The approval records the caller cites as the user's decision, possibly none. */
    List<String> userApprovalRefs();
}
