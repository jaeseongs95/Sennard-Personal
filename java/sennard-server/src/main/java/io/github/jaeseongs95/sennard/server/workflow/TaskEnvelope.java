// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A task envelope ({@code task-envelope.v1.schema.json}): what a caller asks for, with its scope, acceptance criteria, risk and authorization. The contract
 * schema is checked at the edges (the caller's input, and the document written out); inside, the envelope is this record, and a convergence root, an attempt
 * proposal and a plan read it as one.
 *
 * <p>{@code artifactContracts} is optional in the schema and written only when there is one. {@code taskId} is written whenever it is set; it is null only in
 * {@link #withoutTaskId()}, the form a user approval binds, where a generated task ID is not part of what was approved.
 */
public record TaskEnvelope(String schemaVersion, @JsonInclude(JsonInclude.Include.NON_NULL) String taskId, String objective, Scope scope, List<String> acceptanceCriteria, RiskLevel riskLevel,
        List<WorkUnit> workUnits, @JsonInclude(JsonInclude.Include.NON_NULL) List<ArtifactContract> artifactContracts, List<String> requiredCapabilities, List<String> constraints,
        Authorization authorization, Decision decision, Orchestration orchestration) {

    /** What the task may change ({@code included}) and what it must leave alone. */
    public record Scope(List<String> included, List<String> excluded) {
    }

    /** One unit of work, the units it waits for and the paths it writes. */
    public record WorkUnit(String id, String objective, List<String> dependencies, List<String> writeTargets) {
    }

    /** The artifacts a capability promises to produce, with the schema each is checked against and the digest of the target it covers. */
    public record ArtifactContract(String capability, List<ProducedArtifact> produced) {
    }

    /** One artifact a capability produces. */
    public record ProducedArtifact(String artifactId, String schemaId, String targetDigest) {
    }

    /** The actions the task allows, forbids and asks the user about. */
    public record Authorization(List<String> allowedActions, List<String> prohibitedActions, List<String> approvalRequired) {
    }

    /** Whether the task is simple or complex, and whether the request has conflicts. */
    public record Decision(Complexity complexity, boolean hasConflicts) {
    }

    /** Whether orchestration was asked for, and whether the MCP server that backs it is available. */
    public record Orchestration(boolean requested, boolean mcpAvailable) {
    }

    /** The complexity a task declares; the task contract spells the wire text. */
    public enum Complexity {
        SIMPLE("simple"),
        COMPLEX("complex");

        private final String wire;

        Complexity(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** The envelope with no task ID: what a user approval binds, because a generated task ID does not change what the user approved. */
    TaskEnvelope withoutTaskId() {
        return new TaskEnvelope(schemaVersion, null, objective, scope, acceptanceCriteria, riskLevel, workUnits, artifactContracts, requiredCapabilities, constraints, authorization, decision,
                orchestration);
    }
}
