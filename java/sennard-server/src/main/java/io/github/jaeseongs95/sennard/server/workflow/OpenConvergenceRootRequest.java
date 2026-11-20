// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The input of {@code open_convergence_root} ({@code open-convergence-root-request.v1.schema.json}): the task and the frame of a new root, and the root it
 * replaces. {@code parentRootId} is the key the schema requires, null for a root that replaces none; a caller that sends no
 * {@code userApprovalRefs} cites none.
 */
public record OpenConvergenceRootRequest(String schemaVersion, @JsonInclude(JsonInclude.Include.ALWAYS) String parentRootId, TaskEnvelope taskEnvelope, ConvergenceFrame frame,
        List<String> userApprovalRefs) implements RootRequest {

    public OpenConvergenceRootRequest {
        userApprovalRefs = userApprovalRefs == null ? List.of() : userApprovalRefs;
    }
}
