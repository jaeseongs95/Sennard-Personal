// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.service.store.AttemptState;
import io.github.jaeseongs95.sennard.service.store.LeaseState;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The three attempt documents (lease, proposal, outcome) are records that Jackson writes and reads. The cases, each run against the contract files
 * themselves:
 * <ol>
 * <li>a lease written from code passes the lease schema, and reading it and writing it again gives the same canonical form;</li>
 * <li>a lease with no verified caller carries {@code actorId} as an explicit null, which the schema requires and a missing key would break;</li>
 * <li>a time is written as the text {@code Instant.toString()} gives, whatever fraction it has, because the stored documents and their digests carry that text;</li>
 * <li>a lease text written by the earlier tree-based writer reads and writes back to the same canonical form and digest;</li>
 * <li>an outcome of each state passes the outcome schema with the null that does not apply written out, and the state the schema does not name is refused;</li>
 * <li>a proposal writes exactly the keys its schema lists, an absent prior failure as an explicit null, and the documents inside it as they were given.</li>
 * </ol>
 */
class AttemptDocumentsTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Path REPO = Path.of(System.getProperty("sennard.repo.root"));
    private static final String DIGEST = "sha256:" + Digests.sha256Hex("attempt");
    private static final String OTHER_DIGEST = "sha256:" + Digests.sha256Hex("other");
    private static final Instant ISSUED = Instant.parse("2026-11-11T09:00:00.123456789Z");

    private static ContractValidator validator;
    /** A plan the server would sign for a direct task, over the repository's real registry. */
    private static WorkflowPlan plan;

    @BeforeAll
    static void wire() throws IOException {
        validator = new ContractValidator(MAPPER, new ContentRoot(REPO));
        PlanSamples.Wiring wiring = PlanSamples.wire();
        plan = wiring.planner().build(PlanSamples.task("task-1", RiskLevel.LOW, List.of("minimal-implementation"), true), wiring.registry().read(), null, PlanSamples.bootstrap("task-1"), null,
                PlanSamples.KEY);
    }

    private static AttemptLease lease(String actorId, Instant issuedAt, LeaseState state) {
        return new AttemptLease("1.0.0", "lease-1", "root-1", 4, 1, 2, DIGEST, DIGEST, DIGEST, DIGEST, OTHER_DIGEST, DIGEST, DIGEST, DIGEST, "token-1", actorId,
                List.of(OTHER_DIGEST), List.of("src/a.java", "src/b.java"), issuedAt, issuedAt.plusSeconds(300), state);
    }

    private static AttemptOutcome outcome(AttemptState state, String receiptDigest, String fingerprint) {
        return new AttemptOutcome("1.0.0", "outcome-1", "root-1", 5, "lease-1", 1, 2, "run-1", state, receiptDigest, fingerprint, List.of("sha256:" + Digests.sha256Hex("evidence")), ISSUED);
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

    private static Set<String> schemaProperties(String file, String... path) throws IOException {
        JsonNode schema = MAPPER.readTree(Files.readString(REPO.resolve("claude-plugin/contracts").resolve(file), StandardCharsets.UTF_8));
        for (String step : path) {
            schema = schema.get(step);
        }
        return names(schema.get("properties"));
    }

    private static Set<String> schemaRequired(String file, String... path) throws IOException {
        JsonNode schema = MAPPER.readTree(Files.readString(REPO.resolve("claude-plugin/contracts").resolve(file), StandardCharsets.UTF_8));
        for (String step : path) {
            schema = schema.get(step);
        }
        Set<String> required = new TreeSet<>();
        schema.get("required").forEach(item -> required.add(item.asString()));
        return required;
    }

    @Test
    void aLeaseWrittenFromCodePassesItsSchemaAndKeepsItsCanonicalFormWhenReadAndWrittenAgain() {
        for (LeaseState state : LeaseState.values()) {
            AttemptLease lease = lease("claude:actor-1", ISSUED, state);
            assertDoesNotThrow(() -> validator.attemptLease(lease), state.name());
            assertRoundTrip(lease, AttemptLease.class);
            assertEquals(state.name(), MAPPER.valueToTree(lease).get("state").asString());
        }
    }

    @Test
    void aLeaseWithNoVerifiedCallerCarriesActorIdAsAnExplicitNull() {
        AttemptLease lease = lease(null, ISSUED, LeaseState.ISSUED);
        JsonNode tree = MAPPER.valueToTree(lease);
        assertTrue(tree.has("actorId") && tree.get("actorId").isNull(), tree.toString());
        assertDoesNotThrow(() -> validator.attemptLease(lease));
        assertRoundTrip(lease, AttemptLease.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-11T09:00:00Z", "2026-11-11T09:00:00.120Z", "2026-11-11T09:00:00.123456Z", "2026-11-11T09:00:00.123456789Z"})
    void aTimeIsWrittenAsTheTextInstantToStringGives(String text) {
        Instant at = Instant.parse(text);
        JsonNode tree = MAPPER.valueToTree(lease("claude:actor-1", at, LeaseState.ISSUED));
        assertEquals(at.toString(), tree.get("issuedAt").asString());
        assertEquals(at.plusSeconds(300).toString(), tree.get("expiresAt").asString());
        JsonNode outcome = MAPPER.valueToTree(new AttemptOutcome("1.0.0", "outcome-1", "root-1", 5, "lease-1", 1, 2, "run-1", AttemptState.PASSED, DIGEST, null, List.of(), at));
        assertEquals(at.toString(), outcome.get("recordedAt").asString());
    }

    @Test
    void aLeaseTextOfTheEarlierWriterReadsAndWritesBackToTheSameCanonicalFormAndDigest() {
        String stored = """
                {"schemaVersion":"1.0.0","leaseId":"lease-7","rootId":"root-9","rootRevision":12,"epoch":2,"ordinal":3,"proposalDigest":"%1$s","frameDigest":"%1$s",
                "workspaceDigest":"%1$s","controlDigest":"%1$s","targetDigest":"%2$s","operationalDigest":"%1$s","taskDigest":"%1$s","outputTargetsDigest":"%1$s",
                "planIntegrityToken":"abc_-123","actorId":null,"priorEvidenceDigests":["%2$s"],"outputTargets":["src/x.java"],"issuedAt":"2026-11-11T09:00:00.500Z",
                "expiresAt":"2026-11-11T09:05:00.500Z","state":"CONSUMED"}
                """.formatted(DIGEST, OTHER_DIGEST);
        JsonNode before = MAPPER.readTree(stored);
        assertDoesNotThrow(() -> validator.assertValid("attempt-lease.v1.schema.json", before));
        AttemptLease read = MAPPER.readValue(stored, AttemptLease.class);
        JsonNode after = MAPPER.valueToTree(read);
        assertEquals(CanonicalJson.of(before), CanonicalJson.of(after));
        assertEquals(CanonicalJson.digest(before), CanonicalJson.digest(after));
        assertEquals(LeaseState.CONSUMED, read.state());
        assertEquals(Instant.parse("2026-11-11T09:00:00.500Z"), read.issuedAt());
    }

    @ParameterizedTest
    @EnumSource(value = AttemptState.class, names = {"PASSED", "FAILED", "ABORTED"})
    void anOutcomeOfEachStatePassesItsSchemaWithTheNullThatDoesNotApplyWrittenOut(AttemptState state) {
        boolean passed = state == AttemptState.PASSED;
        AttemptOutcome outcome = outcome(state, DIGEST, passed ? null : "sha256:" + Digests.sha256Hex("fingerprint"));
        JsonNode tree = MAPPER.valueToTree(outcome);
        assertTrue(tree.has("failureFingerprint"), tree.toString());
        assertEquals(passed, tree.get("failureFingerprint").isNull());
        assertDoesNotThrow(() -> validator.attemptOutcome(outcome));
        assertRoundTrip(outcome, AttemptOutcome.class);
        AttemptOutcome noReceipt = outcome(state, null, null);
        assertTrue(MAPPER.valueToTree(noReceipt).get("receiptDigest").isNull());
        assertDoesNotThrow(() -> validator.attemptOutcome(noReceipt));
    }

    @Test
    void anOutcomeInTheRunningStateIsRefusedBecauseTheSchemaNamesOnlyTheThreeThatEndAnAttempt() {
        assertEquals(WorkflowCode.INVALID_INPUT, assertThrows(WorkflowFailure.class, () -> validator.attemptOutcome(outcome(AttemptState.RUNNING, DIGEST, null))).code());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aProposalWritesExactlyTheKeysItsSchemaListsAndKeepsTheDocumentsInsideAsGiven(boolean withPriorFailure) throws IOException {
        TaskEnvelope task = RootSamples.task("task-1", true);
        ConvergenceFrame frame = RootSamples.frame("/w");
        AttemptProposal.PriorFailure prior = withPriorFailure ? new AttemptProposal.PriorFailure("fp-1", "a hypothesis", "a change", "a discriminator", List.of("22222222-2222-4222-8222-222222222222")) : null;
        AttemptProposal proposal = new AttemptProposal("1.0.0", "root-1", 3, task, frame, plan, List.of("src/a.java"), prior);

        JsonNode tree = MAPPER.valueToTree(proposal);
        assertEquals(schemaProperties("attempt-proposal.v1.schema.json"), names(tree));
        assertEquals(schemaRequired("attempt-proposal.v1.schema.json"), names(tree));
        assertTrue(tree.has("priorFailure"));
        assertEquals(!withPriorFailure, tree.get("priorFailure").isNull());
        if (withPriorFailure) {
            assertEquals(schemaProperties("attempt-proposal.v1.schema.json", "$defs", "priorFailure"), names(tree.get("priorFailure")));
        }
        assertEquals(CanonicalJson.of(MAPPER.valueToTree(plan)), CanonicalJson.of(tree.get("plan")));
        assertDoesNotThrow(() -> validator.attemptProposal(proposal));
        assertEquals(CanonicalJson.of(MAPPER.valueToTree(task)), CanonicalJson.of(tree.get("taskEnvelope")));
        assertEquals(CanonicalJson.of(MAPPER.valueToTree(frame)), CanonicalJson.of(tree.get("frame")));
        assertRoundTrip(proposal, AttemptProposal.class);
    }

    @Test
    void aProposalCarryingTheTaskEnvelopeAndTheFramePassesItsSchemaAndAKeyItDoesNotListIsRefusedWhenBinding() {
        AttemptProposal proposal = new AttemptProposal("1.0.0", "root-1", 3, RootSamples.task("task-1", false), RootSamples.frame("/w"), plan, List.of("src/a.java"), null);
        JsonNode tree = MAPPER.valueToTree(proposal);
        assertFalse(tree.get("taskEnvelope").has("artifactContracts"), "an absent optional list is left out, not written as null");
        JsonNode withSurprise = MAPPER.readTree("{\"schemaVersion\":\"1.0.0\",\"rootId\":\"root-1\",\"expectedRevision\":3,\"plan\":{},\"outputTargets\":[\"a\"],\"priorFailure\":null,\"surprise\":1}");
        WorkflowFailure refused = assertThrows(WorkflowFailure.class, () -> validator.bind("attempt-proposal.v1.schema.json", withSurprise, AttemptProposal.class));
        assertEquals(WorkflowCode.INVALID_INPUT, refused.code());
        assertTrue(refused.getCause() instanceof JacksonException, String.valueOf(refused.getCause()));
        assertEquals("root-1", validator.bind("attempt-proposal.v1.schema.json", withSurprise, AttemptProposal.RootReference.class).rootId(), "the root a call names is read whatever else it carries");
        assertNull(validator.bind("attempt-proposal.v1.schema.json", MAPPER.readTree("{}"), AttemptProposal.RootReference.class).rootId());
    }

    @Test
    void aProposalWithoutTaskEnvelopeAndFrameTakesTheRootsAndChangesNothingElse() throws IOException {
        AttemptProposal partial = MAPPER.readValue("{\"schemaVersion\":\"1.0.0\",\"rootId\":\"root-1\",\"expectedRevision\":3,\"plan\":" + MAPPER.writeValueAsString(plan)
                + ",\"outputTargets\":[\"a\"],\"priorFailure\":null}", AttemptProposal.class);
        assertEquals(null, partial.taskEnvelope());
        assertEquals(null, partial.frame());
        TaskEnvelope task = RootSamples.task("t", false);
        ConvergenceFrame frame = RootSamples.frame("/f");
        AttemptProposal filled = partial.withTaskAndFrame(task, frame);
        assertEquals(new AttemptProposal("1.0.0", "root-1", 3, task, frame, plan, List.of("a"), null), filled);
        List<String> keys = new ArrayList<>(MAPPER.valueToTree(filled).propertyNames());
        assertEquals(List.of("schemaVersion", "rootId", "expectedRevision", "taskEnvelope", "frame", "plan", "outputTargets", "priorFailure"), keys);
    }
}
