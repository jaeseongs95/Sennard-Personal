// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.contract.ApiResult;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.service.store.RunState;

/** Turns a task envelope into the ordered stages of a plan, and signs and verifies that plan. */
@Component
public class PlanBuilder {
    /** The token a plan carries while it is being built; the signature is made over the plan without a token. */
    private static final String PENDING_TOKEN = "pending";
    private static final Pattern PLAN_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$");
    /** The capabilities whose stage is decided by a skill's check alone, so it needs no observation of the model. */
    private static final Set<String> DETERMINISTIC_CAPABILITIES = Set.of("korean-prose-finalization");

    private final Messages messages;
    private final ContractValidator validator;
    private final DocumentDigests digests;

    public PlanBuilder(Messages messages, ContractValidator validator, DocumentDigests digests) {
        this.messages = messages;
        this.validator = validator;
        this.digests = digests;
    }

    private record Selected(String capability, List<String> satisfied, SkillProvider provider) {
    }

    /** Whether every capability a stage satisfies is one a check alone decides. */
    static boolean deterministic(List<String> capabilities) {
        return !capabilities.isEmpty() && DETERMINISTIC_CAPABILITIES.containsAll(capabilities);
    }

    /** A semantic stage asks that the host's observation of the model be recorded; a deterministic one asks for none. Neither names a model or an effort, whatever the risk. */
    private static ExecutionRequirement stageRequirement(List<String> capabilities) {
        return deterministic(capabilities) ? ExecutionRequirement.DETERMINISTIC : ExecutionRequirement.SEMANTIC;
    }

    ApiResult.ErrorBody errorBody(WorkflowCode code, MessageRef ref, Map<String, ?> extra) {
        return WorkflowResults.errorBody(messages, new WorkflowFailure(code, ref, extra));
    }

    private static List<String> requiredCapabilities(TaskEnvelope task) {
        List<String> requested = task.requiredCapabilities();
        String auditCapability = KnownCapability.INDEPENDENT_AUDIT.wire();
        boolean audit = requested.contains(auditCapability) || task.riskLevel().needsAudit();
        Set<String> out = new LinkedHashSet<>();
        requested.stream().filter(capability -> !KnownCapability.INDEPENDENT_AUDIT.is(capability)).forEach(out::add);
        if (audit) {
            out.add(auditCapability);
        }
        return new ArrayList<>(out);
    }

    private static List<Selected> orderProviders(List<Selected> items) {
        Map<String, Integer> producedBy = new HashMap<>();
        for (int index = 0; index < items.size(); index++) {
            SkillProvider provider = items.get(index).provider();
            for (String artifact : provider.producedArtifacts()) {
                Integer existing = producedBy.get(artifact);
                if (existing != null && !Objects.equals(items.get(existing).provider().providerKey(), provider.providerKey())) {
                    throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.selectedProvidersProduceSameArtifact"),
                            details("artifact", artifact, "providers", List.of(items.get(existing).provider().providerKey(), provider.providerKey())));
                }
                producedBy.put(artifact, index);
            }
        }
        Map<Integer, Set<Integer>> outgoing = new HashMap<>();
        int[] indegree = new int[items.size()];
        for (int consumer = 0; consumer < items.size(); consumer++) {
            for (String artifact : items.get(consumer).provider().requiredInputArtifacts()) {
                Integer producer = producedBy.get(artifact);
                if (producer == null || producer == consumer) {
                    continue;
                }
                if (outgoing.computeIfAbsent(producer, ignored -> new HashSet<>()).add(consumer)) {
                    indegree[consumer]++;
                }
            }
        }
        Comparator<Integer> compare = Comparator.<Integer>comparingInt(index -> items.get(index).provider().phaseOrder())
                .thenComparing(index -> items.get(index).capability(), SkillRegistry.COLLATION::compare)
                .thenComparing(index -> items.get(index).provider().providerKey(), SkillRegistry.COLLATION::compare);
        List<Integer> ready = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            if (indegree[index] == 0) {
                ready.add(index);
            }
        }
        ready.sort(compare);
        List<Selected> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            int index = ready.remove(0);
            ordered.add(items.get(index));
            for (int next : outgoing.getOrDefault(index, Set.of())) {
                if (--indegree[next] == 0) {
                    ready.add(next);
                    ready.sort(compare);
                }
            }
        }
        if (ordered.size() != items.size()) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.selectedProviderArtifactDependenciesContain"));
        }
        return ordered;
    }

    /** The artifact contract the stage's capabilities share, or null when the task declares none for them. */
    private static TaskEnvelope.ArtifactContract plannedArtifactContract(TaskEnvelope task, List<String> capabilities, List<String> requiredArtifacts) {
        List<TaskEnvelope.ArtifactContract> contracts = task.artifactContracts() == null ? List.of()
                : task.artifactContracts().stream().filter(item -> capabilities.contains(item.capability())).toList();
        if (contracts.isEmpty()) {
            return null;
        }
        if (contracts.size() != 1) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.selectedStageNeedsUnambiguousArtifact"));
        }
        TaskEnvelope.ArtifactContract contract = contracts.get(0);
        List<String> ids = contract.produced().stream().map(TaskEnvelope.ProducedArtifact::artifactId).toList();
        if (ids.size() != requiredArtifacts.size() || new HashSet<>(ids).size() != ids.size() || !ids.containsAll(requiredArtifacts)) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.artifactContractIDsExactlyMatch"));
        }
        return contract;
    }

    /** One required capability's provider joins the plan (a provider that supplies several capabilities is one stage), or is refused when it is a bootstrap one. */
    private void select(SkillProvider skill, String capability, Map<String, Selected> selected, List<ApiResult.ErrorBody> errors) {
        String providerKey = skill.providerKey();
        if (skill.executionClass() == ExecutionClass.BOOTSTRAP) {
            errors.add(errorBody(WorkflowCode.GATE_FAILED, msg("workflow.service.bootstrapCapabilityInPlan", "capability", capability), details("capability", capability, "providerKey", providerKey)));
            return;
        }
        Selected existing = selected.get(providerKey);
        if (existing != null) {
            if (!existing.satisfied().contains(capability)) {
                existing.satisfied().add(capability);
            }
        } else {
            selected.put(providerKey, new Selected(capability, new ArrayList<>(List.of(capability)), skill));
        }
    }

    /**
     * The signed plan of a task over the providers of the current registry. {@code evaluationAuditPurpose}, {@code executionContext} and
     * {@code requiredAuditPhase} may be null: a plan has a bootstrap observation, and a requirement on every stage, only when there is a context.
     */
    WorkflowPlan build(TaskEnvelope task, List<SkillProvider> skills, EvaluationAuditPurpose evaluationAuditPurpose, ExecutionContext executionContext, AuditPhase requiredAuditPhase, byte[] key) {
        AuditPhase phase = requiredAuditPhase == null ? AuditPhase.POST_EXECUTION : requiredAuditPhase;
        boolean orchestrated = task.orchestration().requested();
        List<ApiResult.ErrorBody> errors = new ArrayList<>();
        Map<String, Selected> selected = new LinkedHashMap<>();
        for (String capability : requiredCapabilities(task)) {
            SkillRegistry.selectByCapability(skills, capability).ifPresentOrElse(skill -> select(skill, capability, selected, errors),
                    () -> errors.add(errorBody(WorkflowCode.GATE_FAILED, msg("workflow.service.noProviderForCapability", "capability", capability), details("capability", capability))));
        }
        List<Selected> providers = new ArrayList<>(selected.values());
        Set<ExecutionClass> classes = new LinkedHashSet<>();
        providers.forEach(item -> classes.add(item.provider().executionClass()));
        if (classes.size() > 1) {
            errors.add(errorBody(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.recoveryProvidersRunSeparateWorkflow"), details("executionClasses", classes.stream().map(ExecutionClass::wire).toList())));
        }
        for (Selected consumer : providers) {
            for (WorkflowPlan.InputBinding binding : consumer.provider().inputBindings()) {
                if (binding.operation() != InputOperation.REQUIRE_EXTERNAL) {
                    continue;
                }
                String artifact = binding.targetArtifact();
                providers.stream().filter(item -> !Objects.equals(item.provider().providerKey(), consumer.provider().providerKey()) && item.provider().producedArtifacts().contains(artifact)).findFirst()
                        .ifPresent(producer -> errors.add(errorBody(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.artifactFromExternalRun", "artifact", artifact),
                                details("consumerProviderKey", consumer.provider().providerKey(), "producerProviderKey", producer.provider().providerKey()))));
            }
        }
        List<WorkflowPlan.Stage> stages = new ArrayList<>();
        Set<String> selectedSkills = new LinkedHashSet<>();
        for (Selected item : orderProviders(providers)) {
            SkillProvider skill = item.provider();
            String capability = item.capability();
            List<String> produced = skill.producedArtifacts();
            boolean mandatory = skill.gate().policy() == RiskGate.MANDATORY;
            List<String> required = produced;
            if (mandatory && skill.capabilities().contains(KnownCapability.INDEPENDENT_AUDIT.wire())) {
                Set<String> merged = new LinkedHashSet<>(produced);
                merged.add(KnownArtifact.GATE_VERDICT.wire());
                required = new ArrayList<>(merged);
            }
            boolean evaluationAudit = KnownCapability.EVALUATION_VALIDITY_AUDIT.is(capability);
            if (evaluationAudit && evaluationAuditPurpose == null) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.evaluationAuditPurposeMissingPlanning"));
            }
            int order = stages.size() + 1;
            TaskEnvelope.ArtifactContract artifactContract = plannedArtifactContract(task, item.satisfied(), required);
            String selectionReason = "Selected '" + skill.skillId() + "' because provider '" + skill.providerKey() + "' supplies '" + String.join("', '", item.satisfied()) + "' at priority "
                    + skill.priority() + ".";
            stages.add(new WorkflowPlan.Stage(String.format("stage-%02d-%s", order, capability), order, capability, evaluationAudit ? evaluationAuditPurpose : null,
                    mandatory && required.contains(KnownArtifact.GATE_VERDICT.wire()) ? phase : null, item.satisfied(), skill.skillId(), skill.phase(), selectionReason, RunState.READY,
                    executionContext != null ? stageRequirement(item.satisfied()) : null, artifactContract, required, skill.gate().policy(), skill.providerKey(), skill.executionClass(), skill.phaseOrder(),
                    skill.requiredInputArtifacts(), skill.inputBindings(), produced, skill.outputSchema(), skill.resultSchema(), skill.stateMapping(), skill.gate(), skill.receiptPolicy()));
            selectedSkills.add(skill.skillId());
        }
        List<ApiResult.ErrorBody> ordered = new ArrayList<>();
        if (orchestrated && !task.orchestration().mcpAvailable()) {
            ordered.add(errorBody(WorkflowCode.MCP_UNAVAILABLE, msg("workflow.service.orchestratorBlockedBecauseMCPUnavailable"), null));
        }
        ordered.addAll(errors);
        for (TaskEnvelope.ArtifactContract contract : task.artifactContracts() == null ? List.<TaskEnvelope.ArtifactContract>of() : task.artifactContracts()) {
            String capability = contract.capability();
            if (stages.stream().noneMatch(stage -> stage.satisfiedCapabilities().contains(capability))) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.artifactContractCapabilitySelectedPlan"), details("capability", capability));
            }
        }
        WorkflowPlan unsigned = new WorkflowPlan(ContractValidator.CONTRACT_VERSION, task.taskId(), digests.of(task), PENDING_TOKEN, orchestrated ? ExecutionMode.ORCHESTRATED : ExecutionMode.DIRECT,
                ordered.isEmpty() ? RunState.READY : RunState.BLOCKED, new ArrayList<>(selectedSkills), stages, null, ordered.isEmpty() && !stages.isEmpty() ? stages.get(0).stageId() : null, ordered,
                executionContext == null ? null : new WorkflowPlan.BootstrapExecution(ExecutionRequirement.SEMANTIC, executionContext));
        return unsigned.withIntegrityToken(sign(unsigned, key));
    }

    static void validateWorkUnitGraph(TaskEnvelope task) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (TaskEnvelope.WorkUnit unit : task.workUnits()) {
            String id = unit.id();
            if (graph.containsKey(id)) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.workUnitIDsUnique"), details("workUnitId", id));
            }
            graph.put(id, unit.dependencies());
        }
        graph.forEach((id, dependencies) -> dependencies.forEach(dependency -> {
            if (!graph.containsKey(dependency)) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.workUnitDependencyExist"), details("workUnitId", id, "dependencyId", dependency));
            }
        }));
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        graph.keySet().forEach(id -> visit(id, graph, visiting, visited));
    }

    private static void visit(String id, Map<String, List<String>> graph, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(id)) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.workUnitDependenciesContainCycle"), details("workUnitId", id));
        }
        if (!visited.contains(id)) {
            visiting.add(id);
            graph.get(id).forEach(next -> visit(next, graph, visiting, visited));
            visiting.remove(id);
            visited.add(id);
        }
    }

    /** The token that signs the plan: the HMAC of its canonical form without the token. */
    String sign(WorkflowPlan plan, byte[] key) {
        return digests.integrityToken(plan, key);
    }

    /** The plan carries the token the server's key makes of it, and a mandatory audit in it names the phase it was frozen to. */
    void assertIntegrity(WorkflowPlan plan, byte[] key) {
        String token = plan.integrityToken() == null ? "" : plan.integrityToken();
        if (!PLAN_TOKEN.matcher(token).matches()) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.workflowPlanIntegrityTokenCanonical"));
        }
        byte[] expected = Base64.getUrlDecoder().decode(sign(plan, key));
        byte[] supplied = Base64.getUrlDecoder().decode(token);
        if (!MessageDigest.isEqual(supplied, expected)) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.workflowPlanIntegrityTokenMissing"));
        }
        for (WorkflowPlan.Stage stage : plan.stages()) {
            if (StageGates.mandatoryAudit(stage)) {
                requireAuditPhase(stage);
            }
        }
    }

    static AuditPhase requireAuditPhase(WorkflowPlan.Stage stage) {
        AuditPhase phase = stage.requiredAuditPhase();
        if (phase == null) {
            throw new WorkflowFailure(WorkflowCode.BINDING_REQUIRED, msg("workflow.service.mandatoryAuditPlanFrozenRequired"), details("stageId", stage.stageId()));
        }
        return phase;
    }

    ContractValidator validator() {
        return validator;
    }
}
