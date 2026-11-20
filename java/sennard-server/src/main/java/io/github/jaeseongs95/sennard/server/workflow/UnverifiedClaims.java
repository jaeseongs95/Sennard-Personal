// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Properties a record relies on that Sennard did not observe; the record says so instead of presenting a declaration as verified. */
final class UnverifiedClaims {
    /** The capability of the iteration-frame audit skill; a capability is a skill's own name, not a closed set. */
    static final String ITERATION_FRAME_AUDIT_CAPABILITY = "iteration-frame-audit";

    private UnverifiedClaims() {
    }

    static List<String> externalInputs(WorkflowPlan plan, WorkflowPlan.Stage stage) {
        String stageId = stage.stageId();
        List<String> out = new ArrayList<>();
        for (String artifactId : stage.requiredInputArtifacts()) {
            if (plan.stages().stream().noneMatch(candidate -> !Objects.equals(candidate.stageId(), stageId) && candidate.producedArtifacts().contains(artifactId))) {
                out.add(artifactId);
            }
        }
        return out;
    }

    /**
     * What a passed stage result relies on without Sennard having observed it, each claim once.
     *
     * @param conditionalConsensus the stage's decision record concluded with a consensus on conditions
     */
    static List<UnverifiedClaimEntry> forStage(WorkflowPlan.Stage stage, StageResult result, boolean executionObserved, List<String> externalInputs, boolean independenceVerified,
            boolean conditionalConsensus) {
        if (!result.passed()) {
            return List.of();
        }
        String stageId = stage.stageId();
        List<String> required = stage.requiredArtifacts();
        List<String> capabilities = stage.satisfiedCapabilities();
        List<UnverifiedClaim> claims = new ArrayList<>();
        if (StageGates.mandatoryAudit(stage)) {
            if (!independenceVerified) {
                claims.addAll(List.of(UnverifiedClaim.ACTOR_INDEPENDENCE, UnverifiedClaim.NO_REDELEGATION));
            }
            claims.addAll(List.of(UnverifiedClaim.FRESH_CONTEXT, UnverifiedClaim.WORKER_EXECUTION_PROFILE));
            AuditPhase phase = stage.requiredAuditPhase();
            if (phase != null && phase.isAfterExecution()) {
                claims.add(UnverifiedClaim.POST_EXECUTION_STATE);
            }
        }
        if (capabilities.contains(ITERATION_FRAME_AUDIT_CAPABILITY)) {
            if (!independenceVerified) {
                claims.add(UnverifiedClaim.ACTOR_INDEPENDENCE);
            }
            claims.addAll(List.of(UnverifiedClaim.FRESH_CONTEXT, UnverifiedClaim.WORKER_EXECUTION_PROFILE));
        }
        if (required.contains(StageGates.DECISION_RECORD_ARTIFACT)) {
            claims.addAll(List.of(UnverifiedClaim.PANEL_INDEPENDENCE, UnverifiedClaim.WORKER_EXECUTION_PROFILE));
            if (conditionalConsensus) {
                claims.add(UnverifiedClaim.CONDITIONS_VERIFIED);
            }
        }
        StageReceiptPolicy policy = stage.receiptPolicy();
        if (policy != null && (policy.actorIdPointer() != null || policy.actorIdsPointer() != null)) {
            claims.add(UnverifiedClaim.ACTOR_INDEPENDENCE);
        }
        if (stage.executionRequirement() != null && stage.executionRequirement().semantic() && result.executionContext() != null && !executionObserved) {
            claims.add(UnverifiedClaim.EXECUTION_PROFILE);
        }
        List<UnverifiedClaimEntry> entries = new ArrayList<>();
        claims.forEach(name -> entries.add(new UnverifiedClaimEntry(stageId, name, null)));
        Set<String> matched = new LinkedHashSet<>();
        if (result.artifactChecks() != null) {
            result.artifactChecks().stream().filter(check -> check.check() == ArtifactCheck.SHA256_MATCHED).forEach(check -> matched.add(check.artifactId()));
        }
        Set<String> needed = new LinkedHashSet<>(required);
        needed.addAll(externalInputs);
        for (String artifactId : needed) {
            if (!matched.contains(artifactId)) {
                entries.add(new UnverifiedClaimEntry(stageId, UnverifiedClaim.EVIDENCE_CONTENT, artifactId));
            }
        }
        return List.copyOf(new LinkedHashSet<>(entries));
    }

    /** What a convergence root's record relies on without Sennard having observed it: the user's approval of a replacement, the reviewers' independence and fresh context, the relevance of the evidence a retry cites. */
    static List<UnverifiedClaimEntry> forConvergence(ConvergenceRoot root, List<ConvergenceReview> reviews, List<AttemptLease> leases) {
        List<UnverifiedClaimEntry> entries = new ArrayList<>();
        if (root.parentRootId() != null) {
            entries.add(new UnverifiedClaimEntry(null, UnverifiedClaim.USER_APPROVAL, null));
        }
        if (reviews.stream().anyMatch(review -> review.observedReviewer() == null || review.observedReviewer().independence() != Independence.VERIFIED)) {
            entries.add(new UnverifiedClaimEntry(null, UnverifiedClaim.REVIEWER_INDEPENDENCE, null));
        }
        if (!reviews.isEmpty()) {
            entries.add(new UnverifiedClaimEntry(null, UnverifiedClaim.FRESH_CONTEXT, null));
        }
        if (leases.stream().anyMatch(lease -> !lease.priorEvidenceDigests().isEmpty())) {
            entries.add(new UnverifiedClaimEntry(null, UnverifiedClaim.EVIDENCE_RELEVANCE, null));
        }
        return entries;
    }
}
