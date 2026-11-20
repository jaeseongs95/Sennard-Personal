// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

/**
 * A property a workflow record relies on that Sennard did not observe, listed in the receipt's {@code unverifiedClaims} by the constant's name so the
 * record says so instead of presenting a declaration as verified.
 */
public enum UnverifiedClaim {
    ACTOR_INDEPENDENCE,
    FRESH_CONTEXT,
    NO_REDELEGATION,
    POST_EXECUTION_STATE,
    PANEL_INDEPENDENCE,
    CONDITIONS_VERIFIED,
    WORKER_EXECUTION_PROFILE,
    EXECUTION_PROFILE,
    EVIDENCE_CONTENT,
    EVIDENCE_RELEVANCE,
    USER_APPROVAL,
    REVIEWER_INDEPENDENCE
}
