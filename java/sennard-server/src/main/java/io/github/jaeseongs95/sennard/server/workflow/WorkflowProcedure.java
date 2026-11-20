// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.Optional;

/** The procedures available to a request. A transition acts on an existing run or root. */
public enum WorkflowProcedure {
    INQUIRY(WorkflowKind.INQUIRY),
    CHANGE(WorkflowKind.CHANGE),
    EXECUTION(WorkflowKind.OPERATION),
    TRANSITION(null);

    private final WorkflowKind startKind;

    WorkflowProcedure(WorkflowKind startKind) {
        this.startKind = startKind;
    }

    /** The workflow kind this procedure can open when there is no suitable workflow to reuse. */
    public Optional<WorkflowKind> startKind() {
        return Optional.ofNullable(startKind);
    }
}
