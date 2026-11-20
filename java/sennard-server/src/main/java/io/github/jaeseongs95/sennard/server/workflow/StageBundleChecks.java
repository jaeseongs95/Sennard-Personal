// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileRef;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A review stage's supplied files are judged by that skill's check, and the stage is accepted only when what it reports is what the check
 * read. The files of a bundle are attachments (AGS3-D-0264): the manifest and the report are artifacts of the result and the raw files it names are its evidence, each with its
 * declared digest, and the file name of an attachment is its path under the bundle's folder. The check reads the bundle's bounded files, so it runs before the transaction of the
 * recording (the entry puts the attachments in a folder of its own, runs the check there and answers {@link #request}); the verdict is judged here against the output the stage
 * reports, inside it.
 */
@Component
class StageBundleChecks {
    private static final String CHECK = "check-stage-bundle";

    /**
     * The stages whose result is judged here: the capability that names the stage, the skill whose check judges it, the artifact that carries its bundle and the one
     * that carries the report the check read. Both engineering stages share one check and differ in the capability they pass it.
     */
    enum BundleStage {
        CS("cs-implementation-review", null, "CS stage bundle", "cs-review-bundle", "cs-review-report"),
        TEST("test-sensitivity-review", "test-engineering", "Engineering stage", "engineering-stage-bundle", "engineering-test-result"),
        CODE("change-code-review", "code-review", "Engineering stage", "engineering-stage-bundle", "engineering-review-result");

        private final String capability;
        private final String skill;
        private final String label;
        private final String bundleArtifact;
        private final String reportArtifact;

        BundleStage(String capability, String skill, String label, String bundleArtifact, String reportArtifact) {
            this.capability = capability;
            this.skill = skill;
            this.label = label;
            this.bundleArtifact = bundleArtifact;
            this.reportArtifact = reportArtifact;
        }

        /** The stage a capability names, or none for a capability whose result is not judged here. */
        static Optional<BundleStage> of(String capability) {
            return Arrays.stream(values()).filter(stage -> stage.capability.equals(capability)).findFirst();
        }
    }

    /** What is read of a check's answer when it is not a verdict: the code and the sentence of the refusal of its input. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Answer(String code, String message, JsonNode value) {
    }

    /** A file a check names: its path under the bundle's folder and the digest of its bytes. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Ref(String path, String digest) {
    }

    /** What the workflow reads of the CS check's verdict: the task it judged for, the review it read, where it read it and the candidate it bound. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CsAssessment(@JsonFormat(with = JsonFormat.Feature.READ_UNKNOWN_ENUM_VALUES_AS_NULL) AssessmentKind kind, JsonNode task, JsonNode review, Ref reviewRef, String candidateDigest) {
    }

    /** What the workflow reads of an engineering check's verdict: the task it judged for, the result it read, where it read it and the target it bound. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EngineeringAssessment(@JsonFormat(with = JsonFormat.Feature.READ_UNKNOWN_ENUM_VALUES_AS_NULL) AssessmentKind kind, JsonNode task, JsonNode result, Ref resultRef, String targetDigest) {
    }

    private final ContractValidator validator;
    private final JsonMapper mapper;

    StageBundleChecks(ContractValidator validator, JsonMapper mapper) {
        this.validator = validator;
        this.mapper = mapper;
    }

    /** Whether this stage's result is judged here. */
    static boolean covers(String capability) {
        return BundleStage.of(capability).isPresent();
    }

    /**
     * The check this stage's result needs run, as the request the entry answers before the transaction (the check reads the bundle's files: the entry puts the attachments of the
     * result in a folder of its own and runs the check there). Nothing is asked where the result is not judged here, or names no verified bundle attachment: {@link #check} then
     * refuses it, in the order its own checks give.
     */
    Optional<ReadRequest.BundleCheck> request(WorkflowPlan.Stage stage, StageResult result, String taskDigest) {
        ProviderResult output = result.output();
        Optional<BundleStage> covered = BundleStage.of(stage.requiredCapability());
        if (covered.isEmpty() || output.kind() != OutputKind.OUTPUT || taskDigest == null) {
            return Optional.empty();
        }
        BundleStage kind = covered.get();
        ProviderArtifact reference = single(output, kind.bundleArtifact);
        if (reference == null || !reference.verified()) {
            return Optional.empty();
        }
        return Optional.of(bundleCheck(kind, stage, result, reference, taskDigest));
    }

    /**
     * The check of a bundle: the CS review is judged by the stage validator its provider declares, the engineering stages by their skill. The files of the bundle are the attachments
     * the result names (its artifacts and its evidence): each one's file name is its path under the bundle's folder. Several of them may be files of one attachment.
     */
    private ReadRequest.BundleCheck bundleCheck(BundleStage kind, WorkflowPlan.Stage stage, StageResult result, ProviderArtifact reference, String taskDigest) {
        boolean cs = kind == BundleStage.CS;
        return new ReadRequest.BundleCheck(cs ? stage.providerKey() : null, cs ? null : kind.skill, CHECK, new AttachmentFileRef(reference.attachmentId(), reference.seq()), files(result),
                reference.digest() == null ? "" : reference.digest(), taskDigest, cs ? null : kind.capability);
    }

    /** The files of a result, each once: the artifacts' first, then the evidence's. */
    private static List<AttachmentFileRef> files(StageResult result) {
        Set<AttachmentFileRef> ids = new LinkedHashSet<>();
        result.output().artifacts().forEach(artifact -> ids.add(new AttachmentFileRef(artifact.attachmentId(), artifact.seq())));
        result.evidence().forEach(item -> ids.add(new AttachmentFileRef(item.attachmentId(), item.seq())));
        return List.copyOf(ids);
    }

    void check(WorkflowPlan.Stage stage, StageResult result, String taskDigest, FileReads reads) {
        ProviderResult output = result.output();
        if (output.kind() != OutputKind.OUTPUT) {
            return;
        }
        BundleStage.of(stage.requiredCapability()).ifPresent(bundleStage -> {
            switch (bundleStage) {
                case CS -> checkCs(stage, result, taskDigest, reads);
                case TEST, CODE -> checkEngineering(stage, bundleStage, result, taskDigest, reads);
            }
        });
    }

    private void checkCs(WorkflowPlan.Stage stage, StageResult result, String taskDigest, FileReads reads) {
        ProviderResult output = result.output();
        BundleStage kind = BundleStage.CS;
        if (taskDigest == null) {
            throw new WorkflowFailure(WorkflowCode.BINDING_REQUIRED, msg("workflow.cs.csReviewRequiresSignedTask"));
        }
        ProviderArtifact reference = reference(output, kind, "workflow.cs.csReviewRequiresVerifiedLocal");
        ReadRequest.CheckAnswer found = reads.check(bundleCheck(kind, stage, result, reference, taskDigest));
        if (found instanceof ReadRequest.NoValidator) {
            throw unavailable(kind, "STAGE_VALIDATOR_UNDECLARED", details("step", "declaration", "reason", "STAGE_VALIDATOR_UNDECLARED", "providerKey", stage.providerKey()));
        }
        CsAssessment assessment = assessment(kind, stage, found, CsAssessment.class);
        if (assessment.kind() != AssessmentKind.CS_BUNDLE_CHECK) {
            throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.cs.csStageBundleCouldValidated"), details("step", "kind", "kind", assessment.kind()));
        }
        assertTask(assessment.task());
        if (!sameJson(assessment.review(), output.output())) {
            throw new WorkflowFailure(WorkflowCode.INTEGRITY_FAILED, msg("workflow.cs.csStageOutputDiffersValidated"));
        }
        ProviderArtifact report = single(output, kind.reportArtifact);
        if (!bound(report, assessment.reviewRef(), pathsOf(found), assessment.candidateDigest()) || !Objects.equals(reference.targetDigest(), assessment.candidateDigest())) {
            throw new WorkflowFailure(WorkflowCode.INTEGRITY_FAILED, msg("workflow.cs.csReportArtifactBundleName"));
        }
    }

    private void checkEngineering(WorkflowPlan.Stage stage, BundleStage kind, StageResult result, String taskDigest, FileReads reads) {
        ProviderResult output = result.output();
        if (taskDigest == null) {
            throw new WorkflowFailure(WorkflowCode.BINDING_REQUIRED, msg("workflow.engineering.engineeringReviewRequiresSignedTask"));
        }
        ProviderArtifact reference = reference(output, kind, "workflow.engineering.engineeringReviewRequiresVerifiedLocal");
        ReadRequest.CheckAnswer found = reads.check(bundleCheck(kind, stage, result, reference, taskDigest));
        EngineeringAssessment assessment = assessment(kind, stage, found, EngineeringAssessment.class);
        if (assessment.kind() != AssessmentKind.ENGINEERING_STAGE_CHECK) {
            throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.engineering.engineeringStageFilesCouldValidated"), details("step", "kind", "kind", assessment.kind()));
        }
        assertTask(assessment.task());
        if (!sameJson(assessment.result(), output.output())) {
            throw new WorkflowFailure(WorkflowCode.INTEGRITY_FAILED, msg("workflow.engineering.providerOutputDiffersValidatedRaw"));
        }
        ProviderArtifact report = single(output, kind.reportArtifact);
        if (!bound(report, assessment.resultRef(), pathsOf(found), assessment.targetDigest()) || !Objects.equals(reference.targetDigest(), assessment.targetDigest())) {
            throw new WorkflowFailure(WorkflowCode.INTEGRITY_FAILED, msg("workflow.engineering.engineeringArtifactsBindValidatedResult"));
        }
    }

    /** A check that names no task fails the same contract as one that names a malformed task. */
    private void assertTask(JsonNode task) {
        validator.planWorkflowRequest(task == null ? mapper.createObjectNode() : task);
    }

    private static boolean sameJson(JsonNode judged, JsonNode output) {
        return judged != null && output != null && CanonicalJson.of(judged).equals(CanonicalJson.of(output));
    }

    /** The one artifact of that ID the result names; null where it names none or several. */
    private static ProviderArtifact single(ProviderResult output, String artifactId) {
        List<ProviderArtifact> found = output.artifacts().stream().filter(artifact -> Objects.equals(artifactId, artifact.artifactId())).toList();
        return found.size() == 1 ? found.getFirst() : null;
    }

    /** The one verified bundle attachment the stage names. */
    private static ProviderArtifact reference(ProviderResult output, BundleStage kind, String missingKey) {
        ProviderArtifact reference = single(output, kind.bundleArtifact);
        if (reference == null || !reference.verified()) {
            throw new WorkflowFailure(WorkflowCode.MISSING_EVIDENCE, msg(missingKey));
        }
        return reference;
    }

    /** The path under the bundle's folder of each attachment the check ran over, as the entry put them there. */
    private static Map<AttachmentFileRef, String> pathsOf(ReadRequest.CheckAnswer found) {
        return found instanceof ReadRequest.Checked checked ? checked.paths() : Map.of();
    }

    /** The report artifact is the file the check read (the attachment whose path under the bundle's folder is the one the check read), with the digest and the target the check computed. */
    private static boolean bound(ProviderArtifact report, Ref read, Map<AttachmentFileRef, String> paths, String target) {
        return report != null && report.verified() && read != null && Objects.equals(report.digest(), read.digest()) && read.path() != null
                && Objects.equals(paths.get(new AttachmentFileRef(report.attachmentId(), report.seq())), read.path()) && Objects.equals(report.targetDigest(), target);
    }

    /** A bundle that was never judged is never accepted, so without the skills module the stage is refused. The check itself ran before the transaction, with the entry's request. */
    private <T> T assessment(BundleStage kind, WorkflowPlan.Stage stage, ReadRequest.CheckAnswer found, Class<T> type) {
        SkillChecks.Result outcome = switch (found) {
            case ReadRequest.Checked checked -> checked.result();
            case ReadRequest.SkillsModuleAbsent absent -> throw notPrepared(kind, stage);
            case ReadRequest.NoValidator none -> throw notPrepared(kind, stage);
        };
        Answer answer = answer(kind, outcome);
        // A verdict of the stage's own is 0 (it holds) or 1 (it does not); 2 is an input the check refused.
        if (outcome.exitCode() != 0 && outcome.exitCode() != 1) {
            WorkflowCode child = known(answer.code());
            throw new WorkflowFailure(child, answer.message() != null ? msg("workflow.validatorProcess.childFailure", "message", answer.message())
                    : msg("workflow.validatorProcess.exitStatus", "label", kind.label, "status", String.valueOf(outcome.exitCode())),
                    details("step", "exit", "status", outcome.exitCode(), "childCode", answer.code()));
        }
        JsonNode value = answer.value();
        if (value == null || !value.isObject()) {
            throw unavailable(kind, "VALIDATOR_OUTPUT_INVALID", details("step", "parse", "reason", "VALIDATOR_OUTPUT_INVALID", "status", outcome.exitCode()));
        }
        try {
            return mapper.treeToValue(value, type);
        } catch (JacksonException unbound) {
            throw new WorkflowFailure(WorkflowCode.MCP_UNAVAILABLE, msg("workflow.validatorProcess.unboundedOutput", "label", kind.label),
                    details("step", "parse", "reason", "VALIDATOR_OUTPUT_INVALID", "status", outcome.exitCode()), unbound);
        }
    }

    private Answer answer(BundleStage kind, SkillChecks.Result outcome) {
        try {
            return mapper.treeToValue(outcome.body(), Answer.class);
        } catch (JacksonException unbound) {
            throw new WorkflowFailure(WorkflowCode.MCP_UNAVAILABLE, msg("workflow.validatorProcess.unboundedOutput", "label", kind.label),
                    details("step", "parse", "reason", "VALIDATOR_OUTPUT_INVALID", "status", outcome.exitCode()), unbound);
        }
    }

    /** The public code a check names for a refused input; a code outside the contract is the gate's failure. */
    private static WorkflowCode known(String code) {
        return Arrays.stream(WorkflowCode.values()).filter(candidate -> candidate.name().equals(code)).findFirst().orElse(WorkflowCode.GATE_FAILED);
    }

    private static WorkflowFailure notPrepared(BundleStage kind, WorkflowPlan.Stage stage) {
        return unavailable(kind, "VALIDATOR_NOT_PREPARED", details("step", "prepare", "reason", "VALIDATOR_NOT_PREPARED", "stageId", stage.stageId()));
    }

    private static WorkflowFailure unavailable(BundleStage kind, String reason, Map<String, Object> details) {
        return new WorkflowFailure(WorkflowCode.MCP_UNAVAILABLE, msg("workflow.validatorProcess.couldNotRun", "label", kind.label, "reason", reason), details);
    }
}
