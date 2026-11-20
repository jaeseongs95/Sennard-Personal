// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileRef;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The provider's own semantic rules, selected by the stage's gate validator or capability. The rules are the skills' checks reached through
 * {@link SkillChecks}; the acceptance check additionally binds the frozen request to this task and to the target the signed plan declared.
 */
@Component
class ProviderSemantics {
    /**
     * The semantic rules a stage can be held to, each by the name a gate validator or a capability gives it, with the sentences of the two refusals. The rules are a
     * skill's check each: the security audit report is judged by its own; the acceptance evidence report is judged by its skill's check against the frozen request.
     */
    private enum Validator {
        SECURITY("software-security-audit", "workflow.provider.securityAuditReportOutputMissing", "workflow.provider.securityAuditReportInconsistent"),
        ACCEPTANCE("acceptance-evidence-validation", "workflow.provider.acceptanceEvidenceReportOutputMissing", "workflow.provider.acceptanceEvidenceReportInconsistent");

        private final String named;
        private final String outputMissing;
        private final String inconsistent;

        Validator(String named, String outputMissing, String inconsistent) {
            this.named = named;
            this.outputMissing = outputMissing;
            this.inconsistent = inconsistent;
        }

        /** The validator a name selects, or null where the name is no validator's. */
        static Validator named(String name) {
            for (Validator validator : values()) {
                if (validator.named.equals(name)) {
                    return validator;
                }
            }
            return null;
        }
    }

    private final ObjectProvider<SkillChecks> checks;
    private final JsonMapper mapper;

    ProviderSemantics(ObjectProvider<SkillChecks> checks, JsonMapper mapper) {
        this.checks = checks;
        this.mapper = mapper;
    }

    /** What the acceptance-evidence validator is asked: the report to judge and the frozen request it must answer. Both are the skill's own documents. */
    private record ValidateAgainstRequest(JsonNode report, JsonNode request) {
    }

    /** What the security audit check is asked: the report to judge, the skill's own document. */
    private record JudgeReport(JsonNode report) {
    }

    /** What is read of the validator's answer: the report's problems, or the code of its own failure. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ValidatorAnswer(String code, Value value) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Value(List<String> errors) {
        }

        List<String> errors() {
            return value == null || value.errors() == null ? List.of() : value.errors();
        }
    }

    /** What is read of the frozen acceptance request file: the target it is about and the task it freezes (kept as the tree its digest is made over). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FrozenRequest(Target target, JsonNode taskEnvelope) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Target(String digest) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TaskIdentity(String taskId) {
    }

    /** The rules this stage is held to, each once. */
    private static Set<Validator> validatorsOf(WorkflowPlan.Stage stage) {
        Set<String> names = new LinkedHashSet<>();
        String gateValidator = stage.gate().validator();
        if (gateValidator != null) {
            names.add(gateValidator);
        }
        names.add(stage.requiredCapability());
        names.addAll(stage.satisfiedCapabilities());
        Set<Validator> validators = new LinkedHashSet<>();
        for (String name : names) {
            Validator validator = Validator.named(name);
            if (validator != null) {
                validators.add(validator);
            }
        }
        return validators;
    }

    /** The one verified attachment that is the frozen acceptance request of the result; null where the result names none, or several. */
    private static ProviderArtifact frozenRequestReference(StageResult result) {
        List<ProviderArtifact> references = result.output().artifacts().stream().filter(artifact -> KnownArtifact.ACCEPTANCE_EVIDENCE_REQUEST.is(artifact.artifactId())).toList();
        ProviderArtifact reference = references.size() == 1 ? references.getFirst() : null;
        return reference == null || !reference.verified() ? null : reference;
    }

    /**
     * The file this stage's rules read, as the request the entry answers before the transaction: the frozen acceptance request, for a stage held to the acceptance evidence rules.
     * Nothing is asked where there is none to read; {@link #check} then refuses the result in the order its own checks give.
     */
    Optional<ReadRequest.Document> request(WorkflowPlan.Stage stage, StageResult result, String taskDigest) {
        ProviderArtifact reference = frozenRequestReference(result);
        if (taskDigest == null || reference == null || reference.digest() == null || !validatorsOf(stage).contains(Validator.ACCEPTANCE)) {
            return Optional.empty();
        }
        return Optional.of(new ReadRequest.Document(new AttachmentFileRef(reference.attachmentId(), reference.seq()), StageGates.sha256Form(reference.digest())));
    }

    void check(WorkflowPlan.Stage stage, StageResult result, String taskId, String taskDigest, FileReads reads) {
        Set<Validator> validators = validatorsOf(stage);
        JsonNode output = result.output().hasOutput() ? result.output().output() : null;
        for (Validator validator : validators) {
            if (output == null) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg(validator.outputMissing), details("stageId", stage.stageId(), "validator", validator.named));
            }
            List<String> errors = switch (validator) {
                case SECURITY -> security(output, stage, validator);
                case ACCEPTANCE -> acceptance(output, stage, result, taskId, taskDigest, validator, reads);
            };
            if (!errors.isEmpty()) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg(validator.inconsistent), details("stageId", stage.stageId(), "validator", validator.named, "errors", errors));
            }
        }
    }

    /** The security audit report's own rules: its structure and the links among its checks, evidence and findings, with no file read. */
    private List<String> security(JsonNode output, WorkflowPlan.Stage stage, Validator validator) {
        return run("software-security-auditor", "provider-semantics", new JudgeReport(output), stage, validator);
    }

    /**
     * Runs the shared report rules against the frozen request the stage recorded, then binds that request to this task and to the target the
     * signed plan declared, so a self-consistent report about another target or another request cannot pass.
     */
    private List<String> acceptance(JsonNode output, WorkflowPlan.Stage stage, StageResult result, String taskId, String taskDigest, Validator validator, FileReads reads) {
        String stageId = stage.stageId();
        if (taskDigest == null) {
            throw new WorkflowFailure(WorkflowCode.BINDING_REQUIRED, msg("workflow.provider.acceptanceEvidenceVerificationRequiresSigned"), details("stageId", stageId));
        }
        ProviderArtifact reference = frozenRequestReference(result);
        if (reference == null) {
            throw new WorkflowFailure(WorkflowCode.MISSING_EVIDENCE, msg("workflow.provider.acceptanceEvidenceReportRequiresVerified"), details("stageId", stageId, "artifactId", KnownArtifact.ACCEPTANCE_EVIDENCE_REQUEST.wire()));
        }
        // The frozen request file was read, with its digest checked, before this transaction.
        JsonNode request = reads.document(new AttachmentFileRef(reference.attachmentId(), reference.seq()), StageGates.sha256Form(reference.digest()));
        List<String> errors = run("acceptance-evidence-validator", "validate-against-request", new ValidateAgainstRequest(output, request), stage, validator);
        if (!errors.isEmpty()) {
            return errors;
        }
        FrozenRequest frozen = frozen(request, stageId);
        String requestTarget = frozen.target() == null ? null : frozen.target().digest();
        boolean taskBound = frozen.taskEnvelope() != null && Objects.equals(taskId, taskIdentity(frozen.taskEnvelope(), stageId).taskId()) && Objects.equals(taskDigest, CanonicalJson.digest(frozen.taskEnvelope()));
        List<String> declaredTargets = new ArrayList<>();
        if (stage.artifactContract() != null) {
            stage.artifactContract().produced().forEach(produced -> declaredTargets.add(produced.targetDigest()));
        }
        if (!taskBound || requestTarget == null || !Objects.equals(requestTarget, StageGates.sha256Form(reference.targetDigest())) || declaredTargets.stream().anyMatch(declared -> !Objects.equals(requestTarget, declared))) {
            throw new WorkflowFailure(WorkflowCode.BINDING_INVALID, msg("workflow.provider.frozenAcceptanceRequestBoundTask"), details("stageId", stageId, "taskBound", taskBound, "requestTarget", requestTarget, "declaredTargets", declaredTargets));
        }
        return List.of();
    }

    /** The frozen request file as far as it is read; a file that has not the shape the validator already accepted is a binding failure of the stage. */
    private FrozenRequest frozen(JsonNode request, String stageId) {
        try {
            return mapper.treeToValue(request, FrozenRequest.class);
        } catch (JacksonException unbound) {
            throw frozenUnreadable(stageId, unbound);
        }
    }

    private TaskIdentity taskIdentity(JsonNode taskEnvelope, String stageId) {
        try {
            return mapper.treeToValue(taskEnvelope, TaskIdentity.class);
        } catch (JacksonException unbound) {
            throw frozenUnreadable(stageId, unbound);
        }
    }

    private static WorkflowFailure frozenUnreadable(String stageId, JacksonException cause) {
        return new WorkflowFailure(WorkflowCode.BINDING_INVALID, msg("workflow.provider.frozenAcceptanceRequestBoundTask"), details("stageId", stageId, "taskBound", false), cause);
    }

    /** A report that was never judged is never accepted: with no check, or a check that could not run, the stage is refused. */
    private List<String> run(String skill, String command, Object arguments, WorkflowPlan.Stage stage, Validator validator) {
        SkillChecks skillChecks = checks.getIfAvailable();
        if (skillChecks == null) {
            throw unavailable(stage, validator, "VALIDATOR_NOT_PREPARED", null);
        }
        // The skill-check port of another package takes its arguments, and answers, as trees.
        SkillChecks.Result outcome = skillChecks.run(skill, command, mapper.valueToTree(arguments));
        ValidatorAnswer answer = mapper.treeToValue(outcome.body(), ValidatorAnswer.class);
        if (outcome.exitCode() != 0 && outcome.exitCode() != 1) {
            throw unavailable(stage, validator, "VALIDATOR_FAILED", answer.code());
        }
        return answer.errors();
    }

    private static WorkflowFailure unavailable(WorkflowPlan.Stage stage, Validator validator, String reason, String childCode) {
        return new WorkflowFailure(WorkflowCode.MCP_UNAVAILABLE, msg("workflow.validatorProcess.couldNotRun", "label", "provider semantics", "reason", reason),
                details("step", "run", "reason", reason, "stageId", stage.stageId(), "validator", validator.named, "childCode", childCode));
    }
}
