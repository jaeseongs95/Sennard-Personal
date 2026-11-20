// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.service.store.RunState;

/**
 * The receipt of a workflow run ({@code workflow-receipt.v1.schema.json}): the plan as the run has moved it, the result of each stage recorded so far, and what
 * is still open. It is what {@code get_workflow_status} and the stage tools answer and what the store keeps as the run's text; the contract schema is checked
 * once, on the receipt that is about to be stored. A change is a new receipt ({@code with…}), and each change of a run is one revision.
 *
 * <p>The receipt's digest is made over this JSON, so the keys are those the receipt always had: {@code error} as {@code null} when there is none (the schema
 * requires the key) and {@code skillDeclaration} only for a run that was started with skills.
 */
public record WorkflowReceipt(String schemaVersion, String runId, long revision, RunState state, WorkflowPlan plan, List<StageResult> stageResults, List<String> blockers, List<String> unresolved,
        @JsonInclude(JsonInclude.Include.ALWAYS) RecordedError error, List<UnverifiedClaimEntry> unverifiedClaims, @JsonInclude(JsonInclude.Include.NON_NULL) SkillDeclaration.Recorded skillDeclaration,
        @JsonInclude(JsonInclude.Include.NON_NULL) Pause pause) {

    /** A deliberate hold of this run; no stage result or attempt outcome is implied. */
    public record Pause(String reason, String resumeCondition, Instant pausedAt) {
    }

    /** The previous receipt shape remains readable by source consumers and old stored receipts. */
    public WorkflowReceipt(String schemaVersion, String runId, long revision, RunState state, WorkflowPlan plan, List<StageResult> stageResults, List<String> blockers, List<String> unresolved,
            RecordedError error, List<UnverifiedClaimEntry> unverifiedClaims, SkillDeclaration.Recorded skillDeclaration) {
        this(schemaVersion, runId, revision, state, plan, stageResults, blockers, unresolved, error, unverifiedClaims, skillDeclaration, null);
    }

    /** The receipt of a run that has just started: running, at revision 0, with nothing recorded. */
    static WorkflowReceipt started(String runId, WorkflowPlan plan, SkillDeclaration.Recorded declaration) {
        return new WorkflowReceipt(ContractValidator.CONTRACT_VERSION, runId, 0, RunState.RUNNING, plan, List.of(), List.of(), List.of(), null, List.of(), declaration);
    }

    /** The result recorded for this stage, or none when none is. */
    Optional<StageResult> resultOf(String stageId) {
        return stageResults.stream().filter(result -> Objects.equals(stageId, result.stageId())).findFirst();
    }

    WorkflowReceipt withRevision(long other) {
        return new WorkflowReceipt(schemaVersion, runId, other, state, plan, stageResults, blockers, unresolved, error, unverifiedClaims, skillDeclaration, pause);
    }

    WorkflowReceipt withState(RunState other) {
        return new WorkflowReceipt(schemaVersion, runId, revision, other, plan, stageResults, blockers, unresolved, error, unverifiedClaims, skillDeclaration, other == RunState.PAUSED ? pause : null);
    }

    WorkflowReceipt withPlan(WorkflowPlan other) {
        return new WorkflowReceipt(schemaVersion, runId, revision, state, other, stageResults, blockers, unresolved, error, unverifiedClaims, skillDeclaration, pause);
    }

    WorkflowReceipt withError(RecordedError other) {
        return new WorkflowReceipt(schemaVersion, runId, revision, state, plan, stageResults, blockers, unresolved, other, unverifiedClaims, skillDeclaration, pause);
    }

    WorkflowReceipt withSkillDeclaration(SkillDeclaration.Recorded other) {
        return new WorkflowReceipt(schemaVersion, runId, revision, state, plan, stageResults, blockers, unresolved, error, unverifiedClaims, other, pause);
    }

    WorkflowReceipt withPause(Pause other) {
        return new WorkflowReceipt(schemaVersion, runId, revision, state, plan, stageResults, blockers, unresolved, error, unverifiedClaims, skillDeclaration, other);
    }

    /** The receipt with one more stage result. */
    WorkflowReceipt withStageResult(StageResult result) {
        List<StageResult> all = new ArrayList<>(stageResults);
        all.add(result);
        return new WorkflowReceipt(schemaVersion, runId, revision, state, plan, List.copyOf(all), blockers, unresolved, error, unverifiedClaims, skillDeclaration, pause);
    }

    /** The receipt with these claims added to the ones it has, each claim once. */
    WorkflowReceipt withClaims(Collection<UnverifiedClaimEntry> added) {
        List<UnverifiedClaimEntry> all = new ArrayList<>(unverifiedClaims);
        all.addAll(added);
        return new WorkflowReceipt(schemaVersion, runId, revision, state, plan, stageResults, blockers, unresolved, error, List.copyOf(new LinkedHashSet<>(all)), skillDeclaration, pause);
    }

    /** The receipt with these texts added to its blockers and to what is unresolved, each text once and in the order given. */
    WorkflowReceipt withOpen(Collection<String> blocked, Collection<String> open) {
        return new WorkflowReceipt(schemaVersion, runId, revision, state, plan, stageResults, withEach(blockers, blocked), withEach(unresolved, open), error, unverifiedClaims, skillDeclaration, pause);
    }

    /** The receipt with these texts added to both its blockers and what is unresolved. */
    WorkflowReceipt withBlocked(Collection<String> texts) {
        return withOpen(texts, texts);
    }

    /** The list with the values it does not hold yet, in order. */
    private static List<String> withEach(List<String> list, Collection<String> values) {
        List<String> out = new ArrayList<>(list);
        for (String value : values) {
            if (!out.contains(value)) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    /** True when something blocks the run or is unresolved. */
    boolean isOpen() {
        return !blockers.isEmpty() || !unresolved.isEmpty();
    }
}
