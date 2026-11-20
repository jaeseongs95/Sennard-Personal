// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.server.approval.RootAction;

/**
 * The exact tuple a user decides on the admin page: which action, on which root, for which actor, over which task and frame. A decision is reused only for the
 * same tuple, so the digest of this record is what an approval binds. {@code replacementDigest} is the key written as {@code null} for a release.
 */
public record RootApprovalScope(RootAction action, String rootId, String actorId, String rootDigest, @JsonInclude(JsonInclude.Include.ALWAYS) String replacementDigest) {
}
