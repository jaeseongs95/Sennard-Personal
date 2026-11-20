// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jaeseongs95.sennard.service.store.RunState;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stage with a receipt policy is stored by reference only: what the result holds is a fixed token or an opaque reference. The attachment a stage result names an artifact or an
 * evidence item by (a UUID, AGS3-D-0264) is such a reference and is accepted as it is; a path of the caller's computer is no reference and is refused where it stands. The cases are
 * run through {@link ReceiptPolicy#enforce} over the real plan and receipt samples.
 */
class ReceiptPolicyAttachmentTest {
    private static final JsonMapper MAPPER = ReceiptSamples.MAPPER;
    private static final String ATTACHMENT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String OTHER_ATTACHMENT = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final Set<String> FIXED = Set.of("artifact.v1");
    private static WorkflowReceipt receipt;

    @BeforeAll
    static void wire() throws IOException {
        receipt = ReceiptSamples.started();
    }

    /** A stage that is stored by reference only, produces {@code artifact-1} and takes the artifacts {@code requiredInputs}. */
    private static WorkflowPlan.Stage policyStage(List<String> requiredInputs) {
        WorkflowPlan.Stage base = PlanSamples.stage("stage-01", "minimal-implementation", List.of(), PlanSamples.openGate(), null);
        return new WorkflowPlan.Stage(base.stageId(), base.order(), base.requiredCapability(), null, null, base.satisfiedCapabilities(), base.skillId(), base.phase(), base.selectionReason(), base.state(),
                null, null, base.requiredArtifacts(), base.riskGate(), base.providerKey(), base.executionClass(), base.phaseOrder(), requiredInputs, base.inputBindings(), List.of("artifact-1"),
                base.outputSchema(), base.resultSchema(), base.stateMapping(), base.gate(), new StageReceiptPolicy(StageReceiptPolicy.Mode.REFERENCE_ONLY, null, null, null, null));
    }

    private static StageResult result(String artifactAttachment, List<Evidence> evidence) {
        ProviderResult output = new ProviderResult("1.0.0", OutputKind.OUTPUT, MAPPER.createObjectNode(), List.of(new ProviderArtifact("artifact-1", "artifact.v1", artifactAttachment, ReceiptSamples.DIGEST,
                ReceiptSamples.DIGEST, true)), null);
        return new StageResult("1.0.0", "run-task-1-1", "stage-01", 0, RunState.PASSED, output, evidence, List.of(), List.of(), null, null, null, null, null);
    }

    private static Evidence evidence(String artifactId, String attachmentId, boolean verified) {
        return new Evidence(artifactId, EvidenceKind.FILE, attachmentId, ReceiptSamples.DIGEST, verified, "");
    }

    private static void enforce(WorkflowPlan.Stage stage, StageResult result) {
        ReceiptPolicy.enforce(receipt, receipt.plan(), stage, result, FIXED, MAPPER);
    }

    @Test
    void anAttachmentIdOfAnArtifactAndOfAnEvidenceItemIsAnOpaqueReferenceAndIsAccepted() {
        StageResult result = result(ATTACHMENT, List.of(evidence("artifact-1", OTHER_ATTACHMENT, true)));
        assertDoesNotThrow(() -> enforce(policyStage(List.of()), result));
    }

    @Test
    void twoItemsThatNameTheSameAttachmentAreAccepted() {
        StageResult result = result(ATTACHMENT, List.of(evidence("artifact-1", ATTACHMENT, true), evidence("artifact-1", ATTACHMENT, false)));
        assertDoesNotThrow(() -> enforce(policyStage(List.of()), result));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/work/a.txt", "C:\\work\\a.txt", "a plain sentence about the file", "not-a-uuid"})
    void aTextThatIsNotAReferenceIsRefusedAsAnArtifactsAttachmentWhereItStands(String text) {
        WorkflowFailure refused = assertThrows(WorkflowFailure.class, () -> enforce(policyStage(List.of()), result(text, List.of())), text);
        assertEquals(WorkflowCode.INVALID_INPUT, refused.code(), text);
        assertEquals("workflow.receiptPolicy.referenceReceiptPolicyRejectedFree", refused.ref().key(), text);
        assertEquals("/output/artifacts/0/attachmentId", refused.details().get("location"), text);
    }

    @Test
    void aTextThatIsNotAReferenceIsRefusedAsAnEvidenceItemsAttachmentWhereItStands() {
        WorkflowFailure refused = assertThrows(WorkflowFailure.class, () -> enforce(policyStage(List.of()), result(ATTACHMENT, List.of(evidence("artifact-1", "/work/a.txt", true)))));
        assertEquals(WorkflowCode.INVALID_INPUT, refused.code());
        assertEquals("/evidence/0/attachmentId", refused.details().get("location"));
    }

    @Test
    void aRequiredInputIsSatisfiedByAVerifiedEvidenceItemThatNamesAnAttachmentAndNotByAnUnverifiedOne() {
        WorkflowPlan.Stage stage = policyStage(List.of("input-1"));
        assertDoesNotThrow(() -> enforce(stage, result(ATTACHMENT, List.of(evidence("input-1", OTHER_ATTACHMENT, true)))));
        WorkflowFailure missing = assertThrows(WorkflowFailure.class, () -> enforce(stage, result(ATTACHMENT, List.of(evidence("input-1", OTHER_ATTACHMENT, false)))));
        assertEquals(WorkflowCode.MISSING_EVIDENCE, missing.code());
        assertEquals("workflow.receiptPolicy.referenceStagePrerequisitesRequireVerified", missing.ref().key());
    }
}
