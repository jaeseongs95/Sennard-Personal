// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The input of {@code start_guarded_workflow} ({@code guarded-workflow-start-request.v1.schema.json}): the lease a claim issued, the root revision it was issued
 * at, and the plan the lease is bound to. A caller may leave out the plan, and then the plan of the lease's proposal is used; a request bound from such an input
 * has a null plan. {@code skills} and {@code classificationOperationKey} are optional.
 */
record GuardedStartRequest(String schemaVersion, String leaseId, long expectedRootRevision, WorkflowPlan plan, @JsonInclude(JsonInclude.Include.NON_NULL) List<SkillDeclaration> skills,
        @JsonInclude(JsonInclude.Include.NON_NULL) String classificationOperationKey) {

    /** The lease a request names, read from a request that has not been checked yet: any other key is left for the schema to judge. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LeaseReference(String leaseId) {
    }

    /** The request with the plan of the lease's proposal in place of the one the caller left out. */
    GuardedStartRequest withPlan(WorkflowPlan issuedPlan) {
        return new GuardedStartRequest(schemaVersion, leaseId, expectedRootRevision, issuedPlan, skills, classificationOperationKey);
    }
}
