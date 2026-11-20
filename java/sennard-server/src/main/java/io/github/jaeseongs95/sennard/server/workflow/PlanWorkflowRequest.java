// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The wrapper form of the input of {@code plan_workflow} ({@code plan-workflow-request.v1.schema.json}): the task, and what only the wrapper can carry. A
 * caller that sends a bare task envelope is read as this record with the schema version, the purpose and the phase left out.
 *
 * @param evaluationAuditPurpose why an evaluation-validity audit is planned; required when the task asks for that capability
 * @param requiredAuditPhase when a mandatory audit judges the work; the plan defaults it to post-execution
 */
public record PlanWorkflowRequest(@JsonInclude(JsonInclude.Include.NON_NULL) String schemaVersion, TaskEnvelope taskEnvelope,
        @JsonInclude(JsonInclude.Include.NON_NULL) EvaluationAuditPurpose evaluationAuditPurpose, @JsonInclude(JsonInclude.Include.NON_NULL) AuditPhase requiredAuditPhase) {

    /** A bare task envelope as a request: no purpose and no phase were named. */
    static PlanWorkflowRequest bare(TaskEnvelope task) {
        return new PlanWorkflowRequest(null, task, null, null);
    }
}
