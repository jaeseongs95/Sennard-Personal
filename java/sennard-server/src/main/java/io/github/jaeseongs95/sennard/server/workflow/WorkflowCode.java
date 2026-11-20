// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

/**
 * The public error codes of the workflow tools: the {@code error.code} of a failed result. A closed set, so a code is a constant (a typo does not compile) and
 * a decision over a code is a {@code switch} the compiler checks. The wire text is the constant's name, which is also Jackson's own form of an enum.
 */
public enum WorkflowCode {
    INVALID_INPUT,
    RUN_NOT_FOUND,
    STALE_REVISION,
    INVALID_TRANSITION,
    MISSING_EVIDENCE,
    GATE_FAILED,
    MCP_UNAVAILABLE,
    LEASE_REQUIRED,
    LEASE_CONFLICT,
    FRAME_REVIEW_REQUIRED,
    ATTEMPT_BUDGET_EXHAUSTED,
    NEW_EVIDENCE_REQUIRED,
    ROOT_CONFLICT,
    CONTINUITY_UNAVAILABLE,
    BINDING_REQUIRED,
    BINDING_INVALID,
    SNAPSHOT_NOT_FOUND,
    SNAPSHOT_CONFLICT,
    REQUEST_CONFLICT,
    INTEGRITY_FAILED
}
