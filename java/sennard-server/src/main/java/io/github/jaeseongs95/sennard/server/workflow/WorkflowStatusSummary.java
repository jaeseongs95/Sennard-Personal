// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.service.store.RunState;

/**
 * The compact form of a workflow receipt ({@code workflow-status-summary.v1.schema.json}): where the run is, how many stages it has recorded, how much is open,
 * and the digest of the receipt it summarizes. The keys that name a stage or the error are written as {@code null} when there is none, because the schema
 * requires them; {@code declaredSkillsNotObserved} is left out unless finalize found something to show.
 */
public record WorkflowStatusSummary(String schemaVersion, String runId, long revision, RunState state, @JsonInclude(JsonInclude.Include.ALWAYS) String currentStageId,
        @JsonInclude(JsonInclude.Include.ALWAYS) String nextStageId, @JsonInclude(JsonInclude.Include.ALWAYS) String lastRecordedStageId, int completedStageCount, int totalStageCount, int blockerCount,
        int unresolvedCount, int unverifiedClaimCount, @JsonInclude(JsonInclude.Include.ALWAYS) WorkflowCode errorCode, String receiptDigest,
        @JsonInclude(JsonInclude.Include.NON_NULL) CallsNotObserved declaredSkillsNotObserved, @JsonInclude(JsonInclude.Include.NON_NULL) WorkflowReceipt.Pause pause) {

    /** The summary of a receipt, whose digest the caller has made. */
    static WorkflowStatusSummary of(WorkflowReceipt receipt, String receiptDigest) {
        List<StageResult> results = receipt.stageResults();
        WorkflowPlan plan = receipt.plan();
        CallsNotObserved notObserved = receipt.skillDeclaration() == null ? null : receipt.skillDeclaration().callsNotObserved();
        return new WorkflowStatusSummary(ContractValidator.CONTRACT_VERSION, receipt.runId(), receipt.revision(), receipt.state(), plan.currentStageId(), plan.nextStageId(),
                results.isEmpty() ? null : results.getLast().stageId(), results.size(), plan.stages().size(), receipt.blockers().size(), receipt.unresolved().size(),
                receipt.unverifiedClaims().size(), receipt.error() == null ? null : receipt.error().code(), receiptDigest, notObserved != null && notObserved.worthShowing() ? notObserved : null, receipt.pause());
    }
}
