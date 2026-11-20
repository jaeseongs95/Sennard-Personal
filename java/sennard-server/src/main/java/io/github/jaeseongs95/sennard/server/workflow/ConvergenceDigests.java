// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.Objects;

/**
 * The digests a convergence root keeps of its inputs: the frame as a whole, its four parts, and the task. A change to the task or to any frame part but
 * the targets is what sends a root to review.
 */
public record ConvergenceDigests(String frameDigest, String workspaceDigest, String controlDigest, String targetDigest, String operationalDigest, String taskDigest) {

    /** The task, the workspace, the control artifacts or the operating limits differ: the frame's meaning changed, not only its targets. */
    boolean changesMeaningOf(ConvergenceDigests baseline) {
        return !Objects.equals(taskDigest, baseline.taskDigest) || !Objects.equals(workspaceDigest, baseline.workspaceDigest) || !Objects.equals(controlDigest, baseline.controlDigest)
                || !Objects.equals(operationalDigest, baseline.operationalDigest);
    }
}
