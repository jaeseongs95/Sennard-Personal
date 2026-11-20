// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.service.store.RunState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The workflow receipt and the stage result are records that Jackson writes and reads. The cases, each run against the contract files themselves:
 * <ol>
 * <li>the receipt of a run that has just started passes the receipt schema, and reading it and writing it again gives the same canonical form and digest;</li>
 * <li>a receipt that holds a recorded stage result (with what the server owns) does the same, and the result keeps the keys the schema lists;</li>
 * <li>a result as a caller sends it has none of what the server owns, writes none of those keys, and binds from its JSON with them left out;</li>
 * <li>an adapter error writes {@code output} and the error's {@code details} as explicit nulls, which the schema requires;</li>
 * <li>the details of an error, and the output of a provider, are the provider's own JSON and come back as they went in, numbers and nulls included;</li>
 * <li>{@code callsNotObserved} is absent before finalize, {@code null} when the session's calls could not be read, and a list otherwise: three documents that stay three;</li>
 * <li>a receipt text written by the earlier tree-based writer (other key order) reads and writes back to the same canonical form and digest;</li>
 * <li>the compact summary passes its schema for each shape of receipt and writes {@code declaredSkillsNotObserved} only when there is something to show;</li>
 * <li>a key the record does not list, and a kind or a state the schema does not name, are refused;</li>
 * <li>10: the pointers of a result (output file, artifacts, evidence, and the server's checks) name a file of an attachment, and the file number {@code seq} is written, read and digested.</li>
 * </ol>
 */
class ReceiptDocumentsTest {
    private static final JsonMapper MAPPER = ReceiptSamples.MAPPER;
    /** The attachment that holds an output the result names by file (a UUID, as the contract says). */
    private static final String OUTPUT_ATTACHMENT = "77777777-7777-4777-8777-777777777777";

    private static ContractValidator validator;
    private static DocumentDigests digests;
    private static WorkflowReceipt started;
    private static String stageId;

    @BeforeAll
    static void wire() throws IOException {
        validator = new ContractValidator(MAPPER, new ContentRoot(PlanSamples.REPO));
        digests = new DocumentDigests(MAPPER);
        started = ReceiptSamples.started();
        stageId = started.plan().stages().get(0).stageId();
    }

    private static <T> void assertRoundTrip(T document, Class<T> type) {
        String text = MAPPER.writeValueAsString(document);
        T read = MAPPER.readValue(text, type);
        assertEquals(document, read);
        assertEquals(CanonicalJson.of(MAPPER.readTree(text)), CanonicalJson.of(MAPPER.valueToTree(read)));
        assertEquals(digests.of(document), digests.of(read));
    }

    private static Set<String> names(JsonNode object) {
        Set<String> names = new TreeSet<>();
        object.propertyNames().forEach(names::add);
        return names;
    }

    private static Set<String> schemaProperties(String file, String... path) throws IOException {
        JsonNode schema = MAPPER.readTree(Files.readString(PlanSamples.REPO.resolve("claude-plugin/contracts").resolve(file), StandardCharsets.UTF_8));
        for (String step : path) {
            schema = schema.get(step);
        }
        return names(schema.get("properties"));
    }

    private static WorkflowReceipt withResult(StageResult result) {
        return started.withStageResult(result).withClaims(List.of(new UnverifiedClaimEntry(stageId, UnverifiedClaim.EVIDENCE_CONTENT, "artifact-1"), new UnverifiedClaimEntry(null, UnverifiedClaim.USER_APPROVAL, null)))
                .withRevision(1);
    }

    @Test
    void theReceiptOfAStartedRunPassesItsSchemaAndKeepsItsCanonicalFormAndDigestWhenReadAndWrittenAgain() {
        assertDoesNotThrow(() -> validator.workflowReceipt(started));
        assertRoundTrip(started, WorkflowReceipt.class);
        JsonNode tree = MAPPER.valueToTree(started);
        assertTrue(tree.has("error") && tree.get("error").isNull(), "the schema requires the key: " + tree);
        assertTrue(tree.get("skillDeclaration").has("advice") && tree.get("skillDeclaration").get("advice").isNull());
        assertFalse(tree.get("skillDeclaration").has("callsNotObserved"), "the key is left out until finalize");
        assertEquals("RUNNING", tree.get("state").asString());
        assertEquals(0, tree.get("stageResults").size());
    }

    @Test
    void aReceiptThatHoldsARecordedStageResultPassesItsSchemaAndWritesTheKeysTheSchemaLists() throws IOException {
        WorkflowReceipt receipt = withResult(ReceiptSamples.recordedResult(stageId));
        assertDoesNotThrow(() -> validator.workflowReceipt(receipt));
        assertRoundTrip(receipt, WorkflowReceipt.class);
        JsonNode result = MAPPER.valueToTree(receipt).get("stageResults").get(0);
        Set<String> expectedKeys = schemaProperties("stage-result.v1.schema.json");
        expectedKeys.remove("outputFile");
        assertEquals(expectedKeys, names(result), "every key but the file reference, which this result has no use for");
        assertEquals("FILE".toLowerCase(), result.get("evidence").get(0).get("kind").asString());
        assertEquals("SHA256_MATCHED", result.get("artifactChecks").get(0).get("check").asString());
        assertEquals("ARTIFACT", result.get("artifactChecks").get(0).get("source").asString());
        assertEquals("HOST_OBSERVED", result.get("recordedBy").get("provenance").asString());
        assertEquals("2026-11-11T09:00:00.123456789Z", result.get("executionContext").get("observedAt").asString());
        assertEquals(2, MAPPER.valueToTree(receipt).get("unverifiedClaims").size());
        assertTrue(MAPPER.valueToTree(receipt).get("unverifiedClaims").get(1).get("stageId").isNull(), "a claim about a root names no stage, and the schema requires the key");
    }

    @Test
    void aResultAsACallerSendsItHasNoneOfWhatTheServerOwnsAndWritesNoneOfThose() throws IOException {
        StageResult sent = ReceiptSamples.callerResult(stageId, RunState.PASSED);
        assertDoesNotThrow(() -> validator.stageResult(sent));
        JsonNode tree = MAPPER.valueToTree(sent);
        for (String serverOwned : List.of("executionContext", "artifactChecks", "recordedBy", "outputFile")) {
            assertFalse(tree.has(serverOwned), serverOwned);
        }
        assertEquals(Set.of("schemaVersion", "runId", "stageId", "expectedRevision", "state", "output", "evidence", "findings", "blockers", "error"), names(tree));
        StageResult bound = validator.read("stage-result.v1.schema.json", tree, StageResult.class);
        assertEquals(sent, bound);
        assertNull(bound.executionContext());
        assertNull(bound.artifactChecks());
        assertNull(bound.recordedBy());
        assertNull(bound.outputFile());
        assertRoundTrip(sent, StageResult.class);
    }

    @Test
    void aResultWhoseOutputSitsInAFileWritesTheOutputAsNullAndTheFileReference() {
        // A JSON null is read as the null node, which is what a result read from its JSON holds.
        ProviderResult inFile = new ProviderResult("1.0.0", OutputKind.OUTPUT, MAPPER.nullNode(), List.of(), null);
        StageResult result = new StageResult("1.0.0", "run-task-1-1", stageId, 0, RunState.PASSED, inFile, List.of(), List.of(), List.of(), null, null, null, null, new OutputFile(OUTPUT_ATTACHMENT, ReceiptSamples.DIGEST));
        assertDoesNotThrow(() -> validator.stageResult(result));
        JsonNode tree = MAPPER.valueToTree(result);
        assertTrue(tree.get("output").has("output") && tree.get("output").get("output").isNull());
        assertEquals(OUTPUT_ATTACHMENT, tree.get("outputFile").get("attachmentId").asString());
        assertFalse(tree.get("outputFile").has("locator"), "the server names an output file by its attachment and never by a path");
        assertEquals(Set.of("attachmentId", "digest"), names(tree.get("outputFile")), "the first file (seq 0) is the default and is not written");
        assertTrue(result.hasOutputFile());
        assertFalse(inFile.hasOutput());
        assertRoundTrip(result, StageResult.class);
    }

    /** Case 10: every pointer of a result names a file of an attachment, and the number of the file is kept in the schema's own word, through the schema, the reading and the digest. */
    @Test
    void aResultThatPointsAtTheFilesOfABundleKeepsTheNumberOfEachFileThroughTheSchemaAndTheReceipt() {
        ProviderResult inFile = new ProviderResult("1.0.0", OutputKind.OUTPUT, MAPPER.nullNode(),
                List.of(new ProviderArtifact("artifact-1", "artifact.v1", ReceiptSamples.ATTACHMENT, 2, ReceiptSamples.DIGEST, ReceiptSamples.DIGEST, true)), null);
        StageResult result = new StageResult("1.0.0", "run-task-1-1", stageId, 0, RunState.PASSED, inFile,
                List.of(new Evidence("evidence-1", EvidenceKind.FILE, ReceiptSamples.ATTACHMENT, 1, ReceiptSamples.DIGEST, true, "the second file")), List.of(), List.of(), null, null, null, null,
                new OutputFile(OUTPUT_ATTACHMENT, 3, ReceiptSamples.DIGEST));
        assertDoesNotThrow(() -> validator.stageResult(result));
        JsonNode tree = MAPPER.valueToTree(result);
        assertEquals(3, tree.get("outputFile").get("seq").asInt());
        assertEquals(2, tree.get("output").get("artifacts").get(0).get("seq").asInt());
        assertEquals(1, tree.get("evidence").get(0).get("seq").asInt());
        assertRoundTrip(result, StageResult.class);
        StageResult bound = validator.read("stage-result.v1.schema.json", tree, StageResult.class);
        assertEquals(result, bound);
        // The same result for another file of the attachment is another document: the receipt's digest tells them apart.
        StageResult otherFile = new StageResult("1.0.0", "run-task-1-1", stageId, 0, RunState.PASSED, inFile,
                List.of(new Evidence("evidence-1", EvidenceKind.FILE, ReceiptSamples.ATTACHMENT, 0, ReceiptSamples.DIGEST, true, "the second file")), List.of(), List.of(), null, null, null, null,
                new OutputFile(OUTPUT_ATTACHMENT, 3, ReceiptSamples.DIGEST));
        assertFalse(digests.of(result).equals(digests.of(otherFile)), "file 1 and file 0 of one attachment are not the same pointer");
        // A server-recorded check names the file it was made on.
        StageResult recorded = ReceiptSamples.recordedResult(stageId)
                .withArtifactChecks(List.of(new ArtifactCheckEntry(CheckSource.ARTIFACT, "artifact-1", ReceiptSamples.ATTACHMENT, 2, ArtifactCheck.SHA256_MATCHED),
                        new ArtifactCheckEntry(CheckSource.EVIDENCE, "artifact-1", ReceiptSamples.ATTACHMENT, 1, ArtifactCheck.NOT_CHECKED)));
        JsonNode checks = MAPPER.valueToTree(recorded).get("artifactChecks");
        assertEquals(2, checks.get(0).get("seq").asInt());
        assertEquals(1, checks.get(1).get("seq").asInt());
        assertRoundTrip(recorded, StageResult.class);
        // The first file is the default: its number is not written, and a document without it is read as file 0.
        StageResult firstFiles = ReceiptSamples.recordedResult(stageId);
        JsonNode firstChecks = MAPPER.valueToTree(firstFiles).get("artifactChecks");
        assertFalse(firstChecks.get(0).has("seq") || firstChecks.get(1).has("seq"), firstChecks.toString());
        assertFalse(MAPPER.valueToTree(firstFiles).get("evidence").get(0).has("seq"));
        assertEquals(0, MAPPER.readValue(MAPPER.writeValueAsString(firstFiles), StageResult.class).artifactChecks().get(0).seq());
    }

    @Test
    void anAdapterErrorWritesTheOutputAndTheDetailsAsExplicitNulls() {
        RecordedError error = new RecordedError(WorkflowCode.MCP_UNAVAILABLE, "the adapter is down", MAPPER.nullNode());
        ProviderResult adapter = new ProviderResult("1.0.0", OutputKind.ADAPTER_ERROR, MAPPER.nullNode(), List.of(), error);
        StageResult result = new StageResult("1.0.0", "run-task-1-1", stageId, 0, RunState.BLOCKED, adapter, List.of(), List.of(), List.of("adapter"), error, null, null, null, null);
        assertDoesNotThrow(() -> validator.stageResult(result));
        JsonNode tree = MAPPER.valueToTree(result);
        assertEquals("adapter-error", tree.get("output").get("kind").asString());
        assertTrue(tree.get("output").get("output").isNull());
        assertTrue(tree.get("error").has("details") && tree.get("error").get("details").isNull());
        assertEquals("MCP_UNAVAILABLE", tree.get("error").get("code").asString());
        assertRoundTrip(result, StageResult.class);
    }

    @Test
    void theDetailsOfAnErrorAndTheOutputOfAProviderComeBackAsTheyWentIn() {
        String text = """
                {"schemaVersion":"1.0.0","runId":"run-task-1-1","stageId":"stage-01","expectedRevision":2,"state":"FAILED",
                "output":{"schemaVersion":"1.0.0","kind":"output","output":{"n":1,"ratio":1.50,"big":12345678901234567890,"list":[1,2,{"a":null}],"text":"x"},"artifacts":[],
                          "error":{"code":"GATE_FAILED","message":"no","details":{"count":3,"nested":{"flag":true,"none":null}}}},
                "evidence":[],"findings":[],"blockers":["blocked"],"error":{"code":"GATE_FAILED","message":"no","details":{"count":3,"nested":{"flag":true,"none":null}}}}""";
        StageResult read = validator.read("stage-result.v1.schema.json", MAPPER.readTree(text), StageResult.class);
        assertEquals(CanonicalJson.of(MAPPER.readTree(text)), CanonicalJson.of(MAPPER.valueToTree(read)));
        assertEquals(CanonicalJson.digest(MAPPER.readTree(text)), digests.of(read));
        assertEquals(3, read.error().details().get("count").asInt());
        assertEquals(WorkflowCode.GATE_FAILED, read.output().error().code());
        assertTrue(read.output().hasOutput());
    }

    @Test
    void callsNotObservedIsAbsentBeforeFinalizeNullWhenUnreadAndAListOtherwise() throws IOException {
        SkillDeclaration.Recorded declared = started.skillDeclaration();
        assertNull(declared.callsNotObserved());
        WorkflowReceipt unread = started.withSkillDeclaration(declared.withCallsNotObserved(CallsNotObserved.UNREAD));
        WorkflowReceipt none = started.withSkillDeclaration(declared.withCallsNotObserved(new CallsNotObserved(List.of())));
        WorkflowReceipt some = started.withSkillDeclaration(declared.withCallsNotObserved(new CallsNotObserved(List.of("plugin:minimal-implementation"))));
        for (WorkflowReceipt receipt : List.of(started, unread, none, some)) {
            assertDoesNotThrow(() -> validator.workflowReceipt(receipt));
            assertRoundTrip(receipt, WorkflowReceipt.class);
        }
        assertFalse(MAPPER.valueToTree(started).get("skillDeclaration").has("callsNotObserved"));
        assertTrue(MAPPER.valueToTree(unread).get("skillDeclaration").get("callsNotObserved").isNull());
        assertEquals(0, MAPPER.valueToTree(none).get("skillDeclaration").get("callsNotObserved").size());
        assertEquals("plugin:minimal-implementation", MAPPER.valueToTree(some).get("skillDeclaration").get("callsNotObserved").get(0).asString());
        WorkflowReceipt readUnread = MAPPER.readValue(MAPPER.writeValueAsString(unread), WorkflowReceipt.class);
        assertSame(CallsNotObserved.UNREAD.skills(), readUnread.skillDeclaration().callsNotObserved().skills());
        assertNull(MAPPER.readValue(MAPPER.writeValueAsString(started), WorkflowReceipt.class).skillDeclaration().callsNotObserved());
    }

    @Test
    void aReceiptTextOfTheEarlierTreeWriterReadsAndWritesBackToTheSameCanonicalFormAndDigest() {
        // The earlier writer kept the keys in the order the caller sent them and appended the ones the server added: a different order of the same document.
        JsonNode tree = MAPPER.valueToTree(withResult(ReceiptSamples.recordedResult(stageId)));
        JsonNode result = tree.get("stageResults").get(0);
        String reordered = "{\"runId\":\"" + tree.get("runId").asString() + "\",\"schemaVersion\":\"1.0.0\",\"revision\":1,\"state\":\"RUNNING\",\"plan\":" + tree.get("plan") + ",\"stageResults\":[{\"output\":"
                + result.get("output") + ",\"stageId\":\"" + stageId + "\",\"runId\":\"run-task-1-1\",\"schemaVersion\":\"1.0.0\",\"expectedRevision\":0,\"state\":\"PASSED\",\"evidence\":" + result.get("evidence")
                + ",\"findings\":[\"a finding\"],\"blockers\":[],\"error\":null,\"recordedBy\":" + result.get("recordedBy") + ",\"artifactChecks\":" + result.get("artifactChecks") + ",\"executionContext\":"
                + result.get("executionContext") + "}],\"blockers\":[],\"unresolved\":[],\"error\":null,\"unverifiedClaims\":" + tree.get("unverifiedClaims") + ",\"skillDeclaration\":" + tree.get("skillDeclaration") + "}";
        WorkflowReceipt read = MAPPER.readValue(reordered, WorkflowReceipt.class);
        assertEquals(CanonicalJson.of(MAPPER.readTree(reordered)), CanonicalJson.of(MAPPER.valueToTree(read)));
        assertEquals(CanonicalJson.digest(MAPPER.readTree(reordered)), digests.of(read));
        assertEquals(CanonicalJson.digest(tree), digests.of(read), "the same document, whatever the order of its keys");
    }

    @ParameterizedTest
    @EnumSource(value = RunState.class, names = {"NEEDS_INPUT", "NEEDS_APPROVAL", "NEEDS_REDESIGN", "FAILED", "BLOCKED"})
    void aStageResultOfEachStateThatIsNotAPassPassesItsSchema(RunState state) {
        StageResult result = ReceiptSamples.callerResult(stageId, state);
        assertDoesNotThrow(() -> validator.stageResult(result));
        assertEquals(state.name(), MAPPER.valueToTree(result).get("state").asString());
        assertRoundTrip(result, StageResult.class);
    }

    @Test
    void theSummaryOfEachShapeOfReceiptPassesItsSchemaAndShowsWhatThereIsToShow() {
        SkillDeclaration.Recorded declared = started.skillDeclaration();
        WorkflowReceipt failed = withResult(ReceiptSamples.recordedResult(stageId)).withState(RunState.FAILED).withError(new RecordedError(WorkflowCode.GATE_FAILED, "no", MAPPER.nullNode())).withOpen(List.of("b"), List.of("c"));
        List<WorkflowReceipt> receipts = List.of(started, withResult(ReceiptSamples.recordedResult(stageId)), failed, started.withSkillDeclaration(declared.withCallsNotObserved(CallsNotObserved.UNREAD)),
                started.withSkillDeclaration(declared.withCallsNotObserved(new CallsNotObserved(List.of()))), started.withSkillDeclaration(declared.withCallsNotObserved(new CallsNotObserved(List.of("x")))));
        for (WorkflowReceipt receipt : receipts) {
            WorkflowStatusSummary summary = WorkflowStatusSummary.of(receipt, digests.of(receipt));
            assertDoesNotThrow(() -> validator.assertValid("workflow-status-summary.v1.schema.json", summary));
            JsonNode tree = MAPPER.valueToTree(summary);
            assertEquals(digests.of(receipt), tree.get("receiptDigest").asString());
            assertEquals(receipt.stageResults().size(), tree.get("completedStageCount").asInt());
            assertEquals(receipt.plan().stages().size(), tree.get("totalStageCount").asInt());
            assertTrue(tree.has("errorCode") && tree.has("currentStageId") && tree.has("nextStageId") && tree.has("lastRecordedStageId"), tree.toString());
            assertEquals(receipt.error() == null, tree.get("errorCode").isNull());
        }
        JsonNode first = MAPPER.valueToTree(WorkflowStatusSummary.of(started, digests.of(started)));
        assertTrue(first.get("lastRecordedStageId").isNull());
        assertFalse(first.has("declaredSkillsNotObserved"), "finalize has not run");
        assertFalse(MAPPER.valueToTree(WorkflowStatusSummary.of(receipts.get(4), "sha256:" + "0".repeat(64))).has("declaredSkillsNotObserved"), "an empty list shows nothing");
        assertTrue(MAPPER.valueToTree(WorkflowStatusSummary.of(receipts.get(3), "sha256:" + "0".repeat(64))).get("declaredSkillsNotObserved").isNull(), "an unread record is shown as null");
        assertEquals("x", MAPPER.valueToTree(WorkflowStatusSummary.of(receipts.get(5), "sha256:" + "0".repeat(64))).get("declaredSkillsNotObserved").get(0).asString());
        assertEquals("GATE_FAILED", MAPPER.valueToTree(WorkflowStatusSummary.of(failed, "sha256:" + "0".repeat(64))).get("errorCode").asString());
    }

    @Test
    void theWordsOfTheReceiptAreTheSchemasOwn() throws IOException {
        Set<String> receiptKeys = schemaProperties("workflow-receipt.v1.schema.json");
        assertTrue(receiptKeys.remove("pause"), "the schema lists pause, which an unpaused receipt omits");
        assertEquals(receiptKeys, names(MAPPER.valueToTree(started)));
        Set<String> summaryKeys = schemaProperties("workflow-status-summary.v1.schema.json");
        summaryKeys.remove("declaredSkillsNotObserved");
        assertTrue(summaryKeys.remove("pause"), "the schema lists pause, which an unpaused summary omits");
        assertEquals(summaryKeys, names(MAPPER.valueToTree(WorkflowStatusSummary.of(started, "sha256:" + "0".repeat(64)))), "the optional key is written only when there is something to show");
        Set<String> evidenceKeys = schemaProperties("stage-result.v1.schema.json", "$defs", "evidence");
        Evidence item = ReceiptSamples.callerResult(stageId, RunState.PASSED).evidence().get(0);
        // The file number is written only when it is not the default 0, so an item of the first file has every key of the schema but seq, and an item of another file has them all.
        Set<String> withoutSeq = new TreeSet<>(evidenceKeys);
        assertTrue(withoutSeq.remove("seq"), "the schema lists the file number of an evidence item");
        assertEquals(withoutSeq, names(MAPPER.valueToTree(item)), "an evidence item always names its attachment and its digest, and never a locator");
        Evidence second = new Evidence(item.artifactId(), item.kind(), item.attachmentId(), 1, item.digest(), item.verified(), item.note());
        assertEquals(evidenceKeys, names(MAPPER.valueToTree(second)), "an evidence item of file 1 writes its number");
        assertTrue(evidenceKeys.containsAll(Set.of("attachmentId", "digest")) && !evidenceKeys.contains("locator"), evidenceKeys.toString());
        Set<String> artifactKeys = schemaProperties("stage-result.v1.schema.json", "$defs", "providerArtifact");
        ProviderArtifact artifact = ReceiptSamples.callerResult(stageId, RunState.PASSED).output().artifacts().get(0);
        Set<String> artifactWithoutSeq = new TreeSet<>(artifactKeys);
        assertTrue(artifactWithoutSeq.remove("seq"), "the schema lists the file number of an artifact");
        assertEquals(artifactWithoutSeq, names(MAPPER.valueToTree(artifact)));
        assertEquals(artifactKeys, names(MAPPER.valueToTree(new ProviderArtifact(artifact.artifactId(), artifact.schemaId(), artifact.attachmentId(), 1, artifact.digest(), artifact.targetDigest(), artifact.verified()))));
        assertEquals(schemaProperties("stage-result.v1.schema.json", "$defs", "providerResult"), names(MAPPER.valueToTree(ReceiptSamples.callerResult(stageId, RunState.PASSED).output())));
        assertEquals(Set.of("code", "message", "details"), names(MAPPER.valueToTree(new RecordedError(WorkflowCode.GATE_FAILED, "m", MAPPER.nullNode()))));
    }

    @Test
    void aKeyTheRecordDoesNotListAndAKindOrAStateTheSchemaDoesNotNameAreRefused() {
        ObjectNode extra = MAPPER.valueToTree(ReceiptSamples.callerResult(stageId, RunState.PASSED));
        extra.put("surprise", 1);
        assertEquals(WorkflowCode.INVALID_INPUT, assertThrows(WorkflowFailure.class, () -> validator.read("stage-result.v1.schema.json", extra, StageResult.class)).code());
        WorkflowFailure unbound = assertThrows(WorkflowFailure.class, () -> validator.bind("stage-result.v1.schema.json", extra, StageResult.class));
        assertEquals(WorkflowCode.INVALID_INPUT, unbound.code());
        assertNotNull(unbound.getCause(), "the binding failure is kept as the cause");
        ObjectNode badKind = MAPPER.valueToTree(ReceiptSamples.callerResult(stageId, RunState.PASSED));
        ((ObjectNode) badKind.get("evidence").get(0)).put("kind", "hearsay");
        assertEquals(WorkflowCode.INVALID_INPUT, assertThrows(WorkflowFailure.class, () -> validator.read("stage-result.v1.schema.json", badKind, StageResult.class)).code());
        ObjectNode badState = MAPPER.valueToTree(ReceiptSamples.callerResult(stageId, RunState.PASSED));
        badState.put("state", "RUNNING");
        assertEquals(WorkflowCode.INVALID_INPUT, assertThrows(WorkflowFailure.class, () -> validator.read("stage-result.v1.schema.json", badState, StageResult.class)).code(), "a stage result cannot carry the run's own state");
        ObjectNode serverOwnedWrong = MAPPER.valueToTree(ReceiptSamples.callerResult(stageId, RunState.PASSED));
        serverOwnedWrong.putArray("artifactChecks").addObject().put("source", "SOMEWHERE");
        assertEquals(WorkflowCode.INVALID_INPUT, assertThrows(WorkflowFailure.class, () -> validator.read("stage-result.v1.schema.json", serverOwnedWrong, StageResult.class)).code());
    }

    @Test
    void theEvidenceKindIsWrittenInTheSchemasForm() {
        for (EvidenceKind kind : EvidenceKind.values()) {
            assertEquals(kind.wire(), MAPPER.valueToTree(kind).asString());
            assertEquals(kind, MAPPER.readValue("\"" + kind.wire() + "\"", EvidenceKind.class));
        }
        assertEquals("user-input", MAPPER.valueToTree(EvidenceKind.USER_INPUT).asString());
    }
}
