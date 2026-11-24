// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.util.Objects;

import io.github.jaeseongs95.sennard.app.execution.ExecutionKind;

/**
 * Who an execution is for: what the record of it says and the {@link ProcessRunner} does not know.
 *
 * @param runId the id of the workflow run
 * @param kind the kind of that workflow
 * @param requestedBy the server-issued reference of the session that asked
 */
public record ExecutionOrigin(String runId, ExecutionKind kind, String requestedBy) {
    public ExecutionOrigin {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(requestedBy, "requestedBy");
    }
}
