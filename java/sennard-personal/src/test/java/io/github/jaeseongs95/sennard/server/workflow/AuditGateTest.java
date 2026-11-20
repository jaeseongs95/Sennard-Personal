// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentContents;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileRef;
import io.github.jaeseongs95.sennard.service.store.RunState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mandatory audit gate reads what an audit says of itself from the provider's output, which is the skill's own document and which no schema of the server
 * types. The cases, each through {@link StageGates#assertMandatoryAuditGate}:
 * <ol>
 * <li>an audit that passes, fresh, undelegated, current, of the planned target and of the frozen phase, passes the gate;</li>
 * <li>each fact that is not what the gate needs fails it with {@code GATE_FAILED}: another verdict, a flag that is false or is not a flag (the text
 * {@code "true"} included, which is not converted), a finding that blocks, a phase that is not the frozen one;</li>
 * <li>an output that is not of the shape (a text for the list of implementers, an object for the findings, a number for the auditor, no output) fails it the same way,
 * with the binding failure as the cause;</li>
 * <li>an auditor that is also an implementer, the audit of another target than the one the verdict artifact names, and an auditor the server observed as the root fail it;</li>
 * <li>an audit after the work that did not verify the state after it fails it, and one before the work does not need to;</li>
 * <li>the attachments the artifacts and the evidence name are compared with the digests declared for them ({@link StageGates#checkArtifacts}), and the finish judges again the attachments an
 * audit was recorded on ({@link StageGates#assertAuditedAttachmentsIntact}): the stage-result attachment contract (AGS3-D-0264), where no file of the caller's computer is read;</li>
 * <li>an attachment is a bundle of files, so every one of those comparisons is of one file of an attachment ({@link AttachmentFileRef}: attachment id and file number):
 * <ol type="a">
 * <li>an artifact at file 0 and an evidence item at file 1 of one attachment are each checked against their own file's sha256 and the entries carry {@code seq} 0 and 1;</li>
 * <li>the sha256 of file 1 declared for file 0 is {@code ARTIFACT_DIGEST_MISMATCH} on a passing stage (with {@code details.seq}) and {@code NOT_CHECKED} on any other;</li>
 * <li>a file the recording did not resolve (file 1 when only file 0 was) is the use case's mistake, not an answer;</li>
 * <li>the finish asks for the standing of the file (attachment and number) an audit was recorded on, and a file that is not intact fails it with {@code details.seq}.</li>
 * </ol></li>
 * </ol>
 */
class AuditGateTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String TARGET = "sha256:" + "a".repeat(64);
    /** The attachments the results below name: the gates are fed the digests and the answers of the attachments directly, so the ids only have to be the UUIDs the contract says. */
    private static final String VERDICT_ATTACHMENT = "11111111-1111-4111-8111-111111111111";
    private static final String EVIDENCE_ATTACHMENT = "22222222-2222-4222-8222-222222222222";
    private static final String GOOD = """
            {"gateVerdict":"PASS","auditorId":"auditor-1","implementationActorIds":["worker-1","worker-2"],"auditTarget":"%s","blockingFindings":[],"freshContext":true,"delegationAllowed":false,
             "stale":false,"phase":"post-execution","postExecutionVerified":true}""".formatted(TARGET);

    private static StageGates gates;
    private static WorkflowPlan.Stage postExecution;

    @BeforeAll
    static void wire() throws IOException {
        ContentRoot content = new ContentRoot(PlanSamples.REPO);
        ContractValidator validator = new ContractValidator(MAPPER, content);
        ObjectProvider<SkillChecks> noChecks = new StaticListableBeanFactory().getBeanProvider(SkillChecks.class);
        gates = new StageGates(validator, MAPPER, new StageBundleChecks(validator, MAPPER), new ProviderSemantics(noChecks, MAPPER));
        postExecution = PlanSamples.audit("stage-01", AuditPhase.POST_EXECUTION);
    }

    private static StageResult result(String output) {
        return resultFor(output == null ? MAPPER.nullNode() : MAPPER.readTree(output), TARGET);
    }

    private static StageResult resultFor(JsonNode output, String verdictTarget) {
        ProviderResult provider = new ProviderResult("1.0.0", OutputKind.OUTPUT, output, List.of(new ProviderArtifact("gate-verdict", "gate-verdict.v1", VERDICT_ATTACHMENT, TARGET, verdictTarget, true)), null);
        return new StageResult("1.0.0", "run-1", "stage-01", 0, RunState.PASSED, provider, List.of(), List.of(), List.of(), null, null, null, null, null);
    }

    private static WorkflowFailure refused(WorkflowPlan.Stage stage, StageResult result, List<String> observed) {
        return assertThrows(WorkflowFailure.class, () -> gates.assertMandatoryAuditGate(stage, result, observed));
    }

    @Test
    void anAuditThatPassesIsFreshUndelegatedCurrentOfThePlannedTargetAndOfTheFrozenPhasePassesTheGate() {
        assertDoesNotThrow(() -> gates.assertMandatoryAuditGate(postExecution, result(GOOD), List.of()));
        assertDoesNotThrow(() -> gates.assertMandatoryAuditGate(postExecution, result(GOOD.replace("\"auditTarget\":\"" + TARGET + "\"", "\"auditTarget\":\"" + TARGET.substring(7) + "\"")), List.of()),
                "the target may be written bare or prefixed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"gateVerdict\":\"PASS\"|\"gateVerdict\":\"FAIL\"", "\"gateVerdict\":\"PASS\"|\"gateVerdict\":\"BLOCKED\"", "\"gateVerdict\":\"PASS\"|\"gateVerdict\":\"MAYBE\"",
            "\"freshContext\":true|\"freshContext\":false", "\"freshContext\":true|\"freshContext\":\"true\"", "\"freshContext\":true|\"freshContext\":1", "\"delegationAllowed\":false|\"delegationAllowed\":true",
            "\"delegationAllowed\":false|\"delegationAllowed\":\"false\"", "\"stale\":false|\"stale\":true", "\"stale\":false|\"stale\":0", "\"blockingFindings\":[]|\"blockingFindings\":[\"a defect\"]",
            "\"blockingFindings\":[]|\"blockingFindings\":{}", "\"blockingFindings\":[]|\"blockingFindings\":null", "\"phase\":\"post-execution\"|\"phase\":\"pre-deploy\"",
            "\"phase\":\"post-execution\"|\"phase\":\"whenever\"", "\"postExecutionVerified\":true|\"postExecutionVerified\":\"yes\"", "\"auditorId\":\"auditor-1\"|\"auditorId\":\"\"",
            "\"auditorId\":\"auditor-1\"|\"auditorId\":7", "\"implementationActorIds\":[\"worker-1\",\"worker-2\"]|\"implementationActorIds\":[]", "\"implementationActorIds\":[\"worker-1\",\"worker-2\"]|\"implementationActorIds\":\"worker-1\"",
            "\"implementationActorIds\":[\"worker-1\",\"worker-2\"]|\"implementationActorIds\":[\"worker-1\",3]", "\"implementationActorIds\":[\"worker-1\",\"worker-2\"]|\"implementationActorIds\":[\"\"]"})
    void eachFactThatIsNotWhatTheGateNeedsFailsIt(String change) {
        String[] parts = change.split("\\|");
        assertTrue(GOOD.contains(parts[0]), "the good audit has " + parts[0]);
        WorkflowFailure failure = refused(postExecution, result(GOOD.replace(parts[0], parts[1])), List.of());
        assertEquals(WorkflowCode.GATE_FAILED, failure.code(), change);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"implementationActorIds\":[\"worker-1\",\"worker-2\"]|\"implementationActorIds\":\"worker-1\"", "\"blockingFindings\":[]|\"blockingFindings\":{}", "\"auditorId\":\"auditor-1\"|\"auditorId\":7",
            "\"freshContext\":true|\"freshContext\":\"true\""})
    void anOutputThatIsNotOfTheShapeFailsTheGateWithTheBindingFailureAsTheCause(String change) {
        String[] parts = change.split("\\|");
        WorkflowFailure failure = refused(postExecution, result(GOOD.replace(parts[0], parts[1])), List.of());
        assertEquals(WorkflowCode.GATE_FAILED, failure.code(), change);
        assertNotNull(failure.getCause(), "the output could not be read as the audit's facts: " + change);
    }

    @Test
    void noOutputAtAllFailsTheGate() {
        assertEquals(WorkflowCode.GATE_FAILED, refused(postExecution, result(null), List.of()).code());
        assertEquals(WorkflowCode.GATE_FAILED, refused(postExecution, result("{}"), List.of()).code());
    }

    @Test
    void anAuditorThatIsAlsoAnImplementerFailsTheGate() {
        WorkflowFailure failure = refused(postExecution, result(GOOD.replace("\"worker-2\"", "\"auditor-1\"")), List.of());
        assertEquals(WorkflowCode.GATE_FAILED, failure.code());
        assertEquals("workflow.service.declaredAuditorAlsoDeclaredImplementation", failure.ref().key());
    }

    @Test
    void theAuditOfAnotherTargetThanTheOneTheVerdictArtifactNamesFailsTheGate() {
        WorkflowFailure failure = refused(postExecution, resultFor(MAPPER.readTree(GOOD), "sha256:" + "b".repeat(64)), List.of());
        assertEquals(WorkflowCode.GATE_FAILED, failure.code());
        assertEquals("AUDIT_TARGET_NOT_PLANNED", failure.details().get("reason"));
    }

    @Test
    void anAuditorTheServerObservedAsTheRootFailsTheGate() {
        WorkflowFailure failure = refused(postExecution, result(GOOD), List.of("auditor-1"));
        assertEquals(WorkflowCode.GATE_FAILED, failure.code());
        assertEquals("AUDITOR_IS_OBSERVED_ROOT", failure.details().get("reason"));
    }

    @Test
    void anAuditAfterTheWorkMustHaveVerifiedTheStateAfterItAndOneBeforeTheWorkNeedsNotToHaveDoneSo() {
        String unverified = GOOD.replace("\"postExecutionVerified\":true", "\"postExecutionVerified\":false");
        WorkflowFailure failure = refused(postExecution, result(unverified), List.of());
        assertEquals(WorkflowCode.GATE_FAILED, failure.code());
        assertEquals("workflow.service.postExecutionPostDeployAudits", failure.ref().key());
        WorkflowPlan.Stage before = PlanSamples.audit("stage-01", AuditPhase.PRE_EXECUTION);
        assertDoesNotThrow(() -> gates.assertMandatoryAuditGate(before, result(unverified.replace("post-execution", "pre-execution")), List.of()));
    }

    @Test
    void aStageThatIsNotAMandatoryAuditIsNotJudgedByTheGate() {
        WorkflowPlan.Stage plain = PlanSamples.stage("stage-01", "minimal-implementation", List.of(), PlanSamples.openGate(), null);
        assertDoesNotThrow(() -> gates.assertMandatoryAuditGate(plain, result("{}"), List.of()));
    }

    // ------------------------------------------------------------------------------- the attachments the artifacts and the evidence name

    private static final String DIGEST_B = "b".repeat(64);
    private static final String DIGEST_C = "c".repeat(64);

    private static StageResult resultOf(RunState state, List<ProviderArtifact> artifacts, List<Evidence> evidence) {
        ProviderResult provider = new ProviderResult("1.0.0", OutputKind.OUTPUT, MAPPER.createObjectNode(), artifacts, null);
        return new StageResult("1.0.0", "run-1", "stage-01", 0, state, provider, evidence, List.of(), List.of(), null, null, null, null, null);
    }

    private static AttachmentFileRef file(String attachmentId, int seq) {
        return new AttachmentFileRef(attachmentId, seq);
    }

    private static WorkflowPlan.Stage plainStage() {
        return PlanSamples.stage("stage-01", "minimal-implementation", List.of(), PlanSamples.openGate(), null);
    }

    @Test
    void anArtifactAndAnEvidenceItemWhoseAttachmentHasTheDeclaredSha256AreRecordedAsMatched() {
        // The attachment's own sha256 is the prefixed form; the caller may declare it bare or prefixed.
        StageResult result = resultOf(RunState.PASSED, List.of(new ProviderArtifact("artifact-1", "artifact.v1", VERDICT_ATTACHMENT, DIGEST_B, TARGET, true)),
                List.of(new Evidence("evidence-1", EvidenceKind.TEST, EVIDENCE_ATTACHMENT, "sha256:" + DIGEST_C, true, null)));
        List<ArtifactCheckEntry> checks = gates.checkArtifacts(plainStage(), result, Map.of(file(VERDICT_ATTACHMENT, 0), "sha256:" + DIGEST_B, file(EVIDENCE_ATTACHMENT, 0), "sha256:" + DIGEST_C));
        assertEquals(List.of(new ArtifactCheckEntry(CheckSource.ARTIFACT, "artifact-1", VERDICT_ATTACHMENT, ArtifactCheck.SHA256_MATCHED),
                new ArtifactCheckEntry(CheckSource.EVIDENCE, "evidence-1", EVIDENCE_ATTACHMENT, ArtifactCheck.SHA256_MATCHED)), checks);
    }

    @Test
    void twoItemsThatNameTheSameAttachmentAreANormalInputAndEachIsCheckedAgainstItsOwnDeclaration() {
        StageResult result = resultOf(RunState.PASSED, List.of(new ProviderArtifact("artifact-1", "artifact.v1", VERDICT_ATTACHMENT, DIGEST_B, TARGET, true)),
                List.of(new Evidence("evidence-1", EvidenceKind.TEST, VERDICT_ATTACHMENT, "sha256:" + DIGEST_B, true, null), new Evidence("evidence-2", EvidenceKind.DOCUMENT, VERDICT_ATTACHMENT, "sha256:" + DIGEST_B, true, "again")));
        List<ArtifactCheckEntry> checks = gates.checkArtifacts(plainStage(), result, Map.of(file(VERDICT_ATTACHMENT, 0), "sha256:" + DIGEST_B));
        assertEquals(3, checks.size());
        assertTrue(checks.stream().allMatch(check -> check.check() == ArtifactCheck.SHA256_MATCHED && VERDICT_ATTACHMENT.equals(check.attachmentId())), checks.toString());
    }

    @Test
    void aPassedStageCannotRecordADigestTheAttachmentContradicts() {
        StageResult artifactMismatch = resultOf(RunState.PASSED, List.of(new ProviderArtifact("artifact-1", "artifact.v1", VERDICT_ATTACHMENT, DIGEST_B, TARGET, true)), List.of());
        WorkflowFailure artifact = assertThrows(WorkflowFailure.class, () -> gates.checkArtifacts(plainStage(), artifactMismatch, Map.of(file(VERDICT_ATTACHMENT, 0), "sha256:" + DIGEST_C)));
        assertEquals(WorkflowCode.BINDING_INVALID, artifact.code());
        assertEquals("workflow.service.recordedDigestMatchAttachment", artifact.ref().key());
        assertEquals("ARTIFACT_DIGEST_MISMATCH", artifact.details().get("reason"));
        assertEquals("artifact-1", artifact.details().get("artifactId"));
        assertEquals(VERDICT_ATTACHMENT, artifact.details().get("attachmentId"));
        StageResult evidenceMismatch = resultOf(RunState.PASSED, List.of(), List.of(new Evidence("evidence-1", EvidenceKind.TEST, EVIDENCE_ATTACHMENT, "sha256:" + DIGEST_B, true, null)));
        WorkflowFailure evidence = assertThrows(WorkflowFailure.class, () -> gates.checkArtifacts(plainStage(), evidenceMismatch, Map.of(file(EVIDENCE_ATTACHMENT, 0), "sha256:" + DIGEST_C)));
        assertEquals(WorkflowCode.BINDING_INVALID, evidence.code());
        assertEquals("ARTIFACT_DIGEST_MISMATCH", evidence.details().get("reason"));
        assertEquals(EVIDENCE_ATTACHMENT, evidence.details().get("attachmentId"));
    }

    @ParameterizedTest
    @EnumSource(value = RunState.class, names = "PASSED", mode = EnumSource.Mode.EXCLUDE)
    void aStageThatDidNotPassRecordsADigestTheAttachmentContradictsAsNotChecked(RunState state) {
        StageResult result = resultOf(state, List.of(new ProviderArtifact("artifact-1", "artifact.v1", VERDICT_ATTACHMENT, DIGEST_B, TARGET, true)),
                List.of(new Evidence("evidence-1", EvidenceKind.TEST, EVIDENCE_ATTACHMENT, "sha256:" + DIGEST_B, true, null)));
        List<ArtifactCheckEntry> checks = assertDoesNotThrow(() -> gates.checkArtifacts(plainStage(), result, Map.of(file(VERDICT_ATTACHMENT, 0), "sha256:" + DIGEST_C, file(EVIDENCE_ATTACHMENT, 0), "sha256:" + DIGEST_B)), state.name());
        assertEquals(List.of(new ArtifactCheckEntry(CheckSource.ARTIFACT, "artifact-1", VERDICT_ATTACHMENT, ArtifactCheck.NOT_CHECKED),
                new ArtifactCheckEntry(CheckSource.EVIDENCE, "evidence-1", EVIDENCE_ATTACHMENT, ArtifactCheck.SHA256_MATCHED)), checks, "only the item whose attachment contradicts it is left unchecked");
    }

    @Test
    void anAttachmentTheRecordingDidNotResolveIsTheUseCasesMistakeNotAnAnswer() {
        StageResult result = resultOf(RunState.PASSED, List.of(new ProviderArtifact("artifact-1", "artifact.v1", VERDICT_ATTACHMENT, DIGEST_B, TARGET, true)), List.of());
        assertThrows(IllegalStateException.class, () -> gates.checkArtifacts(plainStage(), result, Map.of()));
    }

    // ------------------------------------------------------------------------------------ the finish judges the attachments of an audit

    private static StageResult auditRecordedOn(CheckSource source) {
        StageResult recorded = result(GOOD);
        if (source == CheckSource.EVIDENCE) {
            recorded = recorded.withEvidence(List.of(new Evidence("gate-verdict", EvidenceKind.DOCUMENT, EVIDENCE_ATTACHMENT, TARGET, true, null)));
        }
        String attachment = source == CheckSource.ARTIFACT ? VERDICT_ATTACHMENT : EVIDENCE_ATTACHMENT;
        return recorded.withArtifactChecks(List.of(new ArtifactCheckEntry(source, "gate-verdict", attachment, ArtifactCheck.SHA256_MATCHED)));
    }

    private static FileReads stored(String attachmentId, AttachmentContents.Standing standing) {
        return stored(attachmentId, 0, standing);
    }

    private static FileReads stored(String attachmentId, int seq, AttachmentContents.Standing standing) {
        return new FileReads(-1, Map.of(new ReadRequest.Stored(file(attachmentId, seq), TARGET), new FileRead<>(standing, null)));
    }

    @ParameterizedTest
    @EnumSource(CheckSource.class)
    void anAuditRecordedOnAnAttachmentThatIsStillIntactPassesTheFinish(CheckSource source) {
        String attachment = source == CheckSource.ARTIFACT ? VERDICT_ATTACHMENT : EVIDENCE_ATTACHMENT;
        assertDoesNotThrow(() -> gates.assertAuditedAttachmentsIntact(postExecution, auditRecordedOn(source), stored(attachment, AttachmentContents.Standing.INTACT)));
    }

    @ParameterizedTest
    @EnumSource(value = AttachmentContents.Standing.class, names = "INTACT", mode = EnumSource.Mode.EXCLUDE)
    void anAuditRecordedOnAnAttachmentThatIsMissingDifferentOrUnreadableFailsTheFinish(AttachmentContents.Standing standing) {
        WorkflowFailure failure = assertThrows(WorkflowFailure.class,
                () -> gates.assertAuditedAttachmentsIntact(postExecution, auditRecordedOn(CheckSource.ARTIFACT), stored(VERDICT_ATTACHMENT, standing)), standing.name());
        assertEquals(WorkflowCode.GATE_FAILED, failure.code(), standing.name());
        assertEquals("workflow.service.auditedAttachmentNotIntact", failure.ref().key());
        assertEquals("AUDIT_TARGET_UNAVAILABLE", failure.details().get("reason"));
        assertEquals("gate-verdict", failure.details().get("artifactId"));
        assertEquals(VERDICT_ATTACHMENT, failure.details().get("attachmentId"));
    }

    @Test
    void theFinishAsksOnlyForTheStandingOfTheAttachmentAnAuditWasRecordedOnAndNeverForAFileOfTheCallersComputer() {
        List<ReadRequest> requests = gates.requestsToFinalize(postExecution, auditRecordedOn(CheckSource.ARTIFACT), "sha256:task");
        assertTrue(requests.contains(new ReadRequest.Stored(file(VERDICT_ATTACHMENT, 0), TARGET)), requests.toString());
        assertTrue(requests.stream().allMatch(request -> request instanceof ReadRequest.Stored || request instanceof ReadRequest.DeclaredSchema), "an original file is not read again: " + requests);
    }

    // ---------------------------------------------------------------------------------------------- a bundle: the files of one attachment

    /** Cases a, b and c of the class's list: an artifact at file 0 and an evidence item at file 1 of one attachment, each against its own file. */
    @Test
    void twoFilesOfOneAttachmentAreEachCheckedAgainstTheirOwnSha256AndTheEntriesCarryTheirNumbers() {
        StageResult result = resultOf(RunState.PASSED, List.of(new ProviderArtifact("artifact-1", "artifact.v1", VERDICT_ATTACHMENT, 0, DIGEST_B, TARGET, true)),
                List.of(new Evidence("evidence-1", EvidenceKind.TEST, VERDICT_ATTACHMENT, 1, "sha256:" + DIGEST_C, true, null)));
        List<ArtifactCheckEntry> checks = gates.checkArtifacts(plainStage(), result, Map.of(file(VERDICT_ATTACHMENT, 0), "sha256:" + DIGEST_B, file(VERDICT_ATTACHMENT, 1), "sha256:" + DIGEST_C));
        assertEquals(List.of(new ArtifactCheckEntry(CheckSource.ARTIFACT, "artifact-1", VERDICT_ATTACHMENT, 0, ArtifactCheck.SHA256_MATCHED),
                new ArtifactCheckEntry(CheckSource.EVIDENCE, "evidence-1", VERDICT_ATTACHMENT, 1, ArtifactCheck.SHA256_MATCHED)), checks);
    }

    @Test
    void theSha256OfFileOneDeclaredForFileZeroIsRefusedOnAPassingStageWithItsNumber() {
        StageResult result = resultOf(RunState.PASSED, List.of(), List.of(new Evidence("evidence-1", EvidenceKind.TEST, VERDICT_ATTACHMENT, 0, "sha256:" + DIGEST_C, true, null)));
        Map<AttachmentFileRef, String> digests = Map.of(file(VERDICT_ATTACHMENT, 0), "sha256:" + DIGEST_B, file(VERDICT_ATTACHMENT, 1), "sha256:" + DIGEST_C);
        WorkflowFailure refused = assertThrows(WorkflowFailure.class, () -> gates.checkArtifacts(plainStage(), result, digests));
        assertEquals(WorkflowCode.BINDING_INVALID, refused.code());
        assertEquals("ARTIFACT_DIGEST_MISMATCH", refused.details().get("reason"));
        assertEquals(VERDICT_ATTACHMENT, refused.details().get("attachmentId"));
        assertEquals(0, refused.details().get("seq"));
    }

    @ParameterizedTest
    @EnumSource(value = RunState.class, names = "PASSED", mode = EnumSource.Mode.EXCLUDE)
    void theSha256OfFileOneDeclaredForFileZeroIsNotCheckedOnAStageThatDidNotPass(RunState state) {
        StageResult result = resultOf(state, List.of(), List.of(new Evidence("evidence-1", EvidenceKind.TEST, VERDICT_ATTACHMENT, 0, "sha256:" + DIGEST_C, true, null)));
        List<ArtifactCheckEntry> checks = gates.checkArtifacts(plainStage(), result, Map.of(file(VERDICT_ATTACHMENT, 0), "sha256:" + DIGEST_B, file(VERDICT_ATTACHMENT, 1), "sha256:" + DIGEST_C));
        assertEquals(List.of(new ArtifactCheckEntry(CheckSource.EVIDENCE, "evidence-1", VERDICT_ATTACHMENT, 0, ArtifactCheck.NOT_CHECKED)), checks, state.name());
    }

    @Test
    void aFileTheRecordingDidNotResolveIsTheUseCasesMistakeEvenWhenAnotherFileOfTheSameAttachmentWasResolved() {
        StageResult result = resultOf(RunState.PASSED, List.of(), List.of(new Evidence("evidence-1", EvidenceKind.TEST, VERDICT_ATTACHMENT, 1, "sha256:" + DIGEST_C, true, null)));
        assertThrows(IllegalStateException.class, () -> gates.checkArtifacts(plainStage(), result, Map.of(file(VERDICT_ATTACHMENT, 0), "sha256:" + DIGEST_C)));
    }

    private static StageResult auditRecordedOnFile(int seq) {
        return result(GOOD).withEvidence(List.of(new Evidence("gate-verdict", EvidenceKind.DOCUMENT, EVIDENCE_ATTACHMENT, seq, TARGET, true, null)))
                .withArtifactChecks(List.of(new ArtifactCheckEntry(CheckSource.EVIDENCE, "gate-verdict", EVIDENCE_ATTACHMENT, seq, ArtifactCheck.SHA256_MATCHED)));
    }

    /** Case d: the finish asks for the file (attachment and number) the audit was recorded on, and a file of that number that is not intact fails it with the number. */
    @Test
    void theFinishAsksForTheStandingOfTheFileOfTheNumberAnAuditWasRecordedOnAndNamesThatNumberWhenItFails() {
        StageResult recorded = auditRecordedOnFile(1);
        List<ReadRequest> requests = gates.requestsToFinalize(postExecution, recorded, "sha256:task");
        assertTrue(requests.contains(new ReadRequest.Stored(file(EVIDENCE_ATTACHMENT, 1), TARGET)), requests.toString());
        assertTrue(requests.stream().noneMatch(request -> request.equals(new ReadRequest.Stored(file(EVIDENCE_ATTACHMENT, 0), TARGET))), "file 0 is not what the audit was recorded on: " + requests);
        assertDoesNotThrow(() -> gates.assertAuditedAttachmentsIntact(postExecution, recorded, stored(EVIDENCE_ATTACHMENT, 1, AttachmentContents.Standing.INTACT)));
        WorkflowFailure failure = assertThrows(WorkflowFailure.class,
                () -> gates.assertAuditedAttachmentsIntact(postExecution, recorded, stored(EVIDENCE_ATTACHMENT, 1, AttachmentContents.Standing.UNREADABLE)));
        assertEquals("AUDIT_TARGET_UNAVAILABLE", failure.details().get("reason"));
        assertEquals(EVIDENCE_ATTACHMENT, failure.details().get("attachmentId"));
        assertEquals(1, failure.details().get("seq"));
        // The standing of file 0 of the same attachment is another question: it was not asked for, so it is no answer to this one (the use case's mistake).
        assertThrows(IllegalStateException.class, () -> gates.assertAuditedAttachmentsIntact(postExecution, recorded, stored(EVIDENCE_ATTACHMENT, 0, AttachmentContents.Standing.INTACT)));
    }

    @Test
    void aCheckThatWasNotMatchedIsNotAskedForNorJudgedAtTheFinish() {
        StageResult unchecked = result(GOOD).withArtifactChecks(List.of(new ArtifactCheckEntry(CheckSource.ARTIFACT, "gate-verdict", VERDICT_ATTACHMENT, ArtifactCheck.NOT_CHECKED)));
        assertTrue(gates.requestsToFinalize(postExecution, unchecked, "sha256:task").stream().noneMatch(ReadRequest.Stored.class::isInstance));
        assertDoesNotThrow(() -> gates.assertAuditedAttachmentsIntact(postExecution, unchecked, FileReads.NONE));
    }

    @Test
    void aMatchedCheckTheResultNoLongerHoldsTheDigestOfFailsTheFinishAndAStageThatIsNotAnAuditIsNotJudged() {
        StageResult orphan = result(GOOD).withArtifactChecks(List.of(new ArtifactCheckEntry(CheckSource.ARTIFACT, "no-such-artifact", EVIDENCE_ATTACHMENT, ArtifactCheck.SHA256_MATCHED)));
        WorkflowFailure failure = assertThrows(WorkflowFailure.class, () -> gates.assertAuditedAttachmentsIntact(postExecution, orphan, FileReads.NONE));
        assertEquals("AUDIT_TARGET_UNAVAILABLE", failure.details().get("reason"));
        assertDoesNotThrow(() -> gates.assertAuditedAttachmentsIntact(plainStage(), orphan, FileReads.NONE), "only a mandatory audit is judged here");
    }
}
