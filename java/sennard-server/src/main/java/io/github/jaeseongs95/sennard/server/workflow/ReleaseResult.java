// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.service.store.RootState;
import io.github.jaeseongs95.sennard.service.store.RunState;

/**
 * What {@code release_convergence_root} answers: the released root, how it was released, and the run that was closed with it. {@code closedRun} is the key
 * written as {@code null} when no run was running.
 */
public record ReleaseResult(String schemaVersion, String rootId, long revision, RootState state, ConvergenceRoot.Release release, @JsonInclude(JsonInclude.Include.ALWAYS) ClosedRun closedRun) {

    /** The workflow run a release closed as blocked. */
    public record ClosedRun(String runId, RunState state, long revision) {
    }
}
