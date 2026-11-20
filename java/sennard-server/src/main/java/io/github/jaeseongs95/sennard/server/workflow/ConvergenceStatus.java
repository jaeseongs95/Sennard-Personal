// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.server.contract.ApiResult;

/**
 * What {@code get_convergence_status} answers ({@code convergence-status.v1.schema.json}): the root, how many of its attempts are used, and every document
 * the root holds. {@code gateError} is the key the schema requires, null when the root is not gated.
 */
public record ConvergenceStatus(String schemaVersion, ConvergenceRoot root, long currentEpoch, int maxAttemptsPerEpoch, int maxEpochs, int attemptsUsedInEpoch, int attemptsRemainingInEpoch,
        List<AttemptProposal> proposals, List<AttemptLease> leases, List<AttemptOutcome> outcomes, List<ConvergenceReview> reviews, List<String> workflowRunIds,
        @JsonInclude(JsonInclude.Include.ALWAYS) ApiResult.ErrorBody gateError, List<UnverifiedClaimEntry> unverifiedClaims) {
}
