// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

/**
 * A provider of a skill as the planner reads it: the descriptor's provider joined with what the skill declares for all of its providers (ID, version, priority,
 * whether it is enabled), the key that names it, and the digests of the schema files it names, taken when the registry was read.
 *
 * @param providerKey {@code skillId:phase:index}, the same for the same registry on every read
 * @param stageValidator the skill whose check judges the bundle a stage of this provider supplies ({@code check-stage-bundle}); {@code null} where the provider declares none
 */
public record SkillProvider(String skillId, String version, String path, boolean enabled, long priority, String providerKey, List<String> capabilities, ExecutionClass executionClass, String phase,
        int phaseOrder, List<String> requiredInputArtifacts, List<WorkflowPlan.InputBinding> inputBindings, List<String> producedArtifacts, WorkflowPlan.SchemaReference outputSchema,
        WorkflowPlan.SchemaReference resultSchema, WorkflowPlan.StateMapping stateMapping, List<String> selectionCriteria, WorkflowPlan.Gate gate, StageReceiptPolicy receiptPolicy,
        String stageValidator) {
}
