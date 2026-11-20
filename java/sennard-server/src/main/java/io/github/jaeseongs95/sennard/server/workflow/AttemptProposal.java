// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An attempt proposal ({@code attempt-proposal.v1.schema.json}): what a caller asks a lease for. The task envelope and the frame are records of their own
 * kinds, and so is the plan. A caller may leave out the task envelope and the frame, and then the root's
 * own are filled in ({@link #withTaskAndFrame}); {@code priorFailure} is written as {@code null} when there is none, because the schema requires the key.
 */
record AttemptProposal(String schemaVersion, String rootId, long expectedRevision, TaskEnvelope taskEnvelope, ConvergenceFrame frame, WorkflowPlan plan, List<String> outputTargets,
        @JsonInclude(JsonInclude.Include.ALWAYS) PriorFailure priorFailure) {

    /** The root a proposal names, read from a proposal that has not been checked yet: any other key is left for the schema to judge. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RootReference(String rootId) {
    }

    /** The failure a retry names as the one it answers, with the evidence it cites. */
    record PriorFailure(String fingerprint, String hypothesis, String changeSummary, String discriminator, List<String> evidenceRefs) {
    }

    AttemptProposal withTaskAndFrame(TaskEnvelope task, ConvergenceFrame rootFrame) {
        return new AttemptProposal(schemaVersion, rootId, expectedRevision, task, rootFrame, plan, outputTargets, priorFailure);
    }
}
