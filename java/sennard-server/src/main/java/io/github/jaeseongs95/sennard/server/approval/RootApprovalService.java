// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.approval;

import java.util.List;

/**
 * The workflow side of a root approval: the requests waiting for the user, and the record of the decision. A model cannot reach this: there is
 * no tool for it, and a tool argument never counts as a decision. The admin page behind its own passkey is the only caller. Every method joins
 * the caller's transaction, so a passkey-signed approval and its effect commit together.
 */
public interface RootApprovalService {
    /**
     * One exact authorized tuple, as the page shows it.
     *
     * @param approvalId the waiting request's identifier on the page; the same call asked again keeps it
     * @param action what the user is asked to approve
     * @param scope the current task and frame, as displayed text
     * @param replacementScope the replacement task and frame; null for a release
     */
    record Pending(String approvalId, RootAction action, String rootId, String actorId, String scopeDigest, String scope, String replacementScope, String requestedAt) {
    }

    /** @param kind where the decision was made; {@code recordHash} names the signed record that approved it */
    record Source(ApprovalSourceKind kind, String recordHash, String credentialId) {
    }

    List<Pending> pending();

    /** @return false when the request is no longer waiting (decided by another call first) or its scope no longer has this digest */
    boolean approve(String approvalId, String scopeDigest, Source source);

    /** @return false when the request is no longer waiting */
    boolean reject(String approvalId);
}
