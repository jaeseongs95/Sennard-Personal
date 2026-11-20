// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import io.github.jaeseongs95.sennard.app.message.CatalogMessageSource;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.service.store.RunState;
import tools.jackson.databind.json.JsonMapper;

/** Plan documents written from code over the repository's real registry and contract files, for the tests that check them. */
final class PlanSamples {
    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final Path REPO = Path.of(System.getProperty("sennard.repo.root"));
    /** A signing key of the length the server's is. */
    static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    static final Instant OBSERVED = Instant.parse("2026-11-11T09:00:00.123456789Z");

    private PlanSamples() {
    }

    /** What building and checking a plan needs: the real registry and contract files, and the real planner over them. */
    record Wiring(ContractValidator validator, SkillRegistry registry, PlanBuilder planner, DocumentDigests digests) {
    }

    static Wiring wire() throws IOException {
        ContentRoot content = new ContentRoot(REPO);
        ContractValidator validator = new ContractValidator(MAPPER, content);
        DocumentDigests digests = new DocumentDigests(MAPPER);
        Messages messages = new Messages(new MessageSourceAccessor(new CatalogMessageSource(new PathMatchingResourcePatternResolver(), MAPPER), Locale.ENGLISH));
        return new Wiring(validator, new SkillRegistry(content, validator, MAPPER), new PlanBuilder(messages, validator, digests), digests);
    }

    /** A task that asks for these capabilities and no artifact contract. */
    static TaskEnvelope task(String taskId, RiskLevel risk, List<String> capabilities, boolean orchestrated) {
        return new TaskEnvelope("1.0.0", taskId, "Make the build pass", new TaskEnvelope.Scope(List.of("src/a.java"), List.of()), List.of("The build passes"), risk,
                List.of(new TaskEnvelope.WorkUnit("w1", "Fix a", List.of(), List.of("src/a.java"))), null, capabilities, List.of(), new TaskEnvelope.Authorization(List.of(), List.of(), List.of()),
                new TaskEnvelope.Decision(TaskEnvelope.Complexity.COMPLEX, false), new TaskEnvelope.Orchestration(orchestrated, true));
    }

    /** The observation the server writes for the bootstrap of an orchestrated plan. */
    static ExecutionContext bootstrap(String taskId) {
        return new ExecutionContext("1.0.0", "a-model-nobody-listed", null, "high", List.of("MODEL_NOT_IN_CATALOG"), ExecutionSource.RUNTIME, OBSERVED, "observation-0123456789abcdef", taskId, null, null,
                null, "codex:actor-1", null);
    }

    /** A stage with everything a plan's stage has and nothing a check of one thing needs to look at. */
    static WorkflowPlan.Stage stage(String stageId, String capability, List<String> requiredArtifacts, WorkflowPlan.Gate gate, TaskEnvelope.ArtifactContract contract) {
        return stage(stageId, capability, "a-skill:a-phase:1", requiredArtifacts, gate, contract);
    }

    /** The same stage as the provider of that key supplies it. */
    static WorkflowPlan.Stage stage(String stageId, String capability, String providerKey, List<String> requiredArtifacts, WorkflowPlan.Gate gate, TaskEnvelope.ArtifactContract contract) {
        WorkflowPlan.SchemaReference schema = new WorkflowPlan.SchemaReference("contracts/freeform-output.v1.schema.json", "sha256:" + Digests.sha256Hex("schema"));
        WorkflowPlan.StateMapping mapping = new WorkflowPlan.StateMapping(null, null, WorkflowPlan.DefaultMapping.REJECT, List.of());
        return new WorkflowPlan.Stage(stageId, 1, capability, null, null, List.of(capability), "a-skill", "a-phase", "a reason", RunState.READY, null, contract, requiredArtifacts, RiskGate.NONE,
                providerKey, ExecutionClass.WORKFLOW, 0, List.of(), List.of(), List.of(), schema, schema, mapping, gate, null);
    }

    /** The same stage as a plan with an execution requirement makes it. */
    static WorkflowPlan.Stage withRequirement(WorkflowPlan.Stage stage, ExecutionRequirement requirement) {
        return new WorkflowPlan.Stage(stage.stageId(), stage.order(), stage.requiredCapability(), stage.evaluationAuditPurpose(), stage.requiredAuditPhase(), stage.satisfiedCapabilities(), stage.skillId(),
                stage.phase(), stage.selectionReason(), stage.state(), requirement, stage.artifactContract(), stage.requiredArtifacts(), stage.riskGate(), stage.providerKey(), stage.executionClass(),
                stage.phaseOrder(), stage.requiredInputArtifacts(), stage.inputBindings(), stage.producedArtifacts(), stage.outputSchema(), stage.resultSchema(), stage.stateMapping(), stage.gate(),
                stage.receiptPolicy());
    }

    /** A stage of a mandatory audit, frozen to this phase, that must produce the gate verdict. */
    static WorkflowPlan.Stage audit(String stageId, AuditPhase phase) {
        WorkflowPlan.Stage base = stage(stageId, "independent-audit-gate", List.of("gate-verdict"), openGate(), null);
        return new WorkflowPlan.Stage(base.stageId(), base.order(), base.requiredCapability(), null, phase, base.satisfiedCapabilities(), base.skillId(), base.phase(), base.selectionReason(), base.state(), null,
                null, base.requiredArtifacts(), RiskGate.MANDATORY, base.providerKey(), base.executionClass(), base.phaseOrder(), base.requiredInputArtifacts(), base.inputBindings(), base.producedArtifacts(),
                base.outputSchema(), base.resultSchema(), base.stateMapping(), base.gate(), null);
    }

    /** A gate that names no validator. */
    static WorkflowPlan.Gate openGate() {
        return new WorkflowPlan.Gate(WorkflowPlan.GateKind.NONE, RiskGate.NONE, null, null);
    }
}
