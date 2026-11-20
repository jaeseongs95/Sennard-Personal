// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import io.github.jaeseongs95.sennard.service.store.RootState;

/** What a compact answer names a convergence root by ({@code convergence-root-handle.v1.schema.json}): its identity, state and the digests of its inputs, without the documents. */
public record ConvergenceRootHandle(String schemaVersion, String rootId, long revision, RootState state, long currentEpoch, String taskDigest, String frameDigest, String workspaceDigest,
        String controlDigest, String targetDigest, String operationalDigest) {

    static ConvergenceRootHandle of(ConvergenceRoot root) {
        return new ConvergenceRootHandle(root.schemaVersion(), root.rootId(), root.revision(), root.state(), root.currentEpoch(), root.taskDigest(), root.frameDigest(), root.workspaceDigest(),
                root.controlDigest(), root.targetDigest(), root.operationalDigest());
    }
}
