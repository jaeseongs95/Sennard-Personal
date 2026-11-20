// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.continuity.ContinuityService.Candidate;
import io.github.jaeseongs95.sennard.server.continuity.ContinuityService.Decision;
import io.github.jaeseongs95.sennard.server.continuity.ContinuityService.DirectSummary;
import io.github.jaeseongs95.sennard.server.continuity.ContinuityService.Memory;
import io.github.jaeseongs95.sennard.server.continuity.ContinuityService.PurgeReceipt;
import io.github.jaeseongs95.sennard.server.continuity.ContinuityService.ReasonCode;
import io.github.jaeseongs95.sennard.server.continuity.ContinuityService.RestoreToken;
import io.github.jaeseongs95.sennard.server.continuity.ContinuityService.RootDoc;
import io.github.jaeseongs95.sennard.server.continuity.ContinuityService.RunDoc;
import io.github.jaeseongs95.sennard.server.continuity.ContinuityService.SnapshotView;
import io.github.jaeseongs95.sennard.server.continuity.ContinuityService.VerifiedToken;
import io.github.jaeseongs95.sennard.server.continuity.ContinuityService.WorkflowCard;
import io.github.jaeseongs95.sennard.server.continuity.ContinuityService.WorkflowSummary;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.service.store.ContextSource;
import io.github.jaeseongs95.sennard.service.store.MemoryStatus;
import io.github.jaeseongs95.sennard.service.store.RequestReceiptKind;
import io.github.jaeseongs95.sennard.service.store.RootState;
import io.github.jaeseongs95.sennard.service.store.RunState;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The documents of the continuity service as records: the wire form of what the tools answer, the signed restore token, and the workflow documents the card is made
 * from. The service's database paths are covered by {@code ContinuityMessagingToolContractTest} (checkpoint, replay, inspect, load, token refusal, suppress, purge)
 * and the hook fixtures; these cases cover the shapes without a store.
 */
class ContinuityRecordsTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String DIGEST = "sha256:" + "a".repeat(64);

    private static JsonNode wire(Object value) {
        return MAPPER.readTree(MAPPER.writeValueAsString(value));
    }

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    private static String reasonOf(String payload, long now) {
        BindingException refused = assertThrows(BindingException.class, () -> ContinuityService.checkedToken(MAPPER, payload.getBytes(StandardCharsets.UTF_8), now));
        assertEquals("BINDING_INVALID", refused.code().wireCode());
        return refused.code().name();
    }

    // ------------------------------------------------------------------------------------------------------------- restore token

    @Test
    void aDirectTokenIsSignedOverTheSortedKeysOfItsPayload() {
        RestoreToken token = new RestoreToken(1, "c1", 3L, "DIRECT", "tok", DIGEST, null, 1000L, null, null);

        assertEquals("{\"c\":\"c1\",\"d\":\"" + DIGEST + "\",\"e\":3,\"s\":\"DIRECT\",\"t\":\"tok\",\"v\":1,\"x\":1000}", CanonicalJson.of(MAPPER.valueToTree(token)));
    }

    @Test
    void aWorkflowTokenCarriesTheRevisionAndNoStateToken() {
        RestoreToken token = new RestoreToken(1, "c1", 3L, "WORKFLOW", null, DIGEST, 7L, 1000L, null, null);

        assertEquals("{\"c\":\"c1\",\"d\":\"" + DIGEST + "\",\"e\":3,\"r\":7,\"s\":\"WORKFLOW\",\"v\":1,\"x\":1000}", CanonicalJson.of(MAPPER.valueToTree(token)));
    }

    @Test
    void aSignedPayloadIsReadBackWithItsSource() {
        RestoreToken token = new RestoreToken(1, "c1", 3L, "WORKFLOW", null, DIGEST, 7L, 1000L, null, null);
        String payload = CanonicalJson.of(MAPPER.valueToTree(token));

        VerifiedToken verified = ContinuityService.checkedToken(MAPPER, payload.getBytes(StandardCharsets.UTF_8), 999);

        assertEquals(ContextSource.WORKFLOW, verified.source());
        assertEquals(7L, verified.token().revision());
        assertEquals(token, verified.token());
    }

    @Test
    void aTokenPastItsTimeIsExpired() {
        String payload = CanonicalJson.of(MAPPER.valueToTree(new RestoreToken(1, "c1", 3L, "DIRECT", "tok", DIGEST, null, 1000L, null, null)));

        assertEquals("CONTINUITY_TOKEN_EXPIRED", reasonOf(payload, 1001));
        assertEquals("c1", ContinuityService.checkedToken(MAPPER, payload.getBytes(StandardCharsets.UTF_8), 1000).token().correlation());
    }

    @Test
    void aPayloadThatIsNotWhatTheServerIssuesIsInvalidAndOneThatIsNoDocumentIsMalformed() {
        // a source that names no constant is a refusal, not an exception out of the binding
        assertEquals("CONTINUITY_TOKEN_INVALID", reasonOf("{\"c\":\"c1\",\"d\":\"" + DIGEST + "\",\"e\":3,\"s\":\"OTHER\",\"t\":\"tok\",\"v\":1,\"x\":1000}", 0));
        assertEquals("CONTINUITY_TOKEN_INVALID", reasonOf("{\"c\":\"c1\",\"d\":\"" + DIGEST + "\",\"e\":3,\"s\":\"DIRECT\",\"v\":1,\"x\":1000}", 0));
        assertEquals("CONTINUITY_TOKEN_INVALID", reasonOf("{\"c\":\"c1\",\"d\":\"" + DIGEST + "\",\"e\":3,\"s\":\"DIRECT\",\"t\":\"\",\"v\":1,\"x\":1000}", 0));
        assertEquals("CONTINUITY_TOKEN_INVALID", reasonOf("{\"c\":\"c1\",\"d\":\"sha256:short\",\"e\":3,\"s\":\"DIRECT\",\"t\":\"tok\",\"v\":1,\"x\":1000}", 0));
        assertEquals("CONTINUITY_TOKEN_INVALID", reasonOf("{\"c\":\"c1\",\"d\":\"" + DIGEST + "\",\"e\":3,\"s\":\"DIRECT\",\"t\":\"tok\",\"v\":2,\"x\":1000}", 0));
        assertEquals("CONTINUITY_TOKEN_INVALID", reasonOf("{\"c\":\"c1\",\"d\":\"" + DIGEST + "\",\"e\":3,\"r\":null,\"s\":\"WORKFLOW\",\"v\":1,\"x\":1000}", 0));
        assertEquals("CONTINUITY_TOKEN_INVALID", reasonOf("{\"c\":\"c1\",\"d\":\"" + DIGEST + "\",\"e\":3,\"f\":\"p\",\"s\":\"DIRECT\",\"t\":\"tok\",\"v\":1,\"x\":1000}", 0));
        assertEquals("CONTINUITY_TOKEN_MALFORMED", reasonOf("not json", 0));
        assertEquals("CONTINUITY_TOKEN_MALFORMED", reasonOf("{\"c\":\"c1\",\"e\":\"three\",\"s\":\"DIRECT\",\"v\":1}", 0));
    }

    @Test
    void aForkTokenNamesItsParentAndItsEpoch() {
        String payload = "{\"c\":\"c1\",\"d\":\"" + DIGEST + "\",\"e\":3,\"f\":\"parent\",\"g\":2,\"s\":\"DIRECT\",\"t\":\"tok\",\"v\":1,\"x\":1000}";

        RestoreToken token = ContinuityService.checkedToken(MAPPER, payload.getBytes(StandardCharsets.UTF_8), 0).token();

        assertEquals("parent", token.forkCorrelation());
        assertEquals(2L, token.forkEpoch());
    }

    @Test
    void aSourceNameIsLookedUpWithoutAnException() {
        assertEquals(ContextSource.DIRECT, ContinuityService.sourceOf("DIRECT"));
        assertEquals(ContextSource.WORKFLOW, ContinuityService.sourceOf("WORKFLOW"));
        assertNull(ContinuityService.sourceOf("direct"));
        assertNull(ContinuityService.sourceOf(null));
    }

    // ------------------------------------------------------------------------------------------------------------- tool results

    @Test
    void aRejectionShowsItsNullsAndADeferralItsSummaryAndToken() {
        Candidate rejected = new Candidate("1.0.0", Decision.REJECT, List.of(ReasonCode.NO_RESTORE_CANDIDATE), null, null);
        assertEquals(json("{\"schemaVersion\":\"1.0.0\",\"decision\":\"REJECT\",\"reasonCodes\":[\"NO_RESTORE_CANDIDATE\"],\"summary\":null,\"restoreToken\":null}"), wire(rejected));

        Candidate direct = new Candidate("1.0.0", Decision.DEFER, List.of(ReasonCode.EXPLICIT_LOAD_REQUIRED),
                new DirectSummary("1.0.0", ContextSource.DIRECT, "c1", 3, "st", "ACTIVE", DIGEST, "2026-01-01T00:00:00Z"), "body.sig");
        assertEquals(json("{\"schemaVersion\":\"1.0.0\",\"decision\":\"DEFER\",\"reasonCodes\":[\"EXPLICIT_LOAD_REQUIRED\"],\"summary\":{\"schemaVersion\":\"1.0.0\",\"source\":\"DIRECT\","
                + "\"taskCorrelation\":\"c1\",\"epoch\":3,\"stateToken\":\"st\",\"status\":\"ACTIVE\",\"memoryDigest\":\"" + DIGEST + "\",\"updatedAt\":\"2026-01-01T00:00:00Z\"},"
                + "\"restoreToken\":\"body.sig\"}"), wire(direct));

        Candidate workflow = new Candidate("1.0.0", Decision.DEFER, List.of(ReasonCode.EXPLICIT_LOAD_REQUIRED, ReasonCode.FORK_PARENT_NOT_PINNED),
                new WorkflowSummary("1.0.0", ContextSource.WORKFLOW, "c1", 3, 7, "OPEN", DIGEST, ""), "body.sig");
        JsonNode summary = wire(workflow).path("summary");
        assertEquals(json("{\"schemaVersion\":\"1.0.0\",\"source\":\"WORKFLOW\",\"taskCorrelation\":\"c1\",\"epoch\":3,\"revision\":7,\"status\":\"OPEN\",\"snapshotDigest\":\"" + DIGEST
                + "\",\"updatedAt\":\"\"}"), summary);
        assertEquals(json("[\"EXPLICIT_LOAD_REQUIRED\",\"FORK_PARENT_NOT_PINNED\"]"), wire(workflow).path("reasonCodes"));
    }

    @Test
    void aSnapshotViewShowsItsMemoryOrItsNulls() {
        Memory memory = new Memory(MemoryStatus.ACTIVE, new ContinuityTools.Core("obj", List.of("done"), List.of(), List.of("d"), List.of(), List.of(), List.of("next")),
                List.of(new ContinuityTools.EvidenceRef("a1", "loc", DIGEST, true)));

        assertEquals(json("{\"schemaVersion\":\"1.0.0\",\"source\":\"DIRECT\",\"taskCorrelation\":\"c1\",\"epoch\":2,\"stateToken\":\"st\",\"memory\":{\"status\":\"ACTIVE\","
                + "\"core\":{\"objective\":\"obj\",\"completionCriteria\":[\"done\"],\"constraints\":[],\"decisions\":[\"d\"],\"progress\":[],\"blockers\":[],\"nextActions\":[\"next\"]},"
                + "\"evidenceRefs\":[{\"artifactId\":\"a1\",\"locator\":\"loc\",\"digest\":\"" + DIGEST + "\",\"verified\":true}]},\"memoryDigest\":\"" + DIGEST
                + "\",\"createdAt\":\"t0\",\"updatedAt\":\"t1\"}"), wire(new SnapshotView("1.0.0", ContextSource.DIRECT, "c1", 2, "st", memory, DIGEST, "t0", "t1")));
        assertEquals(json("{\"schemaVersion\":\"1.0.0\",\"source\":\"DIRECT\",\"taskCorrelation\":\"c1\",\"epoch\":2,\"stateToken\":\"st\",\"memory\":null,\"memoryDigest\":null,"
                + "\"createdAt\":\"t0\",\"updatedAt\":\"t1\"}"), wire(new SnapshotView("1.0.0", ContextSource.DIRECT, "c1", 2, "st", null, null, "t0", "t1")));
    }

    @Test
    void aStoredMemoryIsReadBackAsTheRecordItWasWrittenFrom() {
        Memory memory = new Memory(MemoryStatus.PAUSED, new ContinuityTools.Core("obj", List.of(), List.of(), List.of(), List.of(), List.of("b"), List.of()), List.of());

        assertEquals(memory, MAPPER.readValue(CanonicalJson.of(MAPPER.valueToTree(memory)), Memory.class));
    }

    @Test
    void aPurgeReceiptIsTheAnswerAndTheStoredReceiptRead() {
        String stored = "{\"schemaVersion\":\"1.0.0\",\"purged\":true,\"epoch\":2,\"stateToken\":\"s1\",\"emptyStateToken\":\"s2\",\"tombstoneDigest\":\"" + DIGEST + "\",\"purgedAt\":\"t\",\"extra\":1}";

        PurgeReceipt receipt = ContinuityService.storedDocument(MAPPER, stored, PurgeReceipt.class);

        assertEquals(new PurgeReceipt("1.0.0", true, 2, "s1", "s2", DIGEST, "t"), receipt);
        assertEquals(json(stored.replace(",\"extra\":1", "")), wire(receipt));
    }

    @Test
    void aDamagedStoredDocumentIsNoDocumentAndAnUnknownReceiptKindIsDamaged() {
        assertNull(ContinuityService.storedDocument(MAPPER, "{not json", PurgeReceipt.class));
        assertNull(ContinuityService.storedDocument(MAPPER, "{\"kind\":\"SOMETHING\",\"epoch\":1}", ContinuityService.CheckpointReceipt.class));
        ContinuityService.CheckpointReceipt receipt = ContinuityService.storedDocument(MAPPER, "{\"schemaVersion\":\"1.0.0\",\"kind\":\"CHECKPOINT\",\"epoch\":4,\"stateToken\":\"s\",\"memoryDigest\":\"d\"}",
                ContinuityService.CheckpointReceipt.class);
        assertEquals(new ContinuityService.CheckpointReceipt(RequestReceiptKind.CHECKPOINT, 4L, "s", "d"), receipt);
    }

    // ------------------------------------------------------------------------------------------------------------ workflow card

    private static final String ROOT = "{\"state\":\"OPEN\",\"revision\":5,\"rootId\":\"root-1\",\"updatedAt\":\"2026-01-02T00:00:00Z\",\"ignored\":{\"a\":1},"
            + "\"taskEnvelope\":{\"taskId\":\"task-1\",\"objective\":\"Do the thing\",\"scope\":{\"included\":[\"a\",\"b\"],\"excluded\":[]},"
            + "\"acceptanceCriteria\":[\"c1\",\"c2\",\"c3\",\"c4\",\"c5\",\"c6\"],\"constraints\":[\"k\"],"
            + "\"authorization\":{\"allowedActions\":[\"edit\"],\"prohibitedActions\":[],\"approvalRequired\":[\"deploy\"]}}}";

    /** The attachment the artifact {@code index} of a receipt below names (a UUID, as a stage result writes it). */
    private static String attachment(int index) {
        return "00000000-0000-4000-8000-00000000000" + index;
    }

    private static String receipt(int artifacts, String checkSource, String check) {
        return receipt(artifacts, checkSource, check, attachment(0));
    }

    /** A receipt whose one recorded check names {@code checkedAttachment} for the artifact {@code a0}. */
    private static String receipt(int artifacts, String checkSource, String check, String checkedAttachment) {
        StringBuilder list = new StringBuilder();
        for (int index = 0; index < artifacts; index++) {
            list.append(index == 0 ? "" : ",").append("{\"artifactId\":\"a").append(index).append("\",\"attachmentId\":\"").append(attachment(index)).append("\",\"digest\":\"d").append(index).append("\"}");
        }
        return "{\"state\":\"RUNNING\",\"blockers\":[\"b1\"],\"unresolved\":[],\"plan\":{\"stages\":[{\"stageId\":\"s1\"},{\"stageId\":\"s2\"}],\"currentStageId\":\"s1\",\"nextStageId\":\"s2\"},"
                + "\"stageResults\":[{\"stageId\":\"s1\",\"state\":\"PASSED\",\"output\":{\"artifacts\":[" + list + "]},"
                + "\"artifactChecks\":[{\"source\":\"" + checkSource + "\",\"check\":\"" + check + "\",\"artifactId\":\"a0\",\"attachmentId\":\"" + checkedAttachment + "\"}]}]}";
    }

    private static WorkflowCard card(RunDoc run) {
        return ContinuityService.card(MAPPER, "c1", 2, MAPPER.readValue(ROOT, RootDoc.class), run);
    }

    @Test
    void theCardOfARootAndItsRunKeepsItsKeysBoundsAndDigest() {
        WorkflowCard card = card(MAPPER.readValue(receipt(2, "ARTIFACT", "SHA256_MATCHED"), RunDoc.class));
        JsonNode tree = wire(card);

        assertEquals(new TreeSet<>(List.of("schemaVersion", "source", "taskCorrelation", "epoch", "revision", "rootId", "rootState", "taskId", "objective", "includedScope", "excludedScope",
                "acceptanceCriteria", "constraints", "authorization", "workflowState", "currentStageId", "nextStageId", "blockers", "unresolved", "evidenceRefs", "evidenceRefsOmitted",
                "updatedAt", "snapshotDigest")), new TreeSet<>(tree.propertyNames()));
        assertEquals("WORKFLOW", tree.path("source").asString());
        assertEquals("OPEN", tree.path("rootState").asString());
        assertEquals("RUNNING", tree.path("workflowState").asString());
        assertEquals(5, tree.path("revision").asInt());
        assertEquals(json("[\"c1\",\"c2\",\"c3\",\"c4 [+2 omitted]\"]"), tree.path("acceptanceCriteria"));
        assertEquals(json("{\"allowedActions\":[\"edit\"],\"prohibitedActions\":[],\"approvalRequired\":[\"deploy\"]}"), tree.path("authorization"));
        // The card names an artifact's evidence by the attachment that keeps it, in the evidence reference's locator.
        assertEquals(json("[{\"artifactId\":\"a0\",\"locator\":\"" + attachment(0) + "\",\"digest\":\"d0\",\"verified\":true},{\"artifactId\":\"a1\",\"locator\":\"" + attachment(1)
                + "\",\"digest\":\"d1\",\"verified\":false}]"), tree.path("evidenceRefs"));
        assertEquals(0, tree.path("evidenceRefsOmitted").asInt());
        // the digest is that of the canonical form of the card without it
        ObjectNode without = MAPPER.valueToTree(card);
        without.remove("snapshotDigest");
        assertEquals(CanonicalJson.digest(without), card.snapshotDigest());
        assertTrue(card.snapshotDigest().startsWith("sha256:"));
    }

    @Test
    void aCardOfARootWithoutARunShowsTheNullsAndNoEvidence() {
        WorkflowCard card = card(null);
        JsonNode tree = wire(card);

        assertTrue(tree.path("workflowState").isNull());
        assertTrue(tree.path("currentStageId").isNull());
        assertTrue(tree.path("nextStageId").isNull());
        assertEquals(json("[]"), tree.path("blockers"));
        assertEquals(json("[]"), tree.path("evidenceRefs"));
        // a null that the digest sees: the same card with a run has another digest
        assertFalse(card.snapshotDigest().equals(card(MAPPER.readValue(receipt(0, "ARTIFACT", "SHA256_MATCHED"), RunDoc.class)).snapshotDigest()));
    }

    @Test
    void onlyTheFirstSixEvidenceRefsAreShownAndTheRestCounted() {
        WorkflowCard card = card(MAPPER.readValue(receipt(9, "ARTIFACT", "SHA256_MATCHED"), RunDoc.class));

        assertEquals(6, card.evidenceRefs().size());
        assertEquals(3, card.evidenceRefsOmitted());
        assertEquals("a5", card.evidenceRefs().getLast().artifactId());
    }

    @Test
    void anArtifactIsVerifiedOnlyByAnArtifactCheckThatMatched() {
        assertTrue(card(MAPPER.readValue(receipt(1, "ARTIFACT", "SHA256_MATCHED"), RunDoc.class)).evidenceRefs().getFirst().verified());
        assertFalse(card(MAPPER.readValue(receipt(1, "EVIDENCE", "SHA256_MATCHED"), RunDoc.class)).evidenceRefs().getFirst().verified());
        assertFalse(card(MAPPER.readValue(receipt(1, "ARTIFACT", "NOT_CHECKED"), RunDoc.class)).evidenceRefs().getFirst().verified());
        // a check text that names no constant is no check, and no exception
        assertFalse(card(MAPPER.readValue(receipt(1, "SOMETHING", "SHA256_MATCHED"), RunDoc.class)).evidenceRefs().getFirst().verified());
        assertFalse(card(MAPPER.readValue(receipt(1, "ARTIFACT", "SOMETHING"), RunDoc.class)).evidenceRefs().getFirst().verified());
    }

    @Test
    void aMatchedCheckOfAnotherAttachmentThanTheOneTheArtifactNamesDoesNotVerifyIt() {
        WorkflowCard card = card(MAPPER.readValue(receipt(1, "ARTIFACT", "SHA256_MATCHED", attachment(7)), RunDoc.class));
        assertFalse(card.evidenceRefs().getFirst().verified());
        assertEquals(attachment(0), card.evidenceRefs().getFirst().locator(), "the card still shows the attachment the artifact names");
    }

    /**
     * A stage result points at a file of an attachment, and the receipt the server writes carries {@code seq} for a file other than the first (artifacts and checks). The card is made from
     * the same receipt as before: the key is read past, the artifact is verified by the matched check that names its attachment, and the card keeps naming the attachment.
     */
    @Test
    void aReceiptThatNamesFilesOfABundleByTheirNumberIsStillReadAndTheCardNamesTheAttachment() {
        String bundle = attachment(3);
        String text = receipt(1, "ARTIFACT", "SHA256_MATCHED", bundle).replace("\"attachmentId\":\"" + attachment(0) + "\",\"digest\"", "\"attachmentId\":\"" + bundle + "\",\"seq\":1,\"digest\"")
                .replace("\"artifactId\":\"a0\",\"attachmentId\":\"" + bundle + "\"}]", "\"artifactId\":\"a0\",\"attachmentId\":\"" + bundle + "\",\"seq\":1}]");
        assertTrue(text.contains("\"seq\":1,\"digest\"") && text.contains("\"seq\":1}]"), "both the artifact and its check name file 1: " + text);

        WorkflowCard card = card(MAPPER.readValue(text, RunDoc.class));

        JsonNode first = wire(card).path("evidenceRefs").get(0);
        assertEquals(bundle, first.path("locator").asString());
        assertTrue(first.path("verified").asBoolean(), first.toString());
        assertFalse(first.has("seq"), "the card names the attachment that keeps the evidence: " + first);
    }

    @Test
    void aWorkflowDocumentWithMissingValuesGetsTheDefaultsTheTreeReadingGave() {
        RootDoc root = MAPPER.readValue("{\"state\":\"COMPLETED\",\"unknown\":[1,2]}", RootDoc.class);

        assertEquals(RootState.COMPLETED, root.state());
        assertEquals(0L, root.revision());
        assertEquals("", root.rootId());
        assertEquals("", root.updatedAt());
        assertEquals("", root.taskEnvelope().objective());
        assertEquals(List.of(), root.taskEnvelope().scope().included());
        assertEquals(List.of(), root.taskEnvelope().authorization().approvalRequired());

        RunDoc run = MAPPER.readValue("{\"state\":\"PASSED\"}", RunDoc.class);
        assertEquals(RunState.PASSED, run.state());
        assertEquals(List.of(), run.stageResults());
        assertEquals(List.of(), run.plan().stages());
        assertNull(run.plan().currentStageId());
        assertEquals(List.of(), run.blockers());
    }

    @Test
    void aStateThatNamesNoConstantIsABindingError() {
        assertThrows(JacksonException.class, () -> MAPPER.readValue("{\"state\":\"BOGUS\"}", RunDoc.class));
        assertThrows(JacksonException.class, () -> MAPPER.readValue("{\"state\":\"BOGUS\"}", RootDoc.class));
        assertThrows(JacksonException.class, () -> MAPPER.readValue("{\"state\":\"RUNNING\",\"stageResults\":{\"a\":1}}", RunDoc.class));
    }

    @Test
    void aTextLongerThanItsBoundEndsInAnEllipsis() {
        assertEquals("abc", ContinuityService.boundedText("abc", 3));
        assertEquals("ab…", ContinuityService.boundedText("abcd", 3));
    }
}
