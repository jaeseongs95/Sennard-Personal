// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

/** The input of {@code resolve_convergence_gate} ({@code resolve-convergence-gate-request.v1.schema.json}): the review that resolves a gated root, and the revision it was made against. */
public record ResolveConvergenceGateRequest(String schemaVersion, String rootId, long expectedRevision, ConvergenceReview review) {
}
