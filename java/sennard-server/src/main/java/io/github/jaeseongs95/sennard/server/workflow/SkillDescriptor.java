// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/**
 * A skill descriptor ({@code skill-descriptor.v2.schema.json}): one entry of the skill registry, with the providers the skill offers. The registry is read
 * through the contract (the schema is checked on each entry as the file has it, then the entry is bound to this record); a provider is routed into a
 * {@link SkillProvider} for the planner.
 *
 * <p>{@code failureHandling} is a sentence or an object the skill's author writes for readers of the descriptor; nothing in Sennard reads inside it, so it stays a
 * tree.
 */
public record SkillDescriptor(String schemaVersion, String skillId, String version, String path, boolean enabled, long priority, List<Provider> providers) {

    /** One capability set a skill provides in one phase, with the artifacts it needs and makes and the schemas and rules its result is held to. */
    public record Provider(List<String> capabilities, ExecutionClass executionClass, String phase, int phaseOrder, List<String> requiredInputArtifacts, List<WorkflowPlan.InputBinding> inputBindings,
            List<String> producedArtifacts, String outputSchema, String resultSchema, WorkflowPlan.StateMapping stateMapping, List<String> selectionCriteria, List<String> preconditions,
            JsonNode failureHandling, Gate gate, @JsonInclude(JsonInclude.Include.NON_NULL) StageReceiptPolicy receiptPolicy, @JsonInclude(JsonInclude.Include.NON_NULL) String stageValidator) {
    }

    /** The gate a provider declares: when it applies, how strongly, and the validator it names (a schema file or a check), if any. */
    public record Gate(WorkflowPlan.GateKind kind, RiskGate policy, @JsonInclude(JsonInclude.Include.ALWAYS) String validator) {
    }
}
