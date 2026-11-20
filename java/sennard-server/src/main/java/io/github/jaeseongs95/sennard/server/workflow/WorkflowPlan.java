// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import io.github.jaeseongs95.sennard.server.contract.ApiResult;
import io.github.jaeseongs95.sennard.service.store.RunState;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * A workflow plan ({@code workflow-plan.v1.schema.json}): the ordered stages a task needs, signed with the server's key so that a plan a caller sends back is the
 * plan the server issued. The contract schema is checked at the edges (a plan a caller sends, and the plan written out); inside, the plan is this record, and a
 * convergence attempt proposal, a lease claim and a run read it as one. A change of a plan is a new record ({@link #withState}, {@link #withStageState}).
 *
 * <p>The JSON is what the signature and the digests are made over, so the record writes exactly the keys the plan always had: {@code taskDigest} and
 * {@code bootstrapExecution} only when there is one, {@code currentStageId} and {@code nextStageId} as {@code null} when there is no such stage.
 */
public record WorkflowPlan(String schemaVersion, String taskId, @JsonInclude(JsonInclude.Include.NON_NULL) String taskDigest, String integrityToken, ExecutionMode executionMode, RunState state,
        List<String> selectedSkills, List<Stage> stages, @JsonInclude(JsonInclude.Include.ALWAYS) String currentStageId, @JsonInclude(JsonInclude.Include.ALWAYS) String nextStageId,
        List<ApiResult.ErrorBody> errors, @JsonInclude(JsonInclude.Include.NON_NULL) BootstrapExecution bootstrapExecution) {

    /** The observation of the model that planned an orchestrated task, with the requirement it was held to. */
    public record BootstrapExecution(ExecutionRequirement requirement, ExecutionContext context) {
    }

    /** One stage of a plan: the capability it provides, the skill's provider that supplies it, and everything its result is checked against. */
    public record Stage(String stageId, int order, String requiredCapability, @JsonInclude(JsonInclude.Include.NON_NULL) EvaluationAuditPurpose evaluationAuditPurpose,
            @JsonInclude(JsonInclude.Include.NON_NULL) AuditPhase requiredAuditPhase, List<String> satisfiedCapabilities, String skillId, String phase, String selectionReason, RunState state,
            @JsonInclude(JsonInclude.Include.NON_NULL) ExecutionRequirement executionRequirement, @JsonInclude(JsonInclude.Include.NON_NULL) TaskEnvelope.ArtifactContract artifactContract,
            List<String> requiredArtifacts, RiskGate riskGate, String providerKey, ExecutionClass executionClass, int phaseOrder, List<String> requiredInputArtifacts,
            List<InputBinding> inputBindings, List<String> producedArtifacts, SchemaReference outputSchema, SchemaReference resultSchema, StateMapping stateMapping, Gate gate,
            @JsonInclude(JsonInclude.Include.NON_NULL) StageReceiptPolicy receiptPolicy) {

        Stage withState(RunState newState) {
            return new Stage(stageId, order, requiredCapability, evaluationAuditPurpose, requiredAuditPhase, satisfiedCapabilities, skillId, phase, selectionReason, newState, executionRequirement,
                    artifactContract, requiredArtifacts, riskGate, providerKey, executionClass, phaseOrder, requiredInputArtifacts, inputBindings, producedArtifacts, outputSchema, resultSchema,
                    stateMapping, gate, receiptPolicy);
        }
    }

    /** Where an input artifact of a stage comes from and what the stage does with it. {@code optional} is written only where the descriptor declared it. */
    public record InputBinding(String targetArtifact, List<String> sources, InputOperation operation, @JsonInclude(JsonInclude.Include.NON_NULL) Boolean optional) {
    }

    /** A schema file a stage's output or result is checked against, and the digest of the file the plan froze. */
    public record SchemaReference(String path, String digest) {
    }

    /** How a stage declares that a gate applies. {@code validator} and {@code validatorSchema} are written as {@code null} when the stage has none. */
    public record Gate(GateKind kind, RiskGate policy, @JsonInclude(JsonInclude.Include.ALWAYS) String validator, @JsonInclude(JsonInclude.Include.ALWAYS) SchemaReference validatorSchema) {
    }

    /** When a gate is checked: not at all, before the stage, or when it completes; the descriptors spell the wire text. */
    public enum GateKind {
        NONE("none"),
        PRECONDITION("precondition"),
        COMPLETION("completion");

        private final String wire;

        GateKind(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /**
     * How the state of a provider's result is chosen: the rule the value at {@code selector} names, or the default. A mapping with no selector uses the default
     * alone. The schema calls the default {@code default}, which Java does not allow as a name.
     */
    public record StateMapping(@JsonInclude(JsonInclude.Include.NON_NULL) String selector, @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, MappingRule> values,
            @JsonProperty("default") DefaultMapping fallback, List<WorkflowCode> adapterErrors) {
    }

    /** The state a result takes under a mapping, whether it must carry an error, and the codes that error may have ({@code null} for any). */
    public record MappingRule(RunState state, boolean errorRequired, @JsonInclude(JsonInclude.Include.NON_NULL) List<WorkflowCode> allowedErrorCodes) {
    }

    /**
     * What a mapping does with a value it has no rule for: the text {@code reject}, or a rule. It is written as that text or as the rule, so a descriptor's
     * {@code "default": "reject"} and {@code "default": {…}} both read back as written.
     */
    @JsonDeserialize(using = DefaultMapping.Reading.class)
    public record DefaultMapping(MappingRule rule) {

        /** The default that refuses every value without a rule. */
        public static final DefaultMapping REJECT = new DefaultMapping(null);

        @JsonValue
        Object json() {
            return rule == null ? RejectText.REJECT : rule;
        }

        /** The one text a default may be besides a rule, spelled as the contract spells it. */
        public enum RejectText {
            REJECT("reject");

            private final String wire;

            RejectText(String wire) {
                this.wire = wire;
            }

            @JsonValue
            public String wire() {
                return wire;
            }
        }

        /** Reads {@code "reject"} or a rule; any other text is not a rule and is refused as one. */
        static final class Reading extends StdDeserializer<DefaultMapping> {
            Reading() {
                super(DefaultMapping.class);
            }

            @Override
            public DefaultMapping deserialize(JsonParser parser, DeserializationContext context) {
                JsonNode node = context.readTree(parser);
                if (node.isString()) {
                    context.readTreeAsValue(node, RejectText.class);
                    return REJECT;
                }
                return new DefaultMapping(context.readTreeAsValue(node, MappingRule.class));
            }
        }
    }

    /** The plan with another integrity token. */
    WorkflowPlan withIntegrityToken(String token) {
        return new WorkflowPlan(schemaVersion, taskId, taskDigest, token, executionMode, state, selectedSkills, stages, currentStageId, nextStageId, errors, bootstrapExecution);
    }

    /** The plan in another state. */
    WorkflowPlan withState(RunState newState) {
        return new WorkflowPlan(schemaVersion, taskId, taskDigest, integrityToken, executionMode, newState, selectedSkills, stages, currentStageId, nextStageId, errors, bootstrapExecution);
    }

    /** The plan pointing at another current and next stage. */
    WorkflowPlan withPointers(String current, String next) {
        return new WorkflowPlan(schemaVersion, taskId, taskDigest, integrityToken, executionMode, state, selectedSkills, stages, current, next, errors, bootstrapExecution);
    }

    /** The plan whose stage of this ID is in another state. */
    WorkflowPlan withStageState(String stageId, RunState newState) {
        List<Stage> changed = stages.stream().map(stage -> Objects.equals(stageId, stage.stageId()) ? stage.withState(newState) : stage).toList();
        return new WorkflowPlan(schemaVersion, taskId, taskDigest, integrityToken, executionMode, state, selectedSkills, changed, currentStageId, nextStageId, errors, bootstrapExecution);
    }

    /** The plan of a run that is going on: the first stage still ready is the current one, the second is the next. */
    WorkflowPlan withRunningPointers() {
        List<Stage> ready = stages.stream().filter(stage -> stage.state() == RunState.READY).toList();
        return withPointers(ready.isEmpty() ? null : ready.get(0).stageId(), ready.size() < 2 ? null : ready.get(1).stageId());
    }

    /** The plan of a run that ended without finishing: blocked, with no stage to go on with. */
    WorkflowPlan blocked() {
        return withState(RunState.BLOCKED).withPointers(null, null);
    }

    /** The stage of this ID, or none when the plan has none. */
    Optional<Stage> stage(String stageId) {
        return stages.stream().filter(stage -> Objects.equals(stageId, stage.stageId())).findFirst();
    }
}
