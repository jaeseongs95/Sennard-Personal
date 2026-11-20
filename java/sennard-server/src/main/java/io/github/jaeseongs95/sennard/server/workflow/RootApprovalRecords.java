// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.server.approval.ApprovalSourceKind;

/**
 * The two JSON documents a root approval is kept in. The store keeps their text; {@code StoreRootApprovalService} reads the pending request for the admin
 * page ({@code scope}, {@code currentScope}, {@code replacementScope}) and writes the decision around the scope it carries.
 */
final class RootApprovalRecords {
    private RootApprovalRecords() {
    }

    /**
     * A request waiting for the user: the scope to decide, what the user is shown, and the request that is run once it is approved. {@code replacementScope} is
     * left out for a release.
     *
     * @param currentScope the canonical text of what the root holds now
     * @param replacementScope the canonical text of what would replace it
     */
    record Pending(String scopeDigest, RootApprovalScope scope, long rootRevision, RootRequest request, String message, String currentScope,
            @JsonInclude(JsonInclude.Include.NON_NULL) String replacementScope, String requestedBy) {
    }

    /**
     * A decision made on the admin page for one scope: written once by {@code StoreRootApprovalService} (or, for a release the user signs without a waiting request, by
     * {@code WorkflowService.releaseByAdmin}), never by a tool argument. {@code revokedAt} is null until it is revoked, and the key is there all the same.
     */
    record Decision(String approvalId, RootApprovalScope scope, String scopeDigest, Source source, Instant createdAt, @JsonInclude(JsonInclude.Include.ALWAYS) Instant revokedAt) {
    }

    /** Where the decision was made and the signed record that approved it. */
    record Source(ApprovalSourceKind kind, String recordHash, String credentialId) {
    }
}
