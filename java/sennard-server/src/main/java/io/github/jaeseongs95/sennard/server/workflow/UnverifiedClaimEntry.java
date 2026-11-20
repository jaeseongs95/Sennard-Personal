// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One property a record relies on that Sennard did not observe ({@code workflow-receipt.v1.schema.json#/$defs/unverifiedClaim}). {@code stageId} is the key the
 * schema requires, null for a claim about a convergence root; {@code artifactId} is written only for a claim about one artifact.
 */
public record UnverifiedClaimEntry(@JsonInclude(JsonInclude.Include.ALWAYS) String stageId, UnverifiedClaim claim, @JsonInclude(JsonInclude.Include.NON_NULL) String artifactId) {
}
