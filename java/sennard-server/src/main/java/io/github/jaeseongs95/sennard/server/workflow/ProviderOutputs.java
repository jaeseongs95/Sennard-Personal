// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The parts of a provider's output that the workflow gates read. The output is what a skill produced, and the skills' own schemas say what is in it, so a gate
 * does not own its shape: each record here names only the fields a gate looks at and ignores the rest. A field of another JSON type than the gate expects is not
 * read as the type (a text {@code "true"} is not a flag, a number is not a text), so the output fails the gate and does not pass by being converted. Binding an
 * output that is not of the shape is a failure the gate reports as its own, with the binding failure as the cause.
 */
final class ProviderOutputs {
    private ProviderOutputs() {
    }

    /** What the mandatory audit gate reads: the audit's verdict and the facts it states about itself. A field that is left out is null. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Audit(GateVerdict gateVerdict, @JsonDeserialize(using = StrictInput.Text.class) String auditorId,
            @JsonDeserialize(contentUsing = StrictInput.Item.class) List<String> implementationActorIds, @JsonDeserialize(using = StrictInput.Text.class) String auditTarget,
            List<JsonNode> blockingFindings, @JsonDeserialize(using = StrictInput.Flag.class) Boolean freshContext, @JsonDeserialize(using = StrictInput.Flag.class) Boolean delegationAllowed,
            @JsonDeserialize(using = StrictInput.Flag.class) Boolean stale, AuditPhase phase, @JsonDeserialize(using = StrictInput.Flag.class) Boolean postExecutionVerified) {

        /** The audit names at least one implementation actor, and each is a text. */
        boolean namesImplementers() {
            return implementationActorIds != null && !implementationActorIds.isEmpty() && implementationActorIds.stream().allMatch(actor -> actor != null && !actor.isEmpty());
        }
    }

    /** What the deliberation gate reads of the output: the decision record, whose shape the panel skill's schema states, and whether its conditions were verified. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Deliberation(JsonNode decisionRecord, @JsonDeserialize(using = StrictInput.Flag.class) Boolean conditionsVerified) {
    }

    /** What the deliberation gate reads of a decision record that passed its schema and its semantic rules: how the run went and what the panel concluded. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Decision(Run run, @JsonProperty("consensus_proposal") Proposal consensusProposal) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Run(DecisionTerms.Assurance assurance, @JsonProperty("capability_shortfall") Boolean capabilityShortfall, DecisionTerms.Stage stage, Boolean strict) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Proposal(DecisionTerms.ConsensusStatus status) {
        }
    }

    /** What the evaluation-validity gate shows of the report it refuses. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Evaluation(@JsonDeserialize(using = StrictInput.Text.class) String auditStage, @JsonDeserialize(using = StrictInput.Text.class) String verdict,
            JsonNode qualifiesAsQualityOrReleaseEvidence) {
    }

    /** The digest a workspace baseline stage records ({@code manifestSha256} when it captures one, {@code baselineDigest} when it assures against one). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Baseline(String manifestSha256, String baselineDigest) {
    }
}
