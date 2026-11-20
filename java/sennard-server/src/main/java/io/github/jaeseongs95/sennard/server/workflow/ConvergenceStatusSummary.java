// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.service.store.AttemptState;
import io.github.jaeseongs95.sennard.service.store.LeaseState;

/**
 * The compact form of a convergence status ({@code convergence-status-summary.v1.schema.json}): the root's handle, the attempt budget, the latest ids and
 * counts of what the root holds. The keys that name a latest document or the gate error are written as {@code null} when there is none, because the
 * schema requires them.
 */
public record ConvergenceStatusSummary(String schemaVersion, ConvergenceRootHandle root, long currentEpoch, int maxAttemptsPerEpoch, int maxEpochs, int attemptsUsedInEpoch,
        int attemptsRemainingInEpoch, @JsonInclude(JsonInclude.Include.ALWAYS) String issuedLeaseId, @JsonInclude(JsonInclude.Include.ALWAYS) String latestWorkflowRunId,
        @JsonInclude(JsonInclude.Include.ALWAYS) String latestOutcomeId, @JsonInclude(JsonInclude.Include.ALWAYS) AttemptState latestOutcomeState,
        @JsonInclude(JsonInclude.Include.ALWAYS) String latestOutcomeReceiptDigest, int proposalCount, int leaseCount, int outcomeCount, int reviewCount,
        @JsonInclude(JsonInclude.Include.ALWAYS) String gateErrorCode, int unverifiedClaimCount) {

    /** The summary of a status: its latest documents named, the rest counted. */
    static ConvergenceStatusSummary of(ConvergenceStatus status) {
        String issued = null;
        for (AttemptLease lease : status.leases()) {
            if (lease.state() == LeaseState.ISSUED) {
                issued = lease.leaseId();
            }
        }
        List<String> runs = status.workflowRunIds();
        AttemptOutcome latest = status.outcomes().isEmpty() ? null : status.outcomes().getLast();
        return new ConvergenceStatusSummary(status.schemaVersion(), ConvergenceRootHandle.of(status.root()), status.currentEpoch(), status.maxAttemptsPerEpoch(), status.maxEpochs(),
                status.attemptsUsedInEpoch(), status.attemptsRemainingInEpoch(), issued, runs.isEmpty() ? null : runs.getLast(), latest == null ? null : latest.outcomeId(),
                latest == null ? null : latest.state(), latest == null ? null : latest.receiptDigest(), status.proposals().size(), status.leases().size(), status.outcomes().size(),
                status.reviews().size(), status.gateError() == null ? null : status.gateError().code(), status.unverifiedClaims().size());
    }
}
