// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import io.github.jaeseongs95.sennard.server.approval.ApprovalSourceKind;
import io.github.jaeseongs95.sennard.server.approval.RootAction;
import io.github.jaeseongs95.sennard.server.contract.ApiResult;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.service.store.AttemptState;
import io.github.jaeseongs95.sennard.service.store.LeaseState;
import io.github.jaeseongs95.sennard.service.store.RootState;
import io.github.jaeseongs95.sennard.service.store.RunState;
import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The documents of a convergence root are records that Jackson writes and reads: the task envelope, the frame, the root, the review, the status and its
 * summary, the requests, and the approval records. The cases, each run against the contract files themselves:
 * <ol>
 * <li>a task envelope written from code passes its schema, writes the optional {@code artifactContracts} only when there are some, and reads and writes back to
 * the same canonical form;</li>
 * <li>a task text of the earlier tree-based writer, in another key order, reads and writes back to the same canonical form and the same digest, and a
 * digest of the record is the digest of the tree it writes;</li>
 * <li>every closed set a record names (roles, complexity) is written with exactly the values the contract lists;</li>
 * <li>a frame passes its schema, and the digests of its parts are the digests of the parts of the tree it writes;</li>
 * <li>a root written from code passes its schema with {@code parentRootId} as an explicit null and no {@code retryRejections} or {@code release};</li>
 * <li>a root with a rejection and a release passes its schema, with each null that must be written and each key that must be left out;</li>
 * <li>a root text of the earlier writer reads and writes back to the same canonical form and digest;</li>
 * <li>a change of a root is a new record and the old one is untouched, and two rejections that differ only in time are the same rejection;</li>
 * <li>a review passes its schema with {@code proposedFrame} as an explicit null, leaves {@code observedReviewer} out until the server sets it, and writes a
 * time given with an offset as the UTC text {@code Instant.toString()} gives;</li>
 * <li>an input the schema accepted that the record cannot hold is the caller's invalid input, with the binding failure as the cause;</li>
 * <li>a status, its summary and the root handle pass their schemas with the nulls the schemas require;</li>
 * <li>a release request is held to the rules of the tool schema: blank, too long, negative, not an integer, not a text, an unknown key, a repeated or too many
 * approval references are refused;</li>
 * <li>the approval scope, the pending request and the decision keep the JSON shape {@code StoreRootApprovalService} and the admin page read and write, and the
 * scope digest is the digest of the same keys as a tree;</li>
 * <li>what a user approval binds leaves out the task ID and nothing else;</li>
 * <li>the failure fingerprint and the write surface digest are the digests of the trees the earlier code built;</li>
 * <li>the claims about a root are written as the receipt schema's claim shape.</li>
 * </ol>
 */
class RootDocumentsTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Path REPO = Path.of(System.getProperty("sennard.repo.root"));
    private static final Instant AT = Instant.parse("2026-11-11T09:00:00.123456789Z");

    private static ContractValidator validator;
    private static DocumentDigests digests;
    private static Validator rules;

    @BeforeAll
    static void wire() throws IOException {
        validator = new ContractValidator(MAPPER, new ContentRoot(REPO));
        digests = new DocumentDigests(MAPPER);
        LocalValidatorFactoryBean factory = new LocalValidatorFactoryBean();
        factory.afterPropertiesSet();
        rules = factory;
    }

    private static <T> void assertRoundTrip(T document, Class<T> type) {
        String text = MAPPER.writeValueAsString(document);
        T read = MAPPER.readValue(text, type);
        assertEquals(document, read);
        assertEquals(CanonicalJson.of(MAPPER.readTree(text)), CanonicalJson.of(MAPPER.valueToTree(read)));
    }

    private static Set<String> names(JsonNode object) {
        Set<String> names = new TreeSet<>();
        object.propertyNames().forEach(names::add);
        return names;
    }

    private static JsonNode schema(String file) throws IOException {
        return MAPPER.readTree(Files.readString(REPO.resolve("claude-plugin/contracts").resolve(file), StandardCharsets.UTF_8));
    }

    private static Set<String> enumValues(JsonNode enumNode) {
        Set<String> values = new TreeSet<>();
        enumNode.forEach(item -> values.add(item.asString()));
        return values;
    }

    // ---- task envelope and frame

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aTaskEnvelopeWrittenFromCodePassesItsSchemaAndWritesTheOptionalContractsOnlyWhenThereAreSome(boolean withContracts) {
        TaskEnvelope task = RootSamples.task("task-1", withContracts);
        assertDoesNotThrow(() -> validator.assertValid("task-envelope.v1.schema.json", task));
        JsonNode tree = MAPPER.valueToTree(task);
        assertEquals(withContracts, tree.has("artifactContracts"));
        assertFalse(withContracts && tree.get("artifactContracts").isNull());
        assertRoundTrip(task, TaskEnvelope.class);
        assertEquals("high", tree.get("riskLevel").asString());
        assertEquals("complex", tree.get("decision").get("complexity").asString());
    }

    @Test
    void aTaskTextOfTheEarlierWriterReadsAndWritesBackToTheSameCanonicalFormAndDigest() {
        String stored = """
                {"orchestration":{"mcpAvailable":true,"requested":true},"decision":{"hasConflicts":false,"complexity":"simple"},
                "authorization":{"approvalRequired":[],"prohibitedActions":[],"allowedActions":[]},"constraints":[],"requiredCapabilities":["minimal-implementation"],
                "workUnits":[{"writeTargets":["src"],"dependencies":[],"objective":"do","id":"work"}],"riskLevel":"medium","acceptanceCriteria":["it works"],
                "scope":{"excluded":[],"included":["src"]},"objective":"do","taskId":"task-9","schemaVersion":"1.0.0"}
                """;
        JsonNode before = MAPPER.readTree(stored);
        assertDoesNotThrow(() -> validator.assertValid("task-envelope.v1.schema.json", before));
        TaskEnvelope read = MAPPER.readValue(stored, TaskEnvelope.class);
        JsonNode after = MAPPER.valueToTree(read);
        assertEquals(CanonicalJson.of(before), CanonicalJson.of(after));
        assertEquals(CanonicalJson.digest(before), digests.of(read));
        assertNull(read.artifactContracts());
        assertEquals(TaskEnvelope.Complexity.SIMPLE, read.decision().complexity());
    }

    @Test
    void everyClosedSetARecordNamesIsWrittenWithExactlyTheValuesTheContractLists() throws IOException {
        JsonNode frame = schema("convergence-frame.v1.schema.json");
        Set<String> control = new TreeSet<>();
        for (ConvergenceFrame.ControlRole role : ConvergenceFrame.ControlRole.values()) {
            control.add(MAPPER.valueToTree(role).asString());
        }
        assertEquals(enumValues(frame.get("$defs").get("controlArtifact").get("properties").get("role").get("enum")), control);
        Set<String> target = new TreeSet<>();
        for (ConvergenceFrame.TargetRole role : ConvergenceFrame.TargetRole.values()) {
            target.add(MAPPER.valueToTree(role).asString());
        }
        assertEquals(enumValues(frame.get("$defs").get("targetArtifact").get("properties").get("role").get("enum")), target);
        JsonNode task = schema("task-envelope.v1.schema.json");
        Set<String> complexity = new TreeSet<>();
        for (TaskEnvelope.Complexity value : TaskEnvelope.Complexity.values()) {
            complexity.add(MAPPER.valueToTree(value).asString());
        }
        assertEquals(enumValues(task.get("properties").get("decision").get("properties").get("complexity").get("enum")), complexity);
        JsonNode review = schema("convergence-review.v1.schema.json");
        Set<String> routes = new TreeSet<>();
        for (ReviewRoute value : ReviewRoute.values()) {
            routes.add(MAPPER.valueToTree(value).asString());
        }
        assertEquals(enumValues(review.get("properties").get("route").get("enum")), routes);
        Set<String> rejections = new TreeSet<>();
        for (RetryRejectionReason value : RetryRejectionReason.values()) {
            rejections.add(MAPPER.valueToTree(value).asString());
        }
        assertEquals(enumValues(schema("convergence-root.v1.schema.json").get("properties").get("retryRejections").get("items").get("properties").get("reason").get("enum")), rejections);
    }

    @Test
    void aFramePassesItsSchemaAndTheDigestsOfItsPartsAreTheDigestsOfThePartsOfTheTreeItWrites() {
        ConvergenceFrame frame = RootSamples.frame("/w");
        assertDoesNotThrow(() -> validator.assertValid("convergence-frame.v1.schema.json", frame));
        assertRoundTrip(frame, ConvergenceFrame.class);
        JsonNode tree = MAPPER.valueToTree(frame);
        TaskEnvelope task = RootSamples.task("task-1", true);
        ConvergenceDigests computed = digests.convergence(task, frame);
        assertEquals(CanonicalJson.digest(tree), computed.frameDigest());
        assertEquals(CanonicalJson.digest(tree.get("workspace")), computed.workspaceDigest());
        assertEquals(CanonicalJson.digest(tree.get("controlArtifacts")), computed.controlDigest());
        assertEquals(CanonicalJson.digest(tree.get("targetArtifacts")), computed.targetDigest());
        assertEquals(CanonicalJson.digest(tree.get("operationalSettings")), computed.operationalDigest());
        assertEquals(CanonicalJson.digest(MAPPER.valueToTree(task)), computed.taskDigest());
        assertEquals("pass-condition", tree.get("controlArtifacts").get(0).get("role").asString());
    }

    @Test
    void aChangeOfTheTaskOrOfAnyFramePartButTheTargetsIsAChangeOfMeaning() {
        TaskEnvelope task = RootSamples.task("task-1", false);
        ConvergenceFrame frame = RootSamples.frame("/w");
        ConvergenceDigests base = digests.convergence(task, frame);
        assertFalse(base.changesMeaningOf(base));
        ConvergenceFrame retargeted = new ConvergenceFrame(frame.schemaVersion(), frame.workspace(), frame.controlArtifacts(),
                List.of(new ConvergenceFrame.TargetArtifact("target-1", ConvergenceFrame.TargetRole.TARGET, "src/a.java", RootSamples.digest("changed"))), frame.operationalSettings());
        assertFalse(digests.convergence(task, retargeted).changesMeaningOf(base), "only the targets differ");
        assertNotEquals(base.targetDigest(), digests.convergence(task, retargeted).targetDigest());
        ConvergenceFrame moved = RootSamples.frame("/elsewhere");
        assertTrue(digests.convergence(task, moved).changesMeaningOf(base), "the workspace differs");
        assertTrue(digests.convergence(RootSamples.task("task-2", false), frame).changesMeaningOf(base), "the task differs");
    }

    // ---- the root

    @Test
    void aRootWrittenFromCodePassesItsSchemaWithAnExplicitNullParentAndNoRejectionsOrRelease() {
        ConvergenceRoot root = RootSamples.root(digests, "root-1", null, AT);
        assertDoesNotThrow(() -> validator.convergenceRoot(root));
        JsonNode tree = MAPPER.valueToTree(root);
        assertTrue(tree.has("parentRootId") && tree.get("parentRootId").isNull(), tree.toString());
        assertFalse(tree.has("retryRejections"));
        assertFalse(tree.has("release"));
        assertEquals("OPEN", tree.get("state").asString());
        assertEquals(AT.toString(), tree.get("createdAt").asString());
        assertRoundTrip(root, ConvergenceRoot.class);
        ConvergenceRoot child = RootSamples.root(digests, "root-2", "root-1", AT);
        assertEquals("root-1", MAPPER.valueToTree(child).get("parentRootId").asString());
        assertDoesNotThrow(() -> validator.convergenceRoot(child));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-11T09:00:00Z", "2026-11-11T09:00:00.120Z", "2026-11-11T09:00:00.123456Z", "2026-11-11T09:00:00.123456789Z"})
    void aRootTimeIsWrittenAsTheTextInstantToStringGives(String text) {
        Instant at = Instant.parse(text);
        JsonNode tree = MAPPER.valueToTree(RootSamples.root(digests, "root-1", null, at));
        assertEquals(at.toString(), tree.get("createdAt").asString());
        assertEquals(at.toString(), tree.get("updatedAt").asString());
    }

    @Test
    void aRootWithARejectionAndAReleaseCarriesEachNullThatMustBeWrittenAndLeavesOutEachKeyThatMustNotBe() {
        ConvergenceRoot opened = RootSamples.root(digests, "root-1", null, AT);
        ConvergenceRoot.RetryRejection withoutActor = new ConvergenceRoot.RetryRejection(1, RetryRejectionReason.STALE_FINGERPRINT, null, null, RootSamples.digest("t"), List.of("a.txt"), AT);
        ConvergenceRoot.RetryRejection withActor = new ConvergenceRoot.RetryRejection(1, RetryRejectionReason.STALE_EVIDENCE, "claude:actor", RootSamples.digest("fp"), RootSamples.digest("t"), List.of(), AT);
        ConvergenceRoot owner = opened.bumped(AT.plusSeconds(1)).withRetryRejection(withoutActor).withRetryRejection(withActor).withState(RootState.ABANDONED).withRelease(
                new ConvergenceRoot.Release(AT.plusSeconds(2), "claude:actor", true, RootState.OPEN, null, null, List.of(), List.of(), "done", null));
        assertDoesNotThrow(() -> validator.convergenceRoot(owner));
        JsonNode tree = MAPPER.valueToTree(owner);
        JsonNode first = tree.get("retryRejections").get(0);
        assertTrue(first.has("expectedFingerprint") && first.get("expectedFingerprint").isNull(), first.toString());
        assertFalse(first.has("actorId"));
        assertEquals("stale-fingerprint", first.get("reason").asString());
        assertEquals("claude:actor", tree.get("retryRejections").get(1).get("actorId").asString());
        JsonNode release = tree.get("release");
        assertTrue(release.has("ownerPresence") && release.get("ownerPresence").isNull(), release.toString());
        assertTrue(release.has("closedRunId") && release.get("closedRunId").isNull());
        assertFalse(release.has("presenceObservedAt"));
        assertEquals("OPEN", release.get("previousState").asString());
        assertRoundTrip(owner, ConvergenceRoot.class);

        ConvergenceRoot other = opened.bumped(AT.plusSeconds(3)).withState(RootState.ABANDONED).withRelease(
                new ConvergenceRoot.Release(AT.plusSeconds(3), null, false, RootState.NEEDS_REVIEW, PresenceState.ENDED, "2026-11-11T09:00:03Z", List.of("codex:owner"), List.of("approval-1"), "moved on", "run-7"));
        assertDoesNotThrow(() -> validator.convergenceRoot(other));
        JsonNode otherRelease = MAPPER.valueToTree(other).get("release");
        assertTrue(otherRelease.has("releasedBy") && otherRelease.get("releasedBy").isNull());
        assertEquals("ended", otherRelease.get("ownerPresence").asString());
        assertEquals("2026-11-11T09:00:03Z", otherRelease.get("presenceObservedAt").asString());
        assertRoundTrip(other, ConvergenceRoot.class);
    }

    @Test
    void aRootTextOfTheEarlierWriterReadsAndWritesBackToTheSameCanonicalFormAndDigest() {
        ConvergenceRoot root = RootSamples.root(digests, "root-7", "root-3", AT);
        JsonNode generated = MAPPER.valueToTree(root);
        // The earlier writer appended what a root gained later after the timestamps, and kept the keys a caller sent in the order it sent them.
        String stored = generated.toString().replaceFirst("\\}$", ",\"retryRejections\":[{\"epoch\":1,\"reason\":\"stale-evidence\",\"expectedFingerprint\":null,"
                + "\"targetDigest\":\"" + RootSamples.digest("t") + "\",\"evidenceRefs\":[\"99999999-9999-4999-8999-999999999999\"],\"rejectedAt\":\"2026-11-11T09:00:01.500Z\"}],"
                + "\"release\":{\"releasedAt\":\"2026-11-11T09:00:02Z\",\"releasedBy\":\"a\",\"releasedByOwner\":true,\"previousState\":\"OPEN\",\"ownerPresence\":null,"
                + "\"ownerActors\":[],\"userApprovalRefs\":[],\"reason\":\"r\",\"closedRunId\":null}}");
        JsonNode before = MAPPER.readTree(stored);
        assertDoesNotThrow(() -> validator.assertValid("convergence-root.v1.schema.json", before));
        ConvergenceRoot read = MAPPER.readValue(stored, ConvergenceRoot.class);
        JsonNode after = MAPPER.valueToTree(read);
        assertEquals(CanonicalJson.of(before), CanonicalJson.of(after));
        assertEquals(CanonicalJson.digest(before), CanonicalJson.digest(after));
        assertEquals(Instant.parse("2026-11-11T09:00:01.500Z"), read.retryRejections().getFirst().rejectedAt());
        assertEquals(RetryRejectionReason.STALE_EVIDENCE, read.retryRejections().getFirst().reason());
        assertEquals("root-3", read.parentRootId());
    }

    @Test
    void aChangeOfARootIsANewRecordAndTwoRejectionsThatDifferOnlyInTimeAreTheSameRejection() {
        ConvergenceRoot root = RootSamples.root(digests, "root-1", null, AT);
        Instant later = AT.plusSeconds(10);
        ConvergenceRoot bumped = root.bumped(later);
        assertEquals(0, root.revision());
        assertEquals(AT, root.updatedAt());
        assertEquals(1, bumped.revision());
        assertEquals(later, bumped.updatedAt());
        assertEquals(AT, bumped.createdAt());
        ConvergenceRoot review = root.withState(RootState.NEEDS_REVIEW);
        assertEquals(RootState.OPEN, root.state());
        assertEquals(RootState.NEEDS_REVIEW, review.state());
        assertSame(root.taskEnvelope(), review.taskEnvelope());

        ConvergenceFrame approved = RootSamples.frame("/w");
        ConvergenceFrame retargeted = new ConvergenceFrame(approved.schemaVersion(), approved.workspace(), approved.controlArtifacts(),
                List.of(new ConvergenceFrame.TargetArtifact("target-1", ConvergenceFrame.TargetRole.TARGET, "src/a.java", RootSamples.digest("new"))), approved.operationalSettings());
        ConvergenceDigests next = digests.convergence(root.taskDigest(), retargeted);
        ConvergenceRoot epoch = review.nextEpoch(retargeted, next);
        assertEquals(2, epoch.currentEpoch());
        assertEquals(RootState.OPEN, epoch.state());
        assertEquals(retargeted, epoch.frame());
        assertEquals(next, epoch.digests());
        assertEquals(1, root.currentEpoch());
        assertEquals(root.taskDigest(), epoch.taskDigest());

        ConvergenceRoot.RetryRejection one = new ConvergenceRoot.RetryRejection(1, RetryRejectionReason.STALE_EVIDENCE, "a", "fp", "t", List.of("e"), AT);
        ConvergenceRoot.RetryRejection again = new ConvergenceRoot.RetryRejection(1, RetryRejectionReason.STALE_EVIDENCE, "a", "fp", "t", List.of("e"), later);
        ConvergenceRoot.RetryRejection other = new ConvergenceRoot.RetryRejection(1, RetryRejectionReason.STALE_FINGERPRINT, "a", "fp", "t", List.of("e"), AT);
        assertEquals(one.at(Instant.EPOCH), again.at(Instant.EPOCH));
        assertNotEquals(one.at(Instant.EPOCH), other.at(Instant.EPOCH));
        ConvergenceRoot rejected = root.withRetryRejection(one);
        assertTrue(root.retryRejections().isEmpty());
        assertEquals(List.of(one), rejected.retryRejections());
    }

    // ---- the review

    private static ConvergenceReview review(ConvergenceFrame proposed, Instant at) {
        return new ConvergenceReview("1.0.0", "review-1", "root-1", 4, 1, "claude:reviewer", new ConvergenceReview.FreshContext(true, "fresh.txt"), null, ReviewClassification.SEMANTICS_PRESERVING,
                new ConvergenceReview.Comparability(true, "same checks"), ReviewRoute.RESUME_NEW_EPOCH, proposed, List.of("evidence.txt"), List.of(), at);
    }

    @Test
    void aReviewPassesItsSchemaWithAnExplicitNullProposedFrameAndNoObservedReviewerUntilTheServerSetsIt() {
        ConvergenceReview none = review(null, AT);
        assertDoesNotThrow(() -> validator.assertValid("convergence-review.v1.schema.json", none));
        JsonNode tree = MAPPER.valueToTree(none);
        assertTrue(tree.has("proposedFrame") && tree.get("proposedFrame").isNull(), tree.toString());
        assertFalse(tree.has("observedReviewer"));
        assertEquals("semantics-preserving", tree.get("classification").asString());
        assertEquals("resume-new-epoch", tree.get("route").asString());
        assertRoundTrip(none, ConvergenceReview.class);

        ConvergenceReview observed = review(RootSamples.frame("/w"), AT).withObservedReviewer(
                ConvergenceReview.ObservedReviewer.of(new ObservedCaller("claude:session-x:agent-y", List.of("claude:session-x"), Provenance.HOST_OBSERVED), Independence.VERIFIED));
        assertDoesNotThrow(() -> validator.assertValid("convergence-review.v1.schema.json", observed));
        JsonNode seen = MAPPER.valueToTree(observed).get("observedReviewer");
        assertEquals(Set.of("actor", "parentChain", "provenance", "independence"), names(seen));
        assertEquals("HOST_OBSERVED", seen.get("provenance").asString());
        assertEquals("VERIFIED", seen.get("independence").asString());
        assertRoundTrip(observed, ConvergenceReview.class);
    }

    @Test
    void aReviewTimeGivenWithAnOffsetIsTheSameInstantAndIsWrittenInUtc() {
        String text = MAPPER.writeValueAsString(review(null, AT)).replace(AT.toString(), "2026-11-11T18:00:00+09:00");
        JsonNode given = MAPPER.readTree(text);
        assertDoesNotThrow(() -> validator.assertValid("convergence-review.v1.schema.json", given));
        ConvergenceReview read = MAPPER.readValue(text, ConvergenceReview.class);
        assertEquals(Instant.parse("2026-11-11T09:00:00Z"), read.reviewedAt());
        assertEquals("2026-11-11T09:00:00Z", MAPPER.valueToTree(read).get("reviewedAt").asString());
    }

    @Test
    void anInputTheSchemaAcceptedThatTheRecordCannotHoldIsTheCallersInvalidInputWithTheBindingFailureAsTheCause() {
        JsonNode unknownRoute = MAPPER.readTree(MAPPER.writeValueAsString(review(null, AT)).replace("resume-new-epoch", "no-such-route"));
        WorkflowFailure failure = assertThrows(WorkflowFailure.class, () -> validator.bind("convergence-review.v1.schema.json", unknownRoute, ConvergenceReview.class));
        assertEquals(WorkflowCode.INVALID_INPUT, failure.code());
        assertTrue(failure.getCause() instanceof JacksonException, String.valueOf(failure.getCause()));
        JsonNode notAnObject = MAPPER.readTree("{\"schemaVersion\":\"1.0.0\"}");
        assertEquals(WorkflowCode.INVALID_INPUT, assertThrows(WorkflowFailure.class, () -> validator.read("convergence-review.v1.schema.json", notAnObject, ConvergenceReview.class)).code());
    }

    @Test
    void anOpenRequestThatCitesNoApprovalCitesAnEmptyListAndOneThatBreaksTheSchemaIsRefusedBeforeBinding() {
        OpenConvergenceRootRequest request = new OpenConvergenceRootRequest("1.0.0", null, RootSamples.task("task-1", false), RootSamples.frame("/w"), null);
        assertEquals(List.of(), request.userApprovalRefs());
        JsonNode tree = MAPPER.valueToTree(request);
        assertTrue(tree.has("parentRootId") && tree.get("parentRootId").isNull());
        assertDoesNotThrow(() -> validator.openConvergenceRootRequest(request));
        JsonNode sent = tree.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) sent).remove("userApprovalRefs");
        OpenConvergenceRootRequest read = validator.read("open-convergence-root-request.v1.schema.json", sent, OpenConvergenceRootRequest.class);
        assertEquals(request, read);
        ((tools.jackson.databind.node.ObjectNode) sent).remove("frame");
        assertEquals(WorkflowCode.INVALID_INPUT, assertThrows(WorkflowFailure.class, () -> validator.read("open-convergence-root-request.v1.schema.json", sent, OpenConvergenceRootRequest.class)).code());
    }

    @Test
    void aPlanRequestIsReadAsTheWrapperItIsOrAsTheBareTaskEnvelopeItStandsFor() {
        TaskEnvelope task = RootSamples.task("task-1", false);
        String wrapped = "{\"schemaVersion\":\"1.0.0\",\"taskEnvelope\":" + MAPPER.writeValueAsString(task) + ",\"evaluationAuditPurpose\":\"quality-or-release\",\"requiredAuditPhase\":\"pre-deploy\"}";
        JsonNode tree = MAPPER.readTree(wrapped);
        assertDoesNotThrow(() -> validator.planWorkflowRequest(tree));
        PlanWorkflowRequest request = MAPPER.readValue(wrapped, PlanWorkflowRequest.class);
        assertEquals(task, request.taskEnvelope());
        assertEquals(EvaluationAuditPurpose.QUALITY_OR_RELEASE, request.evaluationAuditPurpose());
        assertEquals(AuditPhase.PRE_DEPLOY, request.requiredAuditPhase());
        JsonNode bare = MAPPER.valueToTree(task);
        assertDoesNotThrow(() -> validator.planWorkflowRequest(bare));
        assertDoesNotThrow(() -> validator.planWorkflowRequest(task));
        PlanWorkflowRequest bareRequest = PlanWorkflowRequest.bare(MAPPER.treeToValue(bare, TaskEnvelope.class));
        assertEquals(task, bareRequest.taskEnvelope());
        assertNull(bareRequest.evaluationAuditPurpose());
        assertNull(bareRequest.requiredAuditPhase());
        assertEquals(Set.of("taskEnvelope"), names(MAPPER.valueToTree(bareRequest)));
    }

    // ---- the status and its summary

    private static ConvergenceStatus status(ConvergenceRoot root, ApiResult.ErrorBody gateError, List<UnverifiedClaimEntry> claims) {
        String digest = RootSamples.digest("attempt");
        AttemptLease consumed = new AttemptLease("1.0.0", "lease-1", root.rootId(), 1, 1, 1, digest, digest, digest, digest, digest, digest, digest, digest, "token", "claude:actor", List.of(),
                List.of("src/a.java"), AT, AT.plusSeconds(300), LeaseState.CONSUMED);
        AttemptLease issued = new AttemptLease("1.0.0", "lease-2", root.rootId(), 2, 1, 2, digest, digest, digest, digest, digest, digest, digest, digest, "token", null, List.of(),
                List.of("src/a.java"), AT, AT.plusSeconds(300), LeaseState.ISSUED);
        AttemptOutcome outcome = new AttemptOutcome("1.0.0", "outcome-1", root.rootId(), 2, "lease-1", 1, 1, "run-1", AttemptState.FAILED, digest, digest, List.of(digest), AT);
        return new ConvergenceStatus("1.0.0", root, 1, 3, 2, 1, 2, List.of(), List.of(consumed, issued), List.of(outcome), List.of(review(null, AT).withObservedReviewer(
                ConvergenceReview.ObservedReviewer.of(new ObservedCaller("a", List.of(), Provenance.UNAVAILABLE), Independence.UNOBSERVED))), List.of("run-1"), gateError, claims);
    }

    @Test
    void aStatusItsSummaryAndTheRootHandlePassTheirSchemasWithTheNullsTheyRequire() {
        ConvergenceRoot root = RootSamples.root(digests, "root-1", "root-0", AT).withState(RootState.NEEDS_REVIEW);
        ApiResult.ErrorBody gate = new ApiResult.ErrorBody("ATTEMPT_BUDGET_EXHAUSTED", "A review is required.", "workflow.service.independentReviewRequiredBeforeAnother", java.util.Map.of(),
                java.util.Map.of("rootId", "root-1", "epoch", 1));
        List<UnverifiedClaimEntry> claims = UnverifiedClaims.forConvergence(root, List.of(review(null, AT).withObservedReviewer(
                ConvergenceReview.ObservedReviewer.of(new ObservedCaller("a", List.of(), Provenance.UNAVAILABLE), Independence.UNOBSERVED))), List.of());
        assertEquals(List.of(UnverifiedClaim.USER_APPROVAL, UnverifiedClaim.REVIEWER_INDEPENDENCE, UnverifiedClaim.FRESH_CONTEXT), claims.stream().map(UnverifiedClaimEntry::claim).toList());

        ConvergenceStatus status = status(root, gate, claims);
        assertDoesNotThrow(() -> validator.convergenceStatus(status));
        JsonNode tree = MAPPER.valueToTree(status);
        assertTrue(tree.get("unverifiedClaims").get(0).has("stageId") && tree.get("unverifiedClaims").get(0).get("stageId").isNull());
        assertFalse(tree.get("unverifiedClaims").get(0).has("artifactId"));
        assertEquals("ATTEMPT_BUDGET_EXHAUSTED", tree.get("gateError").get("code").asString());
        assertRoundTrip(status, ConvergenceStatus.class);

        ConvergenceStatus open = status(RootSamples.root(digests, "root-1", null, AT), null, List.of());
        assertDoesNotThrow(() -> validator.convergenceStatus(open));
        assertTrue(MAPPER.valueToTree(open).has("gateError") && MAPPER.valueToTree(open).get("gateError").isNull());

        ConvergenceStatusSummary summary = ConvergenceStatusSummary.of(status);
        assertDoesNotThrow(() -> validator.assertValid("convergence-status-summary.v1.schema.json", summary));
        JsonNode summaryTree = MAPPER.valueToTree(summary);
        assertEquals("lease-2", summaryTree.get("issuedLeaseId").asString());
        assertEquals("run-1", summaryTree.get("latestWorkflowRunId").asString());
        assertEquals("FAILED", summaryTree.get("latestOutcomeState").asString());
        assertEquals("ATTEMPT_BUDGET_EXHAUSTED", summaryTree.get("gateErrorCode").asString());
        assertEquals(2, summaryTree.get("leaseCount").asInt());
        assertEquals(3, summaryTree.get("unverifiedClaimCount").asInt());
        assertEquals("NEEDS_REVIEW", summaryTree.get("root").get("state").asString());

        ConvergenceStatusSummary quiet = ConvergenceStatusSummary.of(new ConvergenceStatus("1.0.0", RootSamples.root(digests, "root-1", null, AT), 1, 3, 2, 0, 3, List.of(), List.of(), List.of(),
                List.of(), List.of(), null, List.of()));
        assertDoesNotThrow(() -> validator.assertValid("convergence-status-summary.v1.schema.json", quiet));
        JsonNode quietTree = MAPPER.valueToTree(quiet);
        for (String key : List.of("issuedLeaseId", "latestWorkflowRunId", "latestOutcomeId", "latestOutcomeState", "latestOutcomeReceiptDigest", "gateErrorCode")) {
            assertTrue(quietTree.has(key) && quietTree.get(key).isNull(), key);
        }

        ConvergenceRootHandle handle = ConvergenceRootHandle.of(root);
        assertDoesNotThrow(() -> validator.assertValid("convergence-root-handle.v1.schema.json", handle));
        assertEquals(root.taskDigest(), MAPPER.valueToTree(handle).get("taskDigest").asString());
        assertFalse(MAPPER.valueToTree(handle).has("taskEnvelope"));
    }

    @Test
    void theClaimsAboutARootNameTheUserApprovalOfAReplacementTheReviewersAndTheEvidenceOfARetry() {
        ConvergenceRoot plain = RootSamples.root(digests, "root-1", null, AT);
        assertEquals(List.of(), UnverifiedClaims.forConvergence(plain, List.of(), List.of()));
        String digest = RootSamples.digest("attempt");
        AttemptLease retry = new AttemptLease("1.0.0", "lease-1", "root-1", 1, 1, 2, digest, digest, digest, digest, digest, digest, digest, digest, "token", "a", List.of(digest), List.of("x"), AT, AT, LeaseState.CONSUMED);
        assertEquals(List.of(new UnverifiedClaimEntry(null, UnverifiedClaim.EVIDENCE_RELEVANCE, null)), UnverifiedClaims.forConvergence(plain, List.of(), List.of(retry)));
        ConvergenceReview verified = review(null, AT).withObservedReviewer(ConvergenceReview.ObservedReviewer.of(new ObservedCaller("b", List.of("a"), Provenance.HOST_OBSERVED), Independence.VERIFIED));
        assertEquals(List.of(UnverifiedClaim.FRESH_CONTEXT), UnverifiedClaims.forConvergence(plain, List.of(verified), List.of()).stream().map(UnverifiedClaimEntry::claim).toList());
        assertEquals(List.of(UnverifiedClaim.REVIEWER_INDEPENDENCE, UnverifiedClaim.FRESH_CONTEXT), UnverifiedClaims.forConvergence(plain, List.of(review(null, AT)), List.of()).stream().map(UnverifiedClaimEntry::claim).toList(),
                "a review without an observed reviewer proves no independence");
    }

    // ---- the release request

    private ReleaseRequest bound(String json) {
        return ReleaseRequest.reader(MAPPER).readValue(json);
    }

    @Test
    void aReleaseRequestThatKeepsToTheToolSchemaIsBoundAndItsReasonIsStripped() {
        ReleaseRequest request = bound("{\"rootId\":\"root-1\",\"expectedRevision\":3,\"reason\":\"  moved on \"}");
        assertTrue(rules.validate(request).isEmpty());
        assertEquals(List.of(), request.userApprovalRefs());
        ReleaseRequest stripped = request.stripped();
        assertEquals("moved on", stripped.reason());
        JsonNode tree = MAPPER.valueToTree(stripped);
        assertEquals(Set.of("rootId", "expectedRevision", "reason", "userApprovalRefs"), names(tree));
        assertEquals(3, tree.get("expectedRevision").asInt());
        ReleaseRequest cited = bound("{\"rootId\":\"root-1\",\"expectedRevision\":0,\"reason\":\"r\",\"userApprovalRefs\":[\"approval-1\",\"approval-2\"]}");
        assertTrue(rules.validate(cited).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"rootId\":\"\",\"expectedRevision\":1,\"reason\":\"r\"}",
            "{\"rootId\":\"   \",\"expectedRevision\":1,\"reason\":\"r\"}",
            "{\"expectedRevision\":1,\"reason\":\"r\"}",
            "{\"rootId\":\"root-1\",\"reason\":\"r\"}",
            "{\"rootId\":\"root-1\",\"expectedRevision\":-1,\"reason\":\"r\"}",
            "{\"rootId\":\"root-1\",\"expectedRevision\":1,\"reason\":\"\"}",
            "{\"rootId\":\"root-1\",\"expectedRevision\":1,\"reason\":\" \"}",
            "{\"rootId\":\"root-1\",\"expectedRevision\":1}",
            "{\"rootId\":\"root-1\",\"expectedRevision\":1,\"reason\":\"r\",\"userApprovalRefs\":[\"a\",\"a\"]}",
            "{\"rootId\":\"root-1\",\"expectedRevision\":1,\"reason\":\"r\",\"userApprovalRefs\":[\"\"]}",
            "{\"rootId\":\"root-1\",\"expectedRevision\":1,\"reason\":\"r\",\"userApprovalRefs\":[null]}"})
    void aReleaseRequestThatBreaksARuleOfTheToolSchemaIsRefusedByTheConstraints(String json) {
        ReleaseRequest request = bound(json);
        assertFalse(rules.validate(request).isEmpty(), json);
    }

    @Test
    void aReleaseReasonOrApprovalListPastItsLimitIsRefused() {
        assertTrue(rules.validate(bound("{\"rootId\":\"r\",\"expectedRevision\":1,\"reason\":\"" + "x".repeat(2000) + "\"}")).isEmpty());
        assertFalse(rules.validate(bound("{\"rootId\":\"r\",\"expectedRevision\":1,\"reason\":\"" + "x".repeat(2001) + "\"}")).isEmpty());
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            many.add("\"a" + i + "\"");
        }
        String refs = "[" + String.join(",", many.subList(0, 32)) + "]";
        assertTrue(rules.validate(bound("{\"rootId\":\"r\",\"expectedRevision\":1,\"reason\":\"r\",\"userApprovalRefs\":" + refs + "}")).isEmpty());
        assertFalse(rules.validate(bound("{\"rootId\":\"r\",\"expectedRevision\":1,\"reason\":\"r\",\"userApprovalRefs\":[" + String.join(",", many) + "]}")).isEmpty());
        assertFalse(rules.validate(bound("{\"rootId\":\"r\",\"expectedRevision\":1,\"reason\":\"r\",\"userApprovalRefs\":[\"" + "y".repeat(2001) + "\"]}")).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"rootId\":\"root-1\",\"expectedRevision\":1.5,\"reason\":\"r\"}",
            "{\"rootId\":\"root-1\",\"expectedRevision\":true,\"reason\":\"r\"}",
            "{\"rootId\":\"root-1\",\"expectedRevision\":{},\"reason\":\"r\"}",
            "{\"rootId\":[],\"expectedRevision\":1,\"reason\":\"r\"}",
            "{\"rootId\":\"root-1\",\"expectedRevision\":1,\"reason\":\"r\",\"surprise\":1}",
            "{\"rootId\":\"root-1\",\"expectedRevision\":1,\"reason\":\"r\",\"userApprovalRefs\":\"approval-1\"}"})
    void aReleaseRequestOfTheWrongTypeOrWithAKeyTheToolDoesNotTakeIsRefusedWhenBinding(String json) {
        assertThrows(JacksonException.class, () -> bound(json), json);
    }

    // ---- the approval records

    @Test
    void theApprovalScopeIsDigestedAsTheTreeOfTheSameKeysAndAReleaseWritesItsReplacementDigestAsNull() {
        RootApprovalScope release = new RootApprovalScope(RootAction.RELEASE_ROOT, "root-1", "claude:actor", RootSamples.digest("surface"), null);
        JsonNode tree = MAPPER.valueToTree(release);
        assertEquals(Set.of("action", "rootId", "actorId", "rootDigest", "replacementDigest"), names(tree));
        assertTrue(tree.get("replacementDigest").isNull());
        assertEquals("RELEASE_ROOT", tree.get("action").asString());
        JsonNode earlier = MAPPER.readTree("{\"replacementDigest\":null,\"rootDigest\":\"" + RootSamples.digest("surface") + "\",\"actorId\":\"claude:actor\",\"rootId\":\"root-1\",\"action\":\"RELEASE_ROOT\"}");
        assertEquals(CanonicalJson.digest(earlier), digests.of(release));
        assertRoundTrip(release, RootApprovalScope.class);
        RootApprovalScope replace = new RootApprovalScope(RootAction.REPLACE_ROOT, "root-1", "claude:actor", RootSamples.digest("surface"), RootSamples.digest("replacement"));
        assertNotEquals(digests.of(release), digests.of(replace));
    }

    @Test
    void thePendingRequestKeepsTheShapeTheAdminPageReadsAndLeavesOutTheReplacementOfARelease() {
        RootApprovalScope scope = new RootApprovalScope(RootAction.RELEASE_ROOT, "root-1", "claude:actor", RootSamples.digest("surface"), null);
        String scopeDigest = digests.of(scope);
        ReleaseRequest request = bound("{\"rootId\":\"root-1\",\"expectedRevision\":3,\"reason\":\"r\"}");
        RootApprovalRecords.Pending release = new RootApprovalRecords.Pending(scopeDigest, scope, 3, request, "message", "{\"a\":1}", null, "claude:actor");
        JsonNode tree = MAPPER.valueToTree(release);
        assertEquals(Set.of("scopeDigest", "scope", "rootRevision", "request", "message", "currentScope", "requestedBy"), names(tree));
        assertEquals(Set.of("rootId", "expectedRevision", "reason", "userApprovalRefs"), names(tree.get("request")));
        assertEquals("RELEASE_ROOT", tree.get("scope").get("action").asString());
        assertEquals("{\"a\":1}", tree.get("currentScope").asString());

        OpenConvergenceRootRequest replacement = new OpenConvergenceRootRequest("1.0.0", "root-1", RootSamples.task("task-2", false), RootSamples.frame("/w"), null);
        RootApprovalRecords.Pending replace = new RootApprovalRecords.Pending(scopeDigest, scope, 3, replacement, "message", "{\"a\":1}", "{\"b\":2}", "claude:actor");
        JsonNode replaceTree = MAPPER.valueToTree(replace);
        assertEquals("{\"b\":2}", replaceTree.get("replacementScope").asString());
        assertEquals(Set.of("schemaVersion", "parentRootId", "taskEnvelope", "frame", "userApprovalRefs"), names(replaceTree.get("request")));
        assertEquals(scope, MAPPER.treeToValue(tree.get("scope"), RootApprovalScope.class));
    }

    @Test
    void aDecisionTheStoreServiceWroteReadsAsTheRecordAndItsScopeDigestIsTheDigestOfItsScope() {
        RootApprovalScope scope = new RootApprovalScope(RootAction.REPLACE_ROOT, "root-1", "claude:actor", RootSamples.digest("surface"), RootSamples.digest("replacement"));
        String scopeDigest = digests.of(scope);
        String written = "{\"approvalId\":\"approval-1\",\"scope\":" + MAPPER.writeValueAsString(scope) + ",\"scopeDigest\":\"" + scopeDigest + "\","
                + "\"source\":{\"kind\":\"ADMIN_PAGE\",\"recordHash\":\"sha256:abc\",\"credentialId\":null},\"createdAt\":\"2026-11-11T09:00:00.250Z\",\"revokedAt\":null}";
        RootApprovalRecords.Decision decision = MAPPER.readValue(written, RootApprovalRecords.Decision.class);
        assertEquals(ApprovalSourceKind.ADMIN_PAGE, decision.source().kind());
        assertEquals(Instant.parse("2026-11-11T09:00:00.250Z"), decision.createdAt());
        assertNull(decision.revokedAt());
        assertNull(decision.source().credentialId());
        assertEquals(scopeDigest, digests.of(decision.scope()));
        assertEquals(scope, decision.scope());
    }

    @Test
    void whatAUserApprovalBindsLeavesOutTheTaskIdAndNothingElse() {
        TaskEnvelope task = RootSamples.task("task-1", true);
        ConvergenceFrame frame = RootSamples.frame("/w");
        SemanticSurface surface = SemanticSurface.of(task, frame);
        assertEquals(digests.canonical(SemanticSurface.of(RootSamples.task("task-2", true), frame)), digests.canonical(surface), "a generated task ID is not part of what was approved");
        JsonNode tree = MAPPER.valueToTree(surface);
        assertEquals(Set.of("task", "frame"), names(tree));
        assertFalse(tree.get("task").has("taskId"));
        JsonNode taskTree = MAPPER.valueToTree(task);
        ((tools.jackson.databind.node.ObjectNode) taskTree).remove("taskId");
        assertEquals(CanonicalJson.of(taskTree), CanonicalJson.of(tree.get("task")));
        TaskEnvelope changed = new TaskEnvelope(task.schemaVersion(), "task-1", "Another objective", task.scope(), task.acceptanceCriteria(), task.riskLevel(), task.workUnits(), task.artifactContracts(),
                task.requiredCapabilities(), task.constraints(), task.authorization(), task.decision(), task.orchestration());
        assertNotEquals(digests.of(surface), digests.of(SemanticSurface.of(changed, frame)));
        assertEquals("task-1", task.taskId(), "the envelope the root holds keeps its task ID");
    }

    // ---- digests the earlier code built from trees

    @Test
    void theFailureFingerprintIsTheDigestOfTheTreeTheEarlierCodeBuilt() {
        // The fingerprint reads no plan, so the receipts here hold none.
        WorkflowReceipt receipt = new WorkflowReceipt("1.0.0", "run-1", 1, RunState.BLOCKED, null,
                List.of(ReceiptSamples.callerResult("s0", RunState.PASSED), ReceiptSamples.callerResult("stage-01-x", RunState.PASSED)), List.of("b"), List.of("u", "v"),
                new RecordedError(WorkflowCode.GATE_FAILED, "m", MAPPER.nullNode()), List.of(), null);
        JsonNode earlier = MAPPER.readTree("{\"state\":\"BLOCKED\",\"stage\":\"stage-01-x\",\"error\":\"GATE_FAILED\",\"blockers\":[\"b\"],\"unresolved\":[\"u\",\"v\"]}");
        assertEquals(CanonicalJson.digest(earlier), ConvergenceLogic.failureFingerprint(digests, receipt));
        WorkflowReceipt bare = new WorkflowReceipt("1.0.0", "run-1", 1, RunState.FAILED, null, List.of(), List.of(), List.of(), null, List.of(), null);
        JsonNode earlierBare = MAPPER.readTree("{\"state\":\"FAILED\",\"stage\":null,\"error\":null,\"blockers\":[],\"unresolved\":[]}");
        assertEquals(CanonicalJson.digest(earlierBare), ConvergenceLogic.failureFingerprint(digests, bare));
    }

    @Test
    void theWriteSurfaceDigestIsTheDigestOfTheLocatorAndTheEntriesTheEarlierCodeBuilt() {
        ConvergenceRoot root = RootSamples.root(digests, "root-1", null, AT);
        // scope.included, then each work unit's write targets, then each target artifact's locator
        List<String> entries = new ArrayList<>(List.of("src/a.java", "src/b.java"));
        entries.addAll(List.of("src/a.java", "src/b.java"));
        entries.addAll(List.of("src/a.java", "src/b.java"));
        JsonNode earlier = MAPPER.readTree("{\"locator\":\"/w\",\"entries\":" + MAPPER.writeValueAsString(entries) + "}");
        assertEquals(CanonicalJson.digest(earlier), ConvergenceLogic.surfaceDigest(digests, root));
    }

    @Test
    void theObservedCallerIsWrittenAsTheThreeKeysAStageResultAndAReviewRecord() {
        ObservedCaller caller = new ObservedCaller("claude:session-1:agent-2", List.of("claude:session-1"), Provenance.HOST_OBSERVED);
        JsonNode tree = MAPPER.valueToTree(caller);
        assertEquals(Set.of("actor", "parentChain", "provenance"), names(tree));
        assertEquals("HOST_OBSERVED", tree.get("provenance").asString());
        assertTrue(caller.hostObserved());
        assertFalse(new ObservedCaller("a", List.of(), Provenance.UNAVAILABLE).hostObserved());
        assertRoundTrip(caller, ObservedCaller.class);
    }
}
