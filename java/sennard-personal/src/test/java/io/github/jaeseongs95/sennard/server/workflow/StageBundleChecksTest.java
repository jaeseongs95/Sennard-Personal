// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileRef;
import io.github.jaeseongs95.sennard.service.personal.LocalPrivateStorage;
import io.github.jaeseongs95.sennard.service.store.RunState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A review stage is accepted only when the skill's check read the same thing the stage reports. The files of a bundle are attachments (AGS3-D-0264): the check runs in the entry
 * ({@link WorkflowFileReader}) before the transaction and its answer reaches the rules as a {@link ReadRequest.Checked} (the skill's answer and the path under the bundle's folder
 * of each attachment the check ran over), which is what these cases feed to {@link FileReads} for the request {@link StageBundleChecks#request} makes. The rules are the real ones, the
 * registry, the contracts and the task are the real files; the answers are the shape the skills' check gives, with the exit codes it has (a verdict that holds is 0 and one that does not
 * is 1, for the CS review as for the engineering stages). That the entry puts the attachments in a folder and runs the check there is covered where the attachments are
 * ({@code serverit}) and by {@code BundleFolderTest}.
 *
 * <pre>
 * b1  request      it lists the attachments of the result (artifacts first, evidence second, each once), the bundle attachment, its declared digest and the signed task's digest; the capability is
 *                  null for the CS review and set for the engineering stages; nothing is asked of a stage that is not judged here, an unverified or missing or repeated bundle, an adapter error
 *                  or a missing task digest
 * b2  verdict      a verdict that holds or not (exit 0 or 1) is accepted; an exit outside the two keeps the check's code and sentence; the answer must be of the stage's kind, name a valid task,
 *                  hold a value of the shape the rules read
 * b3  same output  the output the stage reports is the output the check read; the report artifact is the attachment whose path under the bundle's folder is the path the check read, with the
 *                  digest and the target the check computed
 * b4  not judged   no skills module, no stage validator declared, no verified bundle, no signed task: the stage is refused, and the check is never asked for where the refusal comes first
 * b5  by file      an attachment is a bundle of files and the files of the review bundle are files of attachments ({@link AttachmentFileRef}): two evidence files of ONE attachment are two files
 *                  of the request, the bundle is named by the file its artifact points at, and the report is bound by the (attachment id, file number) path the check ran over: file 1 of
 *                  the attachment at the report's path is the report, file 0 of the same attachment at that path (or a file the check never ran over) is not
 * </pre>
 */
class StageBundleChecksTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String TASK_DIGEST = "sha256:" + "1".repeat(64);
    private static final String BUNDLE_DIGEST = "sha256:" + "2".repeat(64);
    private static final String TARGET = "sha256:" + "3".repeat(64);
    private static final String REPORT_DIGEST = "sha256:" + "4".repeat(64);
    private static final String FILE_DIGEST = "sha256:" + "5".repeat(64);
    /** The attachments of the bundles below: the uploads under the file names the check reads. */
    private static final String BUNDLE = "11111111-1111-4111-8111-111111111111";
    private static final String REPORT = "22222222-2222-4222-8222-222222222222";
    private static final String OTHER = "33333333-3333-4333-8333-333333333333";
    private static final String PROOF = "44444444-4444-4444-8444-444444444444";
    private static final String CS_STAGE = "cs-implementation-review";
    private static final String TEST_STAGE = "test-sensitivity-review";
    private static final String CODE_STAGE = "change-code-review";
    /** The path under the bundle's folder of each attachment, as the entry puts them there. */
    private static final Map<AttachmentFileRef, String> CS_PATHS = Map.of(file(BUNDLE), "bundle.json", file(REPORT), "review.json");
    private static final Map<AttachmentFileRef, String> ENGINEERING_PATHS = Map.of(file(BUNDLE), "stage.json", file(REPORT), "result.json");

    private static SkillRegistry registry;
    private static ContractValidator validator;
    private static StageBundleChecks bundleChecks;
    private static JsonNode task;
    private static String csProviderKey;

    @BeforeAll
    static void readRealContracts() throws IOException {
        ContentRoot content = new ContentRoot(PlanSamples.REPO);
        validator = new ContractValidator(MAPPER, content);
        registry = new SkillRegistry(content, validator, MAPPER);
        bundleChecks = new StageBundleChecks(validator, MAPPER);
        task = MAPPER.readTree(PlanSamples.REPO.resolve("skills/cs-engineering/assets/examples/claim-queue/task.json").toFile());
        csProviderKey = registry.read().stream().filter(provider -> provider.stageValidator() != null).findFirst().orElseThrow().providerKey();
    }

    // ----------------------------------------------------------------------------------------------------------------------------------------- the stage and its result

    private static WorkflowPlan.Stage stage(String capability, String providerKey) {
        return PlanSamples.stage("stage-01", capability, providerKey, List.of(), PlanSamples.openGate(), null);
    }

    /** The first file of an attachment, which is the only file of an attachment that has one. */
    private static AttachmentFileRef file(String attachmentId) {
        return new AttachmentFileRef(attachmentId, 0);
    }

    private static AttachmentFileRef file(String attachmentId, int seq) {
        return new AttachmentFileRef(attachmentId, seq);
    }

    private static ProviderArtifact artifact(String id, String attachmentId, String digest) {
        return new ProviderArtifact(id, "artifact.v1", attachmentId, digest, TARGET, true);
    }

    private static ProviderArtifact artifact(String id, String attachmentId, int seq, String digest) {
        return new ProviderArtifact(id, "artifact.v1", attachmentId, seq, digest, TARGET, true);
    }

    private static Evidence evidence(String artifactId, String attachmentId, int seq) {
        return new Evidence(artifactId, EvidenceKind.FILE, attachmentId, seq, FILE_DIGEST, true, null);
    }

    private static Evidence evidence(String artifactId, String attachmentId) {
        return new Evidence(artifactId, EvidenceKind.FILE, attachmentId, FILE_DIGEST, true, null);
    }

    private static StageResult resultWith(List<Evidence> evidence, ProviderArtifact... artifacts) {
        ObjectNode output = MAPPER.createObjectNode().put("verdict", "pass");
        ProviderResult provider = new ProviderResult("1.0.0", OutputKind.OUTPUT, output, List.of(artifacts), null);
        return new StageResult("1.0.0", "run-1", "stage-01", 0, RunState.PASSED, provider, evidence, List.of(), List.of(), null, null, null, null, null);
    }

    private static StageResult result(ProviderArtifact... artifacts) {
        return resultWith(List.of(), artifacts);
    }

    private static StageResult csResult() {
        return result(artifact("cs-review-bundle", BUNDLE, BUNDLE_DIGEST), artifact("cs-review-report", REPORT, REPORT_DIGEST));
    }

    private static StageResult engineeringResult(String capability) {
        String report = TEST_STAGE.equals(capability) ? "engineering-test-result" : "engineering-review-result";
        return result(artifact("engineering-stage-bundle", BUNDLE, BUNDLE_DIGEST), artifact(report, REPORT, REPORT_DIGEST));
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------ the check's answer

    private static ObjectNode verdict(int exitCode, Consumer<ObjectNode> fill) {
        ObjectNode value = MAPPER.createObjectNode();
        value.set("task", task);
        fill.accept(value);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("exitCode", exitCode);
        body.set("value", value);
        return body;
    }

    private static ObjectNode csValue(ObjectNode value) {
        value.put("kind", "cs-bundle-check");
        value.putObject("review").put("verdict", "pass");
        value.putObject("reviewRef").put("path", "review.json").put("digest", REPORT_DIGEST);
        value.put("candidateDigest", TARGET);
        return value;
    }

    private static SkillChecks.Result csPass() {
        return new SkillChecks.Result(verdict(0, StageBundleChecksTest::csValue), 0);
    }

    private static ObjectNode engineeringValue(ObjectNode value) {
        value.put("kind", "engineering-stage-check");
        value.putObject("result").put("verdict", "pass");
        value.putObject("resultRef").put("path", "result.json").put("digest", REPORT_DIGEST);
        value.put("targetDigest", TARGET);
        return value;
    }

    private static SkillChecks.Result engineeringPass(int exitCode) {
        return new SkillChecks.Result(verdict(exitCode, StageBundleChecksTest::engineeringValue), exitCode);
    }

    private static ReadRequest.Checked csChecked(SkillChecks.Result answer) {
        return new ReadRequest.Checked("cs-engineering", answer, CS_PATHS);
    }

    private static ReadRequest.Checked engineeringChecked(String capability, SkillChecks.Result answer) {
        return new ReadRequest.Checked(TEST_STAGE.equals(capability) ? "test-engineering" : "code-review", answer, ENGINEERING_PATHS);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------ the entry's answer

    /** The reads of a use case that asked for this stage's check and was answered with {@code answer}, as the entry hands them over. */
    private static FileReads reads(WorkflowPlan.Stage stage, StageResult result, String taskDigest, ReadRequest.CheckAnswer answer) {
        ReadRequest request = bundleChecks.request(stage, result, taskDigest).orElseThrow();
        Map<ReadRequest, FileRead<?>> answers = new LinkedHashMap<>();
        answers.put(request, new FileRead<>(answer, null));
        return new FileReads(-1, answers);
    }

    private static WorkflowFailure refused(WorkflowPlan.Stage stage, StageResult result, String taskDigest, ReadRequest.CheckAnswer answer) {
        FileReads reads = reads(stage, result, taskDigest, answer);
        return assertThrows(WorkflowFailure.class, () -> bundleChecks.check(stage, result, taskDigest, reads));
    }

    /** A refusal that comes before the check is asked for: the reads are none, so a check asked for would be the rules' own mistake ({@link IllegalStateException}). */
    private static WorkflowFailure refusedBeforeTheCheck(WorkflowPlan.Stage stage, StageResult result, String taskDigest) {
        return assertThrows(WorkflowFailure.class, () -> bundleChecks.check(stage, result, taskDigest, FileReads.NONE));
    }

    private static void accepted(WorkflowPlan.Stage stage, StageResult result, String taskDigest, ReadRequest.CheckAnswer answer) {
        FileReads reads = reads(stage, result, taskDigest, answer);
        assertDoesNotThrow(() -> bundleChecks.check(stage, result, taskDigest, reads));
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------ the registry and covers

    @Test
    void theRegistryNamesTheCheckByItsSkillNotAFile() {
        List<String> declared = registry.read().stream().filter(provider -> provider.stageValidator() != null).map(SkillProvider::stageValidator).toList();
        assertEquals(List.of("cs-engineering"), declared);
    }

    @Test
    void aStageOutsideTheReviewsIsNotChecked() {
        assertFalse(StageBundleChecks.covers("minimal-implementation"));
        assertDoesNotThrow(() -> bundleChecks.check(stage("minimal-implementation", "any"), csResult(), TASK_DIGEST, FileReads.NONE));
        for (String capability : List.of(CS_STAGE, TEST_STAGE, CODE_STAGE)) {
            assertTrue(StageBundleChecks.covers(capability), capability);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"minimal-implementation", "cs-constraint-derivation", "independent-audit-gate", "code-review", "", "CS-IMPLEMENTATION-REVIEW"})
    void everyOtherCapabilityIsNotCovered(String capability) {
        assertFalse(StageBundleChecks.covers(capability), capability);
    }

    @Test
    void aStageThatProducedNoOutputIsNotChecked() {
        ProviderResult adapterError = new ProviderResult("1.0.0", OutputKind.ADAPTER_ERROR, null, List.of(), new RecordedError(WorkflowCode.INVALID_INPUT, "unavailable", null));
        StageResult unavailable = csResult().withOutput(adapterError);
        assertDoesNotThrow(() -> bundleChecks.check(stage(CS_STAGE, csProviderKey), unavailable, TASK_DIGEST, FileReads.NONE));
    }

    // -------------------------------------------------------------------------------------------------------------------------------------- the request the entry answers

    @Test
    void theRequestOfACsReviewListsTheAttachmentsOfTheResultTheBundleItsDigestAndTheTask() {
        StageResult result = resultWith(List.of(evidence("task", OTHER), evidence("proof", PROOF)), artifact("cs-review-bundle", BUNDLE, BUNDLE_DIGEST),
                artifact("cs-review-report", REPORT, REPORT_DIGEST));
        Optional<ReadRequest.BundleCheck> request = bundleChecks.request(stage(CS_STAGE, csProviderKey), result, TASK_DIGEST);
        assertEquals(Optional.of(new ReadRequest.BundleCheck(csProviderKey, null, "check-stage-bundle", file(BUNDLE), List.of(file(BUNDLE), file(REPORT), file(OTHER), file(PROOF)), BUNDLE_DIGEST, TASK_DIGEST, null)), request);
        assertNull(request.orElseThrow().capability(), "the CS check is not given a capability");
        assertNull(request.orElseThrow().skill(), "the CS review is judged by the stage validator its provider declares, not by a skill the request names");
    }

    @ParameterizedTest
    @ValueSource(strings = {TEST_STAGE, CODE_STAGE})
    void theRequestOfAnEngineeringStageNamesItsSkillAndItsCapability(String capability) {
        StageResult result = resultWith(List.of(evidence("task", OTHER)), artifact("engineering-stage-bundle", BUNDLE, BUNDLE_DIGEST),
                artifact(TEST_STAGE.equals(capability) ? "engineering-test-result" : "engineering-review-result", REPORT, REPORT_DIGEST));
        String skill = TEST_STAGE.equals(capability) ? "test-engineering" : "code-review";
        Optional<ReadRequest.BundleCheck> request = bundleChecks.request(stage(capability, "any"), result, TASK_DIGEST);
        assertEquals(Optional.of(new ReadRequest.BundleCheck(null, skill, "check-stage-bundle", file(BUNDLE), List.of(file(BUNDLE), file(REPORT), file(OTHER)), BUNDLE_DIGEST, TASK_DIGEST, capability)), request);
    }

    @Test
    void theRequestListsTheArtifactsFirstAndTheEvidenceSecondAndEachAttachmentOnce() {
        // The evidence names the bundle again, an attachment twice and one the artifacts do not name: the list is the artifacts' and then the new ones of the evidence, in the order given.
        StageResult result = resultWith(List.of(evidence("task", OTHER), evidence("bundle-again", BUNDLE), evidence("proof", PROOF), evidence("task-again", OTHER)),
                artifact("cs-review-bundle", BUNDLE, BUNDLE_DIGEST), artifact("cs-review-report", REPORT, REPORT_DIGEST));
        ReadRequest.BundleCheck request = bundleChecks.request(stage(CS_STAGE, csProviderKey), result, TASK_DIGEST).orElseThrow();
        assertEquals(List.of(file(BUNDLE), file(REPORT), file(OTHER), file(PROOF)), request.files());
        // Two artifacts that name one attachment are one file too.
        StageResult sameFile = result(artifact("cs-review-bundle", BUNDLE, BUNDLE_DIGEST), artifact("cs-review-report", BUNDLE, REPORT_DIGEST));
        assertEquals(List.of(file(BUNDLE)), bundleChecks.request(stage(CS_STAGE, csProviderKey), sameFile, TASK_DIGEST).orElseThrow().files());
    }

    @Test
    void theRequestNamesTheBundleByTheArtifactNotByItsPlaceInTheList() {
        StageResult result = result(artifact("cs-review-report", REPORT, REPORT_DIGEST), artifact("cs-review-bundle", BUNDLE, BUNDLE_DIGEST));
        ReadRequest.BundleCheck request = bundleChecks.request(stage(CS_STAGE, csProviderKey), result, TASK_DIGEST).orElseThrow();
        assertEquals(file(BUNDLE), request.bundle());
        assertEquals(BUNDLE_DIGEST, request.bundleDigest());
        assertEquals(List.of(file(REPORT), file(BUNDLE)), request.files());
    }

    /** Case b5, the request: the files of one attachment are files of the request, each once, and the bundle is the file its artifact names. */
    @Test
    void twoEvidenceFilesOfOneAttachmentAreTwoFilesOfTheRequestAndTheBundleIsTheFileItsArtifactNames() {
        // One attachment (BUNDLE) holds the bundle at file 1, the report at file 0 and an evidence file at file 2; another holds one more; file 2 is named twice.
        StageResult result = resultWith(List.of(evidence("proof", BUNDLE, 2), evidence("task", OTHER, 0), evidence("proof-again", BUNDLE, 2), evidence("task-too", BUNDLE, 3)),
                artifact("cs-review-bundle", BUNDLE, 1, BUNDLE_DIGEST), artifact("cs-review-report", BUNDLE, 0, REPORT_DIGEST));
        ReadRequest.BundleCheck request = bundleChecks.request(stage(CS_STAGE, csProviderKey), result, TASK_DIGEST).orElseThrow();
        assertEquals(file(BUNDLE, 1), request.bundle(), "the bundle is file 1 of its attachment, not the attachment");
        assertEquals(List.of(file(BUNDLE, 1), file(BUNDLE, 0), file(BUNDLE, 2), file(OTHER, 0), file(BUNDLE, 3)), request.files(), "each file once, the artifacts' first");
        assertEquals(4, request.files().stream().filter(entry -> entry.attachmentId().equals(BUNDLE)).count(), "four files of one attachment are four files of the request");
        // The same attachment at the same file number is one file: file 0 and file 1 are not.
        StageResult sameFile = result(artifact("cs-review-bundle", BUNDLE, 0, BUNDLE_DIGEST), artifact("cs-review-report", BUNDLE, 0, REPORT_DIGEST));
        assertEquals(List.of(file(BUNDLE, 0)), bundleChecks.request(stage(CS_STAGE, csProviderKey), sameFile, TASK_DIGEST).orElseThrow().files());
    }

    /** Case b5, the binding: the report is the file (attachment id and number) at the path the check read. */
    @Test
    void theReportOfABundleOfFilesIsBoundByTheAttachmentIdAndTheFileNumberOfItsPath() {
        WorkflowPlan.Stage stage = stage(CS_STAGE, csProviderKey);
        StageResult result = result(artifact("cs-review-bundle", BUNDLE, 0, BUNDLE_DIGEST), artifact("cs-review-report", BUNDLE, 1, REPORT_DIGEST));
        accepted(stage, result, TASK_DIGEST, new ReadRequest.Checked("cs-engineering", csPass(), Map.of(file(BUNDLE, 0), "bundle.json", file(BUNDLE, 1), "review.json")));
        // File 1 is at another path than the one the check read.
        WorkflowFailure elsewhere = refused(stage, result, TASK_DIGEST,
                new ReadRequest.Checked("cs-engineering", csPass(), Map.of(file(BUNDLE, 0), "bundle.json", file(BUNDLE, 1), "elsewhere.json")));
        assertEquals(WorkflowCode.INTEGRITY_FAILED, elsewhere.code());
        assertEquals("workflow.cs.csReportArtifactBundleName", elsewhere.ref().key());
        // The path the check read belongs to file 0 of the attachment, which is not the file the report names (file 1): the attachment's id alone does not bind it.
        WorkflowFailure otherFile = refused(stage, result, TASK_DIGEST,
                new ReadRequest.Checked("cs-engineering", csPass(), Map.of(file(BUNDLE, 0), "review.json", file(BUNDLE, 2), "bundle.json")));
        assertEquals(WorkflowCode.INTEGRITY_FAILED, otherFile.code());
        assertEquals("workflow.cs.csReportArtifactBundleName", otherFile.ref().key());
    }

    @ParameterizedTest
    @ValueSource(strings = {TEST_STAGE, CODE_STAGE})
    void theReportOfAnEngineeringBundleOfFilesIsBoundByTheAttachmentIdAndTheFileNumberOfItsPath(String capability) {
        String report = TEST_STAGE.equals(capability) ? "engineering-test-result" : "engineering-review-result";
        StageResult result = result(artifact("engineering-stage-bundle", BUNDLE, 0, BUNDLE_DIGEST), artifact(report, BUNDLE, 1, REPORT_DIGEST));
        String skill = TEST_STAGE.equals(capability) ? "test-engineering" : "code-review";
        accepted(stage(capability, "any"), result, TASK_DIGEST, new ReadRequest.Checked(skill, engineeringPass(0), Map.of(file(BUNDLE, 0), "stage.json", file(BUNDLE, 1), "result.json")));
        WorkflowFailure failure = refused(stage(capability, "any"), result, TASK_DIGEST,
                new ReadRequest.Checked(skill, engineeringPass(0), Map.of(file(BUNDLE, 0), "stage.json", file(BUNDLE, 2), "result.json")));
        assertEquals(WorkflowCode.INTEGRITY_FAILED, failure.code());
        assertEquals("workflow.engineering.engineeringArtifactsBindValidatedResult", failure.ref().key());
    }

    @Test
    void nothingIsAskedOfAStageThatIsNotJudgedHere() {
        assertTrue(bundleChecks.request(stage("minimal-implementation", "any"), csResult(), TASK_DIGEST).isEmpty());
        assertTrue(bundleChecks.request(stage("independent-audit-gate", "any"), csResult(), TASK_DIGEST).isEmpty());
        // A capability that only looks like a review's.
        assertTrue(bundleChecks.request(stage("code-review", "any"), engineeringResult("code-review"), TASK_DIGEST).isEmpty());
    }

    @Test
    void nothingIsAskedOfAnUnverifiedMissingOrRepeatedBundle() {
        WorkflowPlan.Stage stage = stage(CS_STAGE, csProviderKey);
        ProviderArtifact unverified = new ProviderArtifact("cs-review-bundle", "artifact.v1", BUNDLE, BUNDLE_DIGEST, TARGET, false);
        assertTrue(bundleChecks.request(stage, result(unverified), TASK_DIGEST).isEmpty(), "unverified");
        ProviderArtifact one = artifact("cs-review-bundle", BUNDLE, BUNDLE_DIGEST);
        assertTrue(bundleChecks.request(stage, result(one, one), TASK_DIGEST).isEmpty(), "named twice");
        assertTrue(bundleChecks.request(stage, result(), TASK_DIGEST).isEmpty(), "no bundle at all");
        assertTrue(bundleChecks.request(stage, result(artifact("cs-review-report", REPORT, REPORT_DIGEST)), TASK_DIGEST).isEmpty(), "a report without its bundle");
        assertTrue(bundleChecks.request(stage(CODE_STAGE, "any"), result(artifact("cs-review-bundle", BUNDLE, BUNDLE_DIGEST)), TASK_DIGEST).isEmpty(), "the other skill's bundle is not this stage's");
    }

    @Test
    void nothingIsAskedOfAnAdapterErrorOrWithoutASignedTask() {
        ProviderResult adapterError = new ProviderResult("1.0.0", OutputKind.ADAPTER_ERROR, null, List.of(), new RecordedError(WorkflowCode.INVALID_INPUT, "unavailable", null));
        assertTrue(bundleChecks.request(stage(CS_STAGE, csProviderKey), csResult().withOutput(adapterError), TASK_DIGEST).isEmpty());
        assertTrue(bundleChecks.request(stage(CS_STAGE, csProviderKey), csResult(), null).isEmpty());
        assertTrue(bundleChecks.request(stage(CODE_STAGE, "any"), engineeringResult(CODE_STAGE), null).isEmpty());
    }

    // --------------------------------------------------------------------------------------------------------------------------------------------------- the CS review

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void aCsBundleTheCheckReadIsAcceptedWhateverItsVerdictHoldsOrNot(int exitCode) {
        accepted(stage(CS_STAGE, csProviderKey), csResult(), TASK_DIGEST, csChecked(new SkillChecks.Result(verdict(exitCode, StageBundleChecksTest::csValue), exitCode)));
    }

    @Test
    void aReviewThatDiffersFromWhatTheCheckReadIsRefused() {
        StageResult changed = csResult();
        ((ObjectNode) changed.output().output()).put("verdict", "fail");
        WorkflowFailure failure = refused(stage(CS_STAGE, csProviderKey), changed, TASK_DIGEST, csChecked(csPass()));
        assertEquals(WorkflowCode.INTEGRITY_FAILED, failure.code());
        assertEquals("workflow.cs.csStageOutputDiffersValidated", failure.ref().key());
    }

    @Test
    void aReportNotTheFileTheCheckReadIsRefused() {
        // The report attachment is at another path under the bundle's folder than the one the check read (Ref.path is review.json).
        ReadRequest.Checked elsewhere = new ReadRequest.Checked("cs-engineering", csPass(), Map.of(file(BUNDLE), "bundle.json", file(REPORT), "elsewhere.json"));
        WorkflowFailure failure = refused(stage(CS_STAGE, csProviderKey), csResult(), TASK_DIGEST, elsewhere);
        assertEquals(WorkflowCode.INTEGRITY_FAILED, failure.code());
        assertEquals("workflow.cs.csReportArtifactBundleName", failure.ref().key());
    }

    @Test
    void aReportAttachmentTheCheckNeverRanOverIsNotTheFileItRead() {
        // The check read review.json at the path the attachment REPORT has; the result names another attachment (OTHER) as its report, which the check was not run over.
        StageResult other = result(artifact("cs-review-bundle", BUNDLE, BUNDLE_DIGEST), artifact("cs-review-report", OTHER, REPORT_DIGEST));
        WorkflowFailure notRunOver = refused(stage(CS_STAGE, csProviderKey), other, TASK_DIGEST, csChecked(csPass()));
        assertEquals(WorkflowCode.INTEGRITY_FAILED, notRunOver.code());
        assertEquals("workflow.cs.csReportArtifactBundleName", notRunOver.ref().key());
        // The same attachment at the path the check read is the file it read, whatever its id says.
        ReadRequest.Checked named = new ReadRequest.Checked("cs-engineering", csPass(), Map.of(file(BUNDLE), "bundle.json", file(OTHER), "review.json"));
        accepted(stage(CS_STAGE, csProviderKey), other, TASK_DIGEST, named);
    }

    @Test
    void anAnswerThatNamesNoPathsBindsNoReport() {
        WorkflowFailure failure = refused(stage(CS_STAGE, csProviderKey), csResult(), TASK_DIGEST, new ReadRequest.Checked("cs-engineering", csPass(), Map.of()));
        assertEquals(WorkflowCode.INTEGRITY_FAILED, failure.code());
        assertEquals("workflow.cs.csReportArtifactBundleName", failure.ref().key());
    }

    @Test
    void aReportBoundToAnotherCandidateOrWithAnotherDigestIsRefused() {
        WorkflowPlan.Stage stage = stage(CS_STAGE, csProviderKey);
        ProviderArtifact bundle = artifact("cs-review-bundle", BUNDLE, BUNDLE_DIGEST);
        ProviderArtifact moved = new ProviderArtifact("cs-review-report", "artifact.v1", REPORT, REPORT_DIGEST, "sha256:" + "6".repeat(64), true);
        assertEquals(WorkflowCode.INTEGRITY_FAILED, refused(stage, result(bundle, moved), TASK_DIGEST, csChecked(csPass())).code());
        ProviderArtifact changed = artifact("cs-review-report", REPORT, "sha256:" + "7".repeat(64));
        assertEquals(WorkflowCode.INTEGRITY_FAILED, refused(stage, result(bundle, changed), TASK_DIGEST, csChecked(csPass())).code());
        ProviderArtifact unverified = new ProviderArtifact("cs-review-report", "artifact.v1", REPORT, REPORT_DIGEST, TARGET, false);
        assertEquals(WorkflowCode.INTEGRITY_FAILED, refused(stage, result(bundle, unverified), TASK_DIGEST, csChecked(csPass())).code());
        assertEquals(WorkflowCode.INTEGRITY_FAILED, refused(stage, result(bundle), TASK_DIGEST, csChecked(csPass())).code(), "no report at all");
    }

    @Test
    void aBundleBoundToAnotherCandidateThanTheCheckBoundIsRefused() {
        ProviderArtifact moved = new ProviderArtifact("cs-review-bundle", "artifact.v1", BUNDLE, BUNDLE_DIGEST, "sha256:" + "6".repeat(64), true);
        StageResult result = result(moved, artifact("cs-review-report", REPORT, REPORT_DIGEST));
        WorkflowFailure failure = refused(stage(CS_STAGE, csProviderKey), result, TASK_DIGEST, csChecked(csPass()));
        assertEquals(WorkflowCode.INTEGRITY_FAILED, failure.code());
        assertEquals("workflow.cs.csReportArtifactBundleName", failure.ref().key());
    }

    @Test
    void aRefusedInputKeepsTheCheckCodeAndMessage() {
        ObjectNode error = MAPPER.createObjectNode().put("status", "ERROR").put("code", "INTEGRITY_FAILED").put("message", "the bundle changed");
        WorkflowFailure failure = refused(stage(CS_STAGE, csProviderKey), csResult(), TASK_DIGEST, csChecked(new SkillChecks.Result(error, 2)));
        assertEquals(WorkflowCode.INTEGRITY_FAILED, failure.code());
        assertEquals("workflow.validatorProcess.childFailure", failure.ref().key());
        assertEquals("the bundle changed", failure.ref().params().get("message"));
        assertEquals("INTEGRITY_FAILED", failure.details().get("childCode"));
    }

    @Test
    void aCheckCodeOutsideTheContractBecomesAGateFailure() {
        ObjectNode error = MAPPER.createObjectNode().put("status", "ERROR").put("code", "SOMETHING_ELSE").put("message", "no");
        WorkflowFailure failure = refused(stage(CS_STAGE, csProviderKey), csResult(), TASK_DIGEST, csChecked(new SkillChecks.Result(error, 2)));
        assertEquals(WorkflowCode.GATE_FAILED, failure.code());
        assertEquals("SOMETHING_ELSE", failure.details().get("childCode"), "what the check said is kept for the reader");
    }

    @Test
    void aRefusalWithoutASentenceNamesTheExitStatus() {
        ObjectNode error = MAPPER.createObjectNode().put("status", "ERROR").put("code", "INVALID_INPUT");
        WorkflowFailure failure = refused(stage(CS_STAGE, csProviderKey), csResult(), TASK_DIGEST, csChecked(new SkillChecks.Result(error, 2)));
        assertEquals(WorkflowCode.INVALID_INPUT, failure.code());
        assertEquals("workflow.validatorProcess.exitStatus", failure.ref().key());
        assertEquals("CS stage bundle", failure.ref().params().get("label"));
        assertEquals("2", failure.ref().params().get("status"));
    }

    @Test
    void withoutTheSkillsModuleTheStageIsNeverAccepted() {
        WorkflowFailure failure = refused(stage(CS_STAGE, csProviderKey), csResult(), TASK_DIGEST, new ReadRequest.SkillsModuleAbsent("cs-engineering"));
        assertEquals(WorkflowCode.MCP_UNAVAILABLE, failure.code());
        assertEquals("VALIDATOR_NOT_PREPARED", failure.details().get("reason"));
        assertEquals("workflow.validatorProcess.couldNotRun", failure.ref().key());
    }

    @Test
    void aProviderDeclaringNoCheckIsNeverAccepted() {
        WorkflowFailure failure = refused(stage(CS_STAGE, "no-such-provider"), csResult(), TASK_DIGEST, new ReadRequest.NoValidator());
        assertEquals(WorkflowCode.MCP_UNAVAILABLE, failure.code());
        assertEquals("STAGE_VALIDATOR_UNDECLARED", failure.details().get("reason"));
        assertEquals("no-such-provider", failure.details().get("providerKey"));
    }

    @Test
    void anAnswerOfAnotherKindIsAGateFailure() {
        SkillChecks.Result other = new SkillChecks.Result(verdict(0, value -> value.put("kind", "something-else")), 0);
        WorkflowFailure failure = refused(stage(CS_STAGE, csProviderKey), csResult(), TASK_DIGEST, csChecked(other));
        assertEquals(WorkflowCode.GATE_FAILED, failure.code());
        assertEquals("workflow.cs.csStageBundleCouldValidated", failure.ref().key());
    }

    @Test
    void anAnswerOfTheEngineeringKindToTheCsStageIsAGateFailure() {
        SkillChecks.Result engineering = new SkillChecks.Result(verdict(0, StageBundleChecksTest::engineeringValue), 0);
        assertEquals("workflow.cs.csStageBundleCouldValidated", refused(stage(CS_STAGE, csProviderKey), csResult(), TASK_DIGEST, csChecked(engineering)).ref().key());
    }

    @Test
    void anAnswerWithoutATaskFailsTheTaskContract() {
        SkillChecks.Result noTask = new SkillChecks.Result(verdict(0, value -> {
            csValue(value);
            value.remove("task");
        }), 0);
        assertEquals(WorkflowCode.INVALID_INPUT, refused(stage(CS_STAGE, csProviderKey), csResult(), TASK_DIGEST, csChecked(noTask)).code());
    }

    @Test
    void anAnswerWithoutAValueIsNotAVerdict() {
        WorkflowFailure failure = refused(stage(CS_STAGE, csProviderKey), csResult(), TASK_DIGEST, csChecked(new SkillChecks.Result(MAPPER.createObjectNode().put("exitCode", 0), 0)));
        assertEquals(WorkflowCode.MCP_UNAVAILABLE, failure.code());
        assertEquals("VALIDATOR_OUTPUT_INVALID", failure.details().get("reason"));
    }

    @Test
    void anAnswerOfTheWrongShapeIsNotAVerdictAndKeepsItsCause() {
        SkillChecks.Result wrong = new SkillChecks.Result(verdict(0, value -> value.put("reviewRef", "review.json").put("kind", "cs-bundle-check")), 0);
        WorkflowFailure failure = refused(stage(CS_STAGE, csProviderKey), csResult(), TASK_DIGEST, csChecked(wrong));
        assertEquals(WorkflowCode.MCP_UNAVAILABLE, failure.code());
        assertEquals("workflow.validatorProcess.unboundedOutput", failure.ref().key());
        assertEquals("VALIDATOR_OUTPUT_INVALID", failure.details().get("reason"));
        assertNotNull(failure.getCause());
    }

    @Test
    void aReviewNeedsASignedTask() {
        WorkflowFailure failure = refusedBeforeTheCheck(stage(CS_STAGE, csProviderKey), csResult(), null);
        assertEquals(WorkflowCode.BINDING_REQUIRED, failure.code());
        assertEquals("workflow.cs.csReviewRequiresSignedTask", failure.ref().key());
    }

    @Test
    void anUnverifiedOrRepeatedBundleIsNotEvidence() {
        WorkflowPlan.Stage stage = stage(CS_STAGE, csProviderKey);
        ProviderArtifact unverified = new ProviderArtifact("cs-review-bundle", "artifact.v1", BUNDLE, BUNDLE_DIGEST, TARGET, false);
        WorkflowFailure failure = refusedBeforeTheCheck(stage, result(unverified), TASK_DIGEST);
        assertEquals(WorkflowCode.MISSING_EVIDENCE, failure.code());
        assertEquals("workflow.cs.csReviewRequiresVerifiedLocal", failure.ref().key());
        ProviderArtifact one = artifact("cs-review-bundle", BUNDLE, BUNDLE_DIGEST);
        assertEquals(WorkflowCode.MISSING_EVIDENCE, refusedBeforeTheCheck(stage, result(one, one), TASK_DIGEST).code());
        assertEquals(WorkflowCode.MISSING_EVIDENCE, refusedBeforeTheCheck(stage, result(), TASK_DIGEST).code(), "no bundle at all");
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------ the engineering stages

    @ParameterizedTest
    @ValueSource(strings = {TEST_STAGE, CODE_STAGE})
    void anEngineeringStageIsJudgedByTheAnswerOfItsOwnSkill(String capability) {
        accepted(stage(capability, "any"), engineeringResult(capability), TASK_DIGEST, engineeringChecked(capability, engineeringPass(1)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void anEngineeringVerdictThatHoldsOrNotIsAccepted(int exitCode) {
        accepted(stage(CODE_STAGE, "any"), engineeringResult(CODE_STAGE), TASK_DIGEST, engineeringChecked(CODE_STAGE, engineeringPass(exitCode)));
    }

    @Test
    void anEngineeringExitOutsideItsVerdictsIsRefused() {
        WorkflowFailure failure = refused(stage(CODE_STAGE, "any"), engineeringResult(CODE_STAGE), TASK_DIGEST, engineeringChecked(CODE_STAGE, engineeringPass(3)));
        assertEquals(WorkflowCode.GATE_FAILED, failure.code());
        assertEquals("workflow.validatorProcess.exitStatus", failure.ref().key());
        assertEquals("Engineering stage", failure.ref().params().get("label"));
    }

    @Test
    void anEngineeringResultThatDiffersIsRefused() {
        StageResult changed = engineeringResult(CODE_STAGE);
        ((ObjectNode) changed.output().output()).put("verdict", "fail");
        WorkflowFailure failure = refused(stage(CODE_STAGE, "any"), changed, TASK_DIGEST, engineeringChecked(CODE_STAGE, engineeringPass(0)));
        assertEquals(WorkflowCode.INTEGRITY_FAILED, failure.code());
        assertEquals("workflow.engineering.providerOutputDiffersValidatedRaw", failure.ref().key());
    }

    @Test
    void anEngineeringReportBoundToAnotherTargetIsRefused() {
        ProviderArtifact bundle = artifact("engineering-stage-bundle", BUNDLE, BUNDLE_DIGEST);
        ProviderArtifact moved = new ProviderArtifact("engineering-test-result", "artifact.v1", REPORT, REPORT_DIGEST, "sha256:" + "6".repeat(64), true);
        WorkflowFailure failure = refused(stage(TEST_STAGE, "any"), result(bundle, moved), TASK_DIGEST, engineeringChecked(TEST_STAGE, engineeringPass(0)));
        assertEquals(WorkflowCode.INTEGRITY_FAILED, failure.code());
        assertEquals("workflow.engineering.engineeringArtifactsBindValidatedResult", failure.ref().key());
    }

    @Test
    void anEngineeringReportNotTheFileTheCheckReadIsRefused() {
        ReadRequest.Checked elsewhere = new ReadRequest.Checked("code-review", engineeringPass(0), Map.of(file(BUNDLE), "stage.json", file(REPORT), "elsewhere.json"));
        WorkflowFailure failure = refused(stage(CODE_STAGE, "any"), engineeringResult(CODE_STAGE), TASK_DIGEST, elsewhere);
        assertEquals(WorkflowCode.INTEGRITY_FAILED, failure.code());
        assertEquals("workflow.engineering.engineeringArtifactsBindValidatedResult", failure.ref().key());
    }

    @Test
    void anEngineeringReportOfTheOtherStagesKindIsNotTheReportOfThisOne() {
        StageResult review = engineeringResult(CODE_STAGE);
        WorkflowFailure failure = refused(stage(TEST_STAGE, "any"), review, TASK_DIGEST, engineeringChecked(TEST_STAGE, engineeringPass(0)));
        assertEquals(WorkflowCode.INTEGRITY_FAILED, failure.code());
        assertEquals("workflow.engineering.engineeringArtifactsBindValidatedResult", failure.ref().key());
    }

    @Test
    void anEngineeringReviewNeedsASignedTaskAndAVerifiedBundle() {
        assertEquals(WorkflowCode.BINDING_REQUIRED, refusedBeforeTheCheck(stage(CODE_STAGE, "any"), engineeringResult(CODE_STAGE), null).code());
        ProviderArtifact unverified = new ProviderArtifact("engineering-stage-bundle", "artifact.v1", BUNDLE, BUNDLE_DIGEST, TARGET, false);
        WorkflowFailure failure = refusedBeforeTheCheck(stage(CODE_STAGE, "any"), result(unverified), TASK_DIGEST);
        assertEquals(WorkflowCode.MISSING_EVIDENCE, failure.code());
        assertEquals("workflow.engineering.engineeringReviewRequiresVerifiedLocal", failure.ref().key());
        assertEquals(WorkflowCode.MISSING_EVIDENCE, refusedBeforeTheCheck(stage(CODE_STAGE, "any"), result(), TASK_DIGEST).code(), "no bundle at all");
    }

    @Test
    void anEngineeringAnswerOfAnotherKindOrWithoutATaskIsRefused() {
        SkillChecks.Result other = new SkillChecks.Result(verdict(0, StageBundleChecksTest::csValue), 0);
        WorkflowFailure kind = refused(stage(CODE_STAGE, "any"), engineeringResult(CODE_STAGE), TASK_DIGEST, engineeringChecked(CODE_STAGE, other));
        assertEquals(WorkflowCode.GATE_FAILED, kind.code());
        assertEquals("workflow.engineering.engineeringStageFilesCouldValidated", kind.ref().key());
        SkillChecks.Result noTask = new SkillChecks.Result(verdict(0, value -> {
            engineeringValue(value);
            value.remove("task");
        }), 0);
        assertEquals(WorkflowCode.INVALID_INPUT, refused(stage(CODE_STAGE, "any"), engineeringResult(CODE_STAGE), TASK_DIGEST, engineeringChecked(CODE_STAGE, noTask)).code());
    }

    @ParameterizedTest
    @ValueSource(strings = {TEST_STAGE, CODE_STAGE})
    void anEngineeringStageWithoutTheSkillsModuleOrAValidatorIsNeverAccepted(String capability) {
        for (ReadRequest.CheckAnswer unavailable : List.of(new ReadRequest.SkillsModuleAbsent("code-review"), new ReadRequest.NoValidator())) {
            WorkflowFailure failure = refused(stage(capability, "any"), engineeringResult(capability), TASK_DIGEST, unavailable);
            assertEquals(WorkflowCode.MCP_UNAVAILABLE, failure.code(), unavailable.toString());
            assertEquals("VALIDATOR_NOT_PREPARED", failure.details().get("reason"), unavailable.toString());
            assertEquals("workflow.validatorProcess.couldNotRun", failure.ref().key(), unavailable.toString());
        }
    }
}
