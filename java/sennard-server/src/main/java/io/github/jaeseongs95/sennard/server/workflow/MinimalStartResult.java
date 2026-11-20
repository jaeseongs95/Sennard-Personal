// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.app.journal.WorkflowPhase;
import io.github.jaeseongs95.sennard.service.store.RunState;

/**
 * What {@code start_workflow} answers: the run it started, the root and task it belongs to, the stages to run, and what the server filled in. {@code lateAdoption}
 * is written as {@code null} when the session had no edits before the start. {@code changeJournal} says which phase the run is in (see {@link ChangeJournalState}); the
 * start fills it in itself, in the transaction that writes the baseline.
 */
record MinimalStartResult(String schemaVersion, String taskId, String rootId, String runId, long revision, RunState state, List<StageRef> stages, List<String> defaultsUsed,
        @JsonInclude(JsonInclude.Include.ALWAYS) LateAdoption lateAdoption, @JsonInclude(JsonInclude.Include.ALWAYS) ChangeJournalState changeJournal,
        @JsonInclude(JsonInclude.Include.NON_NULL) ClassificationAdvice.StartAdvice advice, WorkflowContinuity.Capacity sessionCapacity) {

    /** A stage of the run, as the caller names it to run it. */
    record StageRef(String stageId, String capability) {
    }

    /** The edits a session made before the start, shown in the model's language with the catalog key of the sentence. */
    record LateAdoption(String label, String messageKey, Map<String, Object> params, int priorFileCount) {
    }

    /**
     * The change journal of the run: its phase, the revision of the latest plan, and the paths of the plan or write roots that could not be read when the baseline was taken
     * (the baseline is not captured while one is unreadable, so the run stays in its planning phase).
     */
    record ChangeJournalState(WorkflowPhase phase, long planRevision, List<String> unreadable) {
    }
}
