// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentContents;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileRef;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.service.store.RunState;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * The checks a stage result has to pass before it is recorded and again at finalize. Everything here reads; nothing here writes. The stage is the plan's
 * record and the result and the receipt are records; what a provider's output holds is the skill's own, so the gates read it through the small records of
 * {@link ProviderOutputs}, and the one value a plan's pointer names as a tree.
 */
@Component
public class StageGates {
    private static final String SHA256_PREFIX = "sha256:";
    private static final Pattern BARE_SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final List<String> SCHEMA_CAUSE_DETAIL_KEYS = List.of("schemaPath", "expectedDigest", "actualDigest", "validationErrors");
    /** The artifact a deliberation stage must produce; an artifact ID is the panel skill's own name, not a closed set. */
    static final String DECISION_RECORD_ARTIFACT = "decision-record";

    private final ContractValidator validator;
    private final JsonMapper mapper;
    private final StageBundleChecks bundleChecks;
    private final ProviderSemantics providerSemantics;

    public StageGates(ContractValidator validator, JsonMapper mapper, StageBundleChecks bundleChecks, ProviderSemantics providerSemantics) {
        this.validator = validator;
        this.mapper = mapper;
        this.bundleChecks = bundleChecks;
        this.providerSemantics = providerSemantics;
    }

    static String evidenceKey(String reference) {
        return BARE_SHA256.matcher(reference).matches() ? "sha256:" + reference : reference;
    }

    /** A digest in the {@code sha256:} form, whether the provider wrote it bare or prefixed. */
    static String sha256Form(String digest) {
        return digest.startsWith(SHA256_PREFIX) ? digest : SHA256_PREFIX + digest;
    }

    static boolean mandatoryAudit(WorkflowPlan.Stage stage) {
        return stage.riskGate() == RiskGate.MANDATORY && stage.requiredArtifacts().contains(KnownArtifact.GATE_VERDICT.wire());
    }

    /** Whether finalize judges this stage's result again: a review stage's bundle, and a mandatory audit's verdict. */
    static boolean rechecksBundles(WorkflowPlan.Stage stage) {
        return StageBundleChecks.covers(stage.requiredCapability()) || mandatoryAudit(stage);
    }

    static boolean independentCheck(WorkflowPlan.Stage stage) {
        return mandatoryAudit(stage) || stage.satisfiedCapabilities().contains(UnverifiedClaims.ITERATION_FRAME_AUDIT_CAPABILITY);
    }

    /** Observed recorders of a run's passed implementation stages; a null entry where no caller was observed. */
    static List<ObservedCaller> implementationRecorders(WorkflowReceipt receipt, WorkflowPlan plan) {
        List<ObservedCaller> out = new ArrayList<>();
        for (StageResult result : receipt.stageResults()) {
            if (result.passed() && plan.stage(result.stageId()).filter(stage -> !independentCheck(stage)).isPresent()) {
                out.add(result.recordedBy());
            }
        }
        return out;
    }

    /** Judged only by provenance so a new host needs no change here. */
    static Independence independence(ObservedCaller checked, List<ObservedCaller> implementers) {
        if (checked == null || !checked.hostObserved()) {
            return Independence.UNOBSERVED;
        }
        String actor = checked.actor();
        List<String> chain = checked.parentChain();
        List<ObservedCaller> observed = implementers.stream().filter(item -> item != null && item.hostObserved()).toList();
        if (observed.stream().anyMatch(item -> Objects.equals(item.actor(), actor) || chain.contains(item.actor()))) {
            return Independence.VIOLATED;
        }
        return !implementers.isEmpty() && observed.size() == implementers.size() ? Independence.VERIFIED : Independence.UNOBSERVED;
    }

    /**
     * The part of a provider's output a gate reads, bound to its record; null where the output is none. An output of another shape than the gate's record is the
     * gate's failure, which {@code refusal} makes of the binding failure.
     */
    private <T> T gateInput(Class<T> type, JsonNode output, Function<JacksonException, WorkflowFailure> refusal) {
        if (output == null || output.isNull()) {
            return null;
        }
        try {
            return mapper.treeToValue(output, type);
        } catch (JacksonException unbound) {
            throw refusal.apply(unbound);
        }
    }

    /**
     * What recording a stage result judges from files and attachments, as the requests the entry answers before the transaction: the output file, and what {@link #requestsToJudge}
     * says. The attachments the artifacts and the evidence name are looked at in the transaction, from their rows ({@link EvidenceAttachments}).
     */
    List<ReadRequest> requestsToRecord(WorkflowPlan.Stage stage, StageResult result, String taskDigest) {
        List<ReadRequest> requests = new ArrayList<>();
        // Receipt policies compare actors across stages through the stored output, so a policy stage's output stays inline: the use case refuses an output file there, before it reads it.
        if (result.hasOutputFile() && stage.receiptPolicy() == null) {
            requests.add(new ReadRequest.Document(new AttachmentFileRef(result.outputFile().attachmentId(), result.outputFile().seq()), result.outputFile().digest()));
        }
        requestsToJudge(requests, stage, result, taskDigest, true);
        return requests;
    }

    /**
     * What the finish judges again, for a stage whose result was recorded: the result's checks as {@link #requestsToJudge} says, the output file, and the attachments an audit was
     * recorded on (each must still be there with the recorded sha256 and be read whole).
     */
    List<ReadRequest> requestsToFinalize(WorkflowPlan.Stage stage, StageResult result, String taskDigest) {
        List<ReadRequest> requests = new ArrayList<>();
        if (rechecksBundles(stage)) {
            if (result.hasOutputFile()) {
                requests.add(new ReadRequest.Document(new AttachmentFileRef(result.outputFile().attachmentId(), result.outputFile().seq()), result.outputFile().digest()));
            }
            requestsToJudge(requests, stage, result, taskDigest, false);
            if (mandatoryAudit(stage) && result.artifactChecks() != null) {
                for (ArtifactCheckEntry check : result.artifactChecks()) {
                    String recorded = check.check() == ArtifactCheck.SHA256_MATCHED ? recordedDigest(result, check) : null;
                    if (recorded != null) {
                        requests.add(new ReadRequest.Stored(new AttachmentFileRef(check.attachmentId(), check.seq()), evidenceKey(recorded)));
                    }
                }
            }
        } else if (stage.receiptPolicy() != null) {
            requests.add(new ReadRequest.DeclaredSchema(stage.outputSchema(), ContractValidator.REFERENCE_ONLY_OUTPUT));
        }
        return requests;
    }

    /**
     * What the gates that judge a result read: the schemas the plan froze for it, the check of a review bundle, the frozen acceptance request, and, for a stage with a receipt policy, the
     * schema its fixed tokens come from. {@code deliberation}: the gate of a deliberation's decision record is judged too (recording judges it, the finish does not).
     */
    private void requestsToJudge(List<ReadRequest> requests, WorkflowPlan.Stage stage, StageResult result, String taskDigest, boolean deliberation) {
        requests.add(new ReadRequest.DeclaredSchema(stage.resultSchema(), ContractValidator.PROVIDER_RESULT));
        if (result.hasOutputFile() || result.output().hasOutput()) {
            requests.add(new ReadRequest.DeclaredSchema(stage.outputSchema(), ContractValidator.PROVIDER_OUTPUT));
        }
        if (deliberation && stage.requiredArtifacts().contains(DECISION_RECORD_ARTIFACT) && stage.gate().validatorSchema() != null) {
            requests.add(new ReadRequest.DeclaredSchema(stage.gate().validatorSchema(), ContractValidator.DECISION_RECORD));
        }
        if (stage.receiptPolicy() != null) {
            requests.add(new ReadRequest.DeclaredSchema(stage.outputSchema(), ContractValidator.REFERENCE_ONLY_OUTPUT));
        }
        providerSemantics.request(stage, result, taskDigest).ifPresent(requests::add);
        bundleChecks.request(stage, result, taskDigest).ifPresent(requests::add);
    }

    /** The provider's declared schema, its state mapping and the error it may carry. */
    void assertResultSemantics(WorkflowPlan.Stage stage, StageResult result, String taskId, String taskDigest, FileReads reads) {
        ProviderResult output = result.output();
        validator.providerResult(reads.schema(stage.resultSchema(), ContractValidator.PROVIDER_RESULT), () -> reads.schema(stage.outputSchema(), ContractValidator.PROVIDER_OUTPUT), output);
        if (output.kind() == OutputKind.OUTPUT) {
            providerSemantics.check(stage, result, taskId, taskDigest, reads);
        }
        ResolvedRule rule = mappedState(stage, output);
        RunState state = result.state();
        String stageId = result.stageId();
        if (state != rule.state()) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.stageStateMatchProviderState"), details("stageId", stageId, "expectedState", rule.state(), "actualState", state));
        }
        RecordedError providerError = output.error();
        if (rule.errorRequired() != (providerError != null)) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.providerErrorPresenceMatchState"), details("stageId", stageId, "errorRequired", rule.errorRequired()));
        }
        if (providerError != null && rule.allowedCodes() != null && !allows(rule.allowedCodes(), providerError.code())) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.providerErrorCodeAllowedState"), details("stageId", stageId, "errorCode", providerError.code()));
        }
        RecordedError stageError = result.error();
        if (!Objects.equals(providerError == null ? null : providerError.code(), stageError == null ? null : stageError.code())) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.stageErrorMirrorProviderResult"), details("stageId", stageId));
        }
        if (state == RunState.PASSED) {
            if (result.evidence().isEmpty()) {
                throw new WorkflowFailure(WorkflowCode.MISSING_EVIDENCE, msg("workflow.service.passedStageRequiresEvidence"), details("stageId", stageId));
            }
            if (stageError != null) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.passedStageContainError"));
            }
        }
        if ((state == RunState.FAILED || state == RunState.BLOCKED) && rule.errorRequired() && stageError == null) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.failedBlockedStagesRequireError"));
        }
    }

    /** The mapping allows this code; a code that no constant names is never allowed. */
    private static boolean allows(List<WorkflowCode> allowed, WorkflowCode code) {
        return code != null && allowed.contains(code);
    }

    private record ResolvedRule(RunState state, boolean errorRequired, List<WorkflowCode> allowedCodes) {
    }

    private ResolvedRule mappedState(WorkflowPlan.Stage stage, ProviderResult output) {
        WorkflowPlan.StateMapping mapping = stage.stateMapping();
        String stageId = stage.stageId();
        if (output.kind() == OutputKind.ADAPTER_ERROR) {
            WorkflowCode code = output.error() == null ? null : output.error().code();
            List<WorkflowCode> allowed = mapping.adapterErrors();
            if (!allows(allowed, code)) {
                throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.adapterErrorAllowedProviderDescriptor"), details("stageId", stageId, "errorCode", code));
            }
            return new ResolvedRule(RunState.BLOCKED, true, allowed);
        }
        // The plan schema allows the default to be a state rule or the single text "reject", which is a rule-less default.
        WorkflowPlan.MappingRule chosen = mapping.fallback().rule();
        String selector = mapping.selector();
        if (selector != null) {
            // The selector is a JSON Pointer the skill's descriptor declared; what it names in the output is the skill's own value.
            // A mapping that names a selector never passes a result that lacks the value or holds a non-text value: the default serves a mapping with no selector,
            // so a missing status is refused rather than recorded as a pass.
            JsonNode value = output.at(selector, mapper);
            chosen = value != null && value.isString() && mapping.values() != null ? mapping.values().get(value.asString()) : null;
        }
        if (chosen == null) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.providerVerdictMappedDescriptor"), details("stageId", stageId, "selector", selector));
        }
        return new ResolvedRule(chosen.state(), chosen.errorRequired(), chosen.allowedErrorCodes());
    }

    /** A supplied bundle is judged by its skill's check, over the attachments of the result; one that was never judged is never accepted. */
    void assertStageBundles(WorkflowPlan.Stage stage, StageResult result, String taskDigest, FileReads reads) {
        bundleChecks.check(stage, result, taskDigest, reads);
    }

    void assertRequiredArtifacts(WorkflowPlan.Stage stage, StageResult result) {
        List<ProviderArtifact> artifacts = result.output().artifacts();
        String stageId = stage.stageId();
        List<String> required = stage.requiredArtifacts();
        List<String> missing = required.stream().filter(id -> artifacts.stream().noneMatch(artifact -> Objects.equals(id, artifact.artifactId()))).toList();
        if (!missing.isEmpty()) {
            throw new WorkflowFailure(WorkflowCode.MISSING_EVIDENCE, msg("workflow.service.requiredArtifactsMissingStageResult"), details("stageId", stageId, "missingArtifacts", missing));
        }
        List<TaskEnvelope.ProducedArtifact> declared = stage.artifactContract() == null ? List.of() : stage.artifactContract().produced();
        for (String artifactId : required) {
            TaskEnvelope.ProducedArtifact expected = declared.stream().filter(candidate -> Objects.equals(artifactId, candidate.artifactId())).reduce((first, second) -> second)
                    .orElseThrow(() -> new WorkflowFailure(WorkflowCode.MISSING_EVIDENCE, msg("workflow.service.requiredArtifactLacksDeclaredTarget"), details("stageId", stageId, "artifactId", artifactId)));
            List<ProviderArtifact> actual = artifacts.stream().filter(artifact -> Objects.equals(artifactId, artifact.artifactId())).toList();
            if (actual.size() != 1 || isBlank(actual.get(0).attachmentId()) || isBlank(actual.get(0).digest())) {
                throw new WorkflowFailure(WorkflowCode.MISSING_EVIDENCE, msg("workflow.service.requiredArtifactLacksDeclaredTarget"), details("stageId", stageId, "artifactId", artifactId));
            }
            String digest = sha256Form(actual.get(0).targetDigest());
            if (!Objects.equals(actual.get(0).schemaId(), expected.schemaId()) || !Objects.equals(digest, expected.targetDigest())) {
                throw new WorkflowFailure(WorkflowCode.BINDING_INVALID, msg("workflow.service.artifactSchemaTargetDiffersSigned"), details("stageId", stageId, "artifactId", artifactId));
            }
        }
    }

    private static boolean isBlank(String text) {
        return text == null || text.isEmpty();
    }

    void assertPlannedInputsAvailable(WorkflowReceipt receipt, WorkflowPlan plan, WorkflowPlan.Stage stage, boolean requireDeclaredTarget) {
        String stageId = stage.stageId();
        List<Map<String, Object>> missing = new ArrayList<>();
        for (String artifactId : stage.requiredInputArtifacts()) {
            // An input from outside the run (no producer in the plan) is not checked here; unverifiedClaims records it as evidence-content.
            plan.stages().stream().filter(candidate -> !Objects.equals(stageId, candidate.stageId()) && candidate.producedArtifacts().contains(artifactId)).findFirst().ifPresent(producer -> {
                Optional<StageResult> passed = receipt.resultOf(producer.stageId()).filter(StageResult::passed);
                if (requireDeclaredTarget) {
                    passed.ifPresent(producerResult -> assertRequiredArtifacts(producer, producerResult));
                }
                boolean verified = passed.map(producerResult -> producerResult.output().artifacts().stream().anyMatch(artifact -> Objects.equals(artifactId, artifact.artifactId())
                        && !isBlank(artifact.attachmentId()) && !isBlank(artifact.digest()) && !isBlank(artifact.targetDigest()))).orElse(false);
                if (!verified) {
                    missing.add(details("artifactId", artifactId, "producerStageId", producer.stageId()));
                }
            });
        }
        if (!missing.isEmpty()) {
            throw new WorkflowFailure(WorkflowCode.MISSING_EVIDENCE, msg("workflow.service.plannedInputArtifactsBackedProducer"), details("stageId", stageId, "missingInputs", missing));
        }
    }

    /**
     * The decision record must be schema-valid, semantically valid and eligible to advance the workflow.
     *
     * @return whether the record concluded with a consensus on conditions, which the receipt lists as unverified; false for a stage that needs no decision record
     */
    boolean assertDeliberationGate(WorkflowPlan.Stage stage, StageResult result, FileReads reads) {
        if (!stage.requiredArtifacts().contains(DECISION_RECORD_ARTIFACT)) {
            return false;
        }
        String stageId = stage.stageId();
        WorkflowPlan.SchemaReference schema = stage.gate().validatorSchema();
        if (schema == null) {
            throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.deliberationProviderDeclareDecisionRecord"), details("stageId", stageId));
        }
        ProviderOutputs.Deliberation output = gateInput(ProviderOutputs.Deliberation.class, result.output().output(), cause -> deliberationNotSchemaValid(stageId, cause));
        // The decision record is the panel skill's own document: its schema and its semantic rules judge it, as a tree.
        JsonNode record = output == null || output.decisionRecord() == null ? MissingNode.getInstance() : output.decisionRecord();
        try {
            validator.declaredSchema(reads.schema(schema, ContractValidator.DECISION_RECORD), record, ContractValidator.DECISION_RECORD);
        } catch (WorkflowFailure error) {
            Map<String, Object> cause = new LinkedHashMap<>();
            if (error.details() != null) {
                SCHEMA_CAUSE_DETAIL_KEYS.forEach(key -> {
                    if (error.details().containsKey(key)) {
                        cause.put(key, error.details().get(key));
                    }
                });
            }
            throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.deliberationRequiresSchemaValidDecisionRecord"),
                    details("stageId", stageId, "causeCode", error.code(), "causeDetails", cause.isEmpty() ? null : cause, "cause", error.getMessage()), error);
        }
        List<String> semanticErrors = DecisionRecordSemantics.validate(record, mapper);
        if (!semanticErrors.isEmpty()) {
            // A reached repetition limit is not a slip to retry: the message tells the panel to re-examine its direction and to finish.
            MessageRef message = semanticErrors.stream().anyMatch(DecisionRecordSemantics::isTerminationCap)
                    ? msg("workflow.service.decisionRecordTerminationCapReached") : msg("workflow.service.decisionRecordV1FailedCanonicalSemantic");
            throw new WorkflowFailure(WorkflowCode.GATE_FAILED, message, details("stageId", stageId, "semanticErrors", semanticErrors));
        }
        ProviderOutputs.Decision decision = gateInput(ProviderOutputs.Decision.class, record, cause -> deliberationNotSchemaValid(stageId, cause));
        ProviderOutputs.Decision.Run run = decision.run();
        DecisionTerms.ConsensusStatus status = decision.consensusProposal() == null ? null : decision.consensusProposal().status();
        DecisionTerms.Assurance assurance = run.assurance();
        if (assurance == DecisionTerms.Assurance.PROVISIONAL || run.capabilityShortfall() == null || run.capabilityShortfall() || decision.consensusProposal() == null || status == DecisionTerms.ConsensusStatus.NO_CONSENSUS) {
            throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.deliberationResultEligibleAdvanceWorkflow"),
                    details("stageId", stageId, "assurance", assurance, "capabilityShortfall", run.capabilityShortfall(), "consensusStatus", status));
        }
        DecisionTerms.Stage runStage = run.stage();
        if (runStage != null && runStage.isHighOrCritical() && !Boolean.TRUE.equals(run.strict())) {
            throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.highCriticalDeliberationRequiresStrict"), details("stageId", stageId, "stage", runStage));
        }
        if (status == DecisionTerms.ConsensusStatus.CONDITIONAL_CONSENSUS && !Boolean.TRUE.equals(output.conditionsVerified())) {
            throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.conditionalConsensusAdvanceAfterConditions"), details("stageId", stageId));
        }
        return status == DecisionTerms.ConsensusStatus.CONDITIONAL_CONSENSUS;
    }

    private static WorkflowFailure deliberationNotSchemaValid(String stageId, JacksonException cause) {
        return new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.deliberationRequiresSchemaValidDecisionRecord"), details("stageId", stageId, "cause", cause.getOriginalMessage()), cause);
    }

    void assertEvaluationValidityGate(WorkflowPlan.Stage stage, StageResult result) {
        if (!KnownCapability.EVALUATION_VALIDITY_AUDIT.is(stage.requiredCapability())) {
            return;
        }
        // A v1 report carries only self-asserted actor UUIDs and no trust boundary observes worker separation, so no PASS can complete this stage.
        ProviderOutputs.Evaluation report = gateInput(ProviderOutputs.Evaluation.class, result.output().output(), cause -> evaluationRefused(stage, null, cause));
        throw evaluationRefused(stage, report, null);
    }

    private static WorkflowFailure evaluationRefused(WorkflowPlan.Stage stage, ProviderOutputs.Evaluation report, JacksonException cause) {
        return new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.evaluationValidityPASSCompleteStage"),
                details("stageId", stage.stageId(), "cause", "WORKER_INDEPENDENCE_UNOBSERVED", "purpose", stage.evaluationAuditPurpose(), "auditStage", report == null ? null : report.auditStage(),
                        "verdict", report == null ? null : report.verdict(), "qualifiesAsQualityOrReleaseEvidence", report == null ? null : report.qualifiesAsQualityOrReleaseEvidence()),
                cause);
    }

    /** What Sennard can see: the frozen phase, the audited target against the signed plan, and the observed actors. */
    void assertMandatoryAuditGate(WorkflowPlan.Stage stage, StageResult result, List<String> observedActors) {
        if (!mandatoryAudit(stage)) {
            return;
        }
        String stageId = stage.stageId();
        AuditPhase requiredPhase = PlanBuilder.requireAuditPhase(stage);
        ProviderOutputs.Audit audit = gateInput(ProviderOutputs.Audit.class, result.output().output(), cause -> auditRefused(stageId, requiredPhase, cause));
        if (audit == null || audit.gateVerdict() != GateVerdict.PASS || isBlank(audit.auditorId()) || !audit.namesImplementers() || isBlank(audit.auditTarget())
                || !Boolean.TRUE.equals(audit.freshContext()) || !Boolean.FALSE.equals(audit.delegationAllowed())
                || audit.blockingFindings() == null || !audit.blockingFindings().isEmpty()
                || !Boolean.FALSE.equals(audit.stale())
                || audit.phase() != requiredPhase
                || audit.postExecutionVerified() == null) {
            throw auditRefused(stageId, requiredPhase, null);
        }
        if (requiredPhase.isAfterExecution() && !audit.postExecutionVerified()) {
            throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.postExecutionPostDeployAudits"), details("stageId", stageId, "phase", requiredPhase));
        }
        String verdictTarget = null;
        for (ProviderArtifact artifact : result.output().artifacts()) {
            if (KnownArtifact.GATE_VERDICT.is(artifact.artifactId()) && verdictTarget == null) {
                verdictTarget = artifact.targetDigest();
            }
        }
        if (verdictTarget == null || !Objects.equals(evidenceKey(audit.auditTarget()), evidenceKey(verdictTarget))) {
            throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.auditTargetSignedPlanTarget"), details("stageId", stageId, "reason", "AUDIT_TARGET_NOT_PLANNED"));
        }
        if (audit.implementationActorIds().contains(audit.auditorId())) {
            throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.declaredAuditorAlsoDeclaredImplementation"), details("stageId", stageId, "auditorId", audit.auditorId(),
                    "implementationActorIds", audit.implementationActorIds()));
        }
        if (observedActors.contains(audit.auditorId())) {
            throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.declaredAuditorActorSennardObserved"), details("stageId", stageId, "reason", "AUDITOR_IS_OBSERVED_ROOT"));
        }
    }

    private static WorkflowFailure auditRefused(String stageId, AuditPhase requiredPhase, JacksonException cause) {
        return new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.mandatoryAuditRequiresDeclaredCurrent"), details("stageId", stageId, "requiredPhase", requiredPhase), cause);
    }

    /**
     * An attachment the audit stage was recorded on (its sha256 matched the digest the stage declared) must still be there with that sha256 and be read whole. Attachments are not
     * rewritten, so the bytes of the audit cannot have been swapped as a local file could be; what the finish can still find is that they were lost or damaged.
     */
    void assertAuditedAttachmentsIntact(WorkflowPlan.Stage stage, StageResult result, FileReads reads) {
        if (!mandatoryAudit(stage) || result.artifactChecks() == null) {
            return;
        }
        for (ArtifactCheckEntry check : result.artifactChecks()) {
            if (check.check() != ArtifactCheck.SHA256_MATCHED) {
                continue;
            }
            String recorded = recordedDigest(result, check);
            if (recorded == null || reads.stored(new AttachmentFileRef(check.attachmentId(), check.seq()), evidenceKey(recorded)) != AttachmentContents.Standing.INTACT) {
                throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.auditedAttachmentNotIntact"),
                        details("stageId", stage.stageId(), "artifactId", check.artifactId(), "attachmentId", check.attachmentId(), "seq", check.seq(), "reason", "AUDIT_TARGET_UNAVAILABLE"));
            }
        }
    }

    /** The digest the result recorded for the item a check is about; null where the item has none. */
    static String recordedDigest(StageResult result, ArtifactCheckEntry check) {
        switch (check.source()) {
            case ARTIFACT -> {
                for (ProviderArtifact item : result.output().artifacts()) {
                    if (Objects.equals(check.artifactId(), item.artifactId()) && Objects.equals(check.attachmentId(), item.attachmentId()) && check.seq() == item.seq()) {
                        return item.digest();
                    }
                }
            }
            case EVIDENCE -> {
                for (Evidence item : result.evidence()) {
                    if (Objects.equals(check.artifactId(), item.artifactId()) && Objects.equals(check.attachmentId(), item.attachmentId()) && check.seq() == item.seq()) {
                        return item.digest();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Compares the files the artifacts and the evidence name with the digests the caller declared. {@code attachmentDigests} is the sha256 each file has, by attachment id and number, as
     * {@link EvidenceAttachments#resolve} read it from the rows in this transaction. A passing stage cannot record a digest the attachment contradicts; any other stage records the
     * item as {@code NOT_CHECKED}.
     */
    List<ArtifactCheckEntry> checkArtifacts(WorkflowPlan.Stage stage, StageResult result, Map<AttachmentFileRef, String> attachmentDigests) {
        List<ArtifactCheckEntry> checks = new ArrayList<>();
        boolean passed = result.passed();
        for (ProviderArtifact artifact : result.output().artifacts()) {
            checks.add(check(stage, CheckSource.ARTIFACT, artifact.artifactId(), new AttachmentFileRef(artifact.attachmentId(), artifact.seq()), artifact.digest(), passed, attachmentDigests));
        }
        for (Evidence item : result.evidence()) {
            checks.add(check(stage, CheckSource.EVIDENCE, item.artifactId(), new AttachmentFileRef(item.attachmentId(), item.seq()), item.digest(), passed, attachmentDigests));
        }
        return checks;
    }

    private static ArtifactCheckEntry check(WorkflowPlan.Stage stage, CheckSource source, String artifactId, AttachmentFileRef file, String digest, boolean passed, Map<AttachmentFileRef, String> attachmentDigests) {
        String actual = attachmentDigests.get(file);
        if (actual == null) {
            throw new IllegalStateException("The file " + file.seq() + " of the attachment " + file.attachmentId() + " was not resolved before its check");
        }
        if (!Objects.equals(actual, evidenceKey(digest))) {
            if (passed) {
                throw new WorkflowFailure(WorkflowCode.BINDING_INVALID, msg("workflow.service.recordedDigestMatchAttachment"), details("stageId", stage.stageId(), "artifactId", artifactId, "attachmentId", file.attachmentId(), "seq", file.seq(), "reason", "ARTIFACT_DIGEST_MISMATCH"));
            }
            return new ArtifactCheckEntry(source, artifactId, file.attachmentId(), file.seq(), ArtifactCheck.NOT_CHECKED);
        }
        return new ArtifactCheckEntry(source, artifactId, file.attachmentId(), file.seq(), ArtifactCheck.SHA256_MATCHED);
    }

    void assertDeclaredReceiptPolicy(WorkflowReceipt receipt, WorkflowPlan plan, WorkflowPlan.Stage stage, StageResult result, FileReads reads) {
        if (stage.receiptPolicy() != null) {
            ReceiptPolicy.enforce(receipt, plan, stage, result, validator.referenceOnlyFixedTokens(reads.schema(stage.outputSchema(), ContractValidator.REFERENCE_ONLY_OUTPUT)), mapper);
        }
    }

    /** The JSON object of a stage output file, as the entry read it with the digest checked. */
    JsonNode loadOutputFile(OutputFile reference, FileReads reads) {
        return reads.document(new AttachmentFileRef(reference.attachmentId(), reference.seq()), reference.digest());
    }

    /** The result with its file-backed output loaded, for checks that read the output; the receipt keeps only the reference. */
    StageResult withLoadedOutput(StageResult result, JsonNode loaded) {
        return result.withOutput(result.output().withOutput(loaded));
    }

    void assertExecutionAssurance(WorkflowPlan.Stage stage, StageResult result, ExecutionAssurance assurance) {
        if (stage.executionRequirement() != null && result.passed()) {
            ExecutionAssurance.Subject subject = ExecutionAssurance.stageSubject(stage.stageId());
            assurance.assertContext(stage.executionRequirement(), result.executionContext(), subject);
        }
    }
}
