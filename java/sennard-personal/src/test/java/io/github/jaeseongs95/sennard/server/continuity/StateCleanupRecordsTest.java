// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.continuity.StateCleanupService.Candidates;
import io.github.jaeseongs95.sennard.server.continuity.StateCleanupService.CleanupPlan;
import io.github.jaeseongs95.sennard.server.continuity.StateCleanupService.CleanupReceipt;
import io.github.jaeseongs95.sennard.server.continuity.StateCleanupService.ContinuityResult;
import io.github.jaeseongs95.sennard.server.continuity.StateCleanupService.Counts;
import io.github.jaeseongs95.sennard.server.continuity.StateCleanupService.Cutoffs;
import io.github.jaeseongs95.sennard.server.continuity.StateCleanupService.DatabaseResults;
import io.github.jaeseongs95.sennard.server.continuity.StateCleanupService.PlanPayload;
import io.github.jaeseongs95.sennard.server.continuity.StateCleanupService.Policy;
import io.github.jaeseongs95.sennard.server.continuity.StateCleanupService.ProtectionSummary;
import io.github.jaeseongs95.sennard.server.continuity.StateCleanupService.SnapshotCandidate;
import io.github.jaeseongs95.sennard.server.continuity.StateCleanupService.WorkflowResult;
import io.github.jaeseongs95.sennard.service.store.RootState;
import io.github.jaeseongs95.sennard.service.store.RunState;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The cleanup plan, the signed token's document and the receipt as records: what each writes is the wire text of
 * {@code contracts/state-cleanup-plan.v1} and {@code state-cleanup-receipt.v1} (key order, nulls, enum text), and what the token's document reads
 * back is what was signed. The signing, the claim and the deletes themselves run against the database in
 * {@code ContinuityMessagingToolContractTest} (a plan runs once, a forged one is refused) and the golden cases {@code T-prepare_state_cleanup-ok} and
 * {@code T-execute_state_cleanup-ok}.
 */
class StateCleanupRecordsTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Candidates EMPTY = new Candidates(List.of(), List.of(), List.of(), List.of());

    private static final Cutoffs CUTOFFS = new Cutoffs("2026-05-01T00:00:00.000Z", "2026-10-12T00:00:00.000Z", "2026-05-01T00:00:00.000Z");
    private static final ProtectionSummary PROTECTION = new ProtectionSummary("2026-11-11T00:00:00.000Z", 15, 1, 2, 3, 21);

    /** T1: the digest of an empty plan is the one the golden record of {@code prepare_state_cleanup} holds, so the digest bytes did not change. */
    @Test
    void theDigestOfNothingToDeleteIsTheRecordedOne() {
        assertEquals("sha256:37cca38aff3bf78ca32c50c2d17750c894313a1759bfba79bfd6a2e047d74d31", StateCleanupService.digest(MAPPER, EMPTY, List.of()));
    }

    /** T2: the candidates are written in the plan's order with the store rows' own members, and a snapshot carries its kind first. */
    @Test
    void theCandidatesAreWrittenInTheContractsShape() {
        Candidates candidates = new Candidates(
                List.of(new WorkflowStore.CleanupRoot("r1", 3, RootState.COMPLETED, "2026-01-01T00:00:00.000Z", List.of("run1", "run2"))),
                List.of(new WorkflowStore.CleanupRun("run3", 1, RunState.PASSED, "2026-01-02T00:00:00.000Z")),
                List.of(SnapshotCandidate.of(new ContinuityStore.CleanupSnapshot("c1", "r2", 2, "token", "memory", "2026-01-03T00:00:00.000Z"))),
                List.of(new ContinuityStore.CleanupTask("c1", 2, "r2", "2026-01-03T00:00:00.000Z")));

        assertEquals("{\"workflowRoots\":[{\"rootId\":\"r1\",\"revision\":3,\"state\":\"COMPLETED\",\"updatedAt\":\"2026-01-01T00:00:00.000Z\",\"runIds\":[\"run1\",\"run2\"]}],"
                + "\"standaloneWorkflowRuns\":[{\"runId\":\"run3\",\"revision\":1,\"state\":\"PASSED\",\"updatedAt\":\"2026-01-02T00:00:00.000Z\"}],"
                + "\"continuitySnapshots\":[{\"kind\":\"current\",\"taskCorrelation\":\"c1\",\"rootId\":\"r2\",\"epoch\":2,\"stateToken\":\"token\",\"memoryDigest\":\"memory\","
                + "\"updatedAt\":\"2026-01-03T00:00:00.000Z\"}],"
                + "\"continuityTasks\":[{\"taskCorrelation\":\"c1\",\"currentEpoch\":2,\"rootId\":\"r2\",\"updatedAt\":\"2026-01-03T00:00:00.000Z\"}]}", MAPPER.writeValueAsString(candidates));
    }

    /** T3: a plan names its members in the contract's order, with the protection and the token last. */
    @Test
    void aPlanIsWrittenWithTheContractsMembers() {
        CleanupPlan plan = new CleanupPlan("1.0.0", "plan-1", "2026-11-11T00:00:00.000Z", new Policy(180, 30, 180), CUTOFFS, EMPTY, new Counts(0, 0, 0, 0, 15, 4), "sha256:d", "os-managed-unverified",
                "token", PROTECTION);

        assertEquals(List.of("schemaVersion", "planId", "createdAt", "policy", "cutoffs", "candidates", "counts", "candidateDigest", "protection", "planToken", "rootProtection"),
                MAPPER.readTree(MAPPER.writeValueAsString(plan)).propertyNames().stream().toList());
        assertEquals("{\"workflowRetentionDays\":180,\"continuityPayloadRetentionDays\":30,\"continuityRecordRetentionDays\":180}", MAPPER.writeValueAsString(plan.policy()));
        assertEquals("{\"workflowRoots\":0,\"workflowRuns\":0,\"continuitySnapshots\":0,\"continuityTasks\":0,\"protectedActiveRoots\":15,\"protectedActiveContinuityTasks\":4}",
                MAPPER.writeValueAsString(plan.counts()));
    }

    /** T4: a receipt that deleted nothing keeps its nulls, writes the status text the contract spells, and has no protection member when the token had none. */
    @Test
    void aReceiptOfNothingDeletedKeepsItsNullsAndOmitsAMissingProtection() {
        CleanupReceipt receipt = new CleanupReceipt("1.0.0", "plan-1", CleanupStatus.Receipt.NO_OP, "2026-11-11T00:00:01.000Z", "sha256:d",
                new DatabaseResults(new WorkflowResult(CleanupStatus.Database.SKIPPED, null, 0, 0, null), new ContinuityResult(CleanupStatus.Database.SKIPPED, null, 0, 0, null)), null,
                "manual-deletion-only", "os-managed-unverified");

        assertEquals("{\"schemaVersion\":\"1.0.0\",\"planId\":\"plan-1\",\"status\":\"no-op\",\"executedAt\":\"2026-11-11T00:00:01.000Z\",\"candidateDigest\":\"sha256:d\","
                + "\"databases\":{\"workflow\":{\"status\":\"skipped\",\"backupPath\":null,\"deletedRoots\":0,\"deletedRuns\":0,\"error\":null},"
                + "\"continuity\":{\"status\":\"skipped\",\"backupPath\":null,\"deletedSnapshots\":0,\"deletedTasks\":0,\"error\":null}},"
                + "\"backupRetention\":\"manual-deletion-only\",\"protection\":\"os-managed-unverified\"}", MAPPER.writeValueAsString(receipt));
    }

    /** T5: a receipt that deleted something carries the token's protection between the databases and the retention. */
    @Test
    void aCompletedReceiptCarriesTheProtectionOfItsPlan() {
        CleanupReceipt receipt = new CleanupReceipt("1.0.0", "plan-1", CleanupStatus.Receipt.COMPLETED, "2026-11-11T00:00:01.000Z", "sha256:d",
                new DatabaseResults(new WorkflowResult(CleanupStatus.Database.COMPLETED, null, 2, 5, null), new ContinuityResult(CleanupStatus.Database.SKIPPED, null, 0, 0, null)), PROTECTION,
                "manual-deletion-only", "os-managed-unverified");

        assertEquals(List.of("schemaVersion", "planId", "status", "executedAt", "candidateDigest", "databases", "rootProtection", "backupRetention", "protection"),
                MAPPER.readTree(MAPPER.writeValueAsString(receipt)).propertyNames().stream().toList());
        assertEquals("{\"previewCreatedAt\":\"2026-11-11T00:00:00.000Z\",\"activeRoots\":15,\"linkedNonTerminalRoots\":1,\"linkedRecentRoots\":2,\"linkedMissingReceiptRoots\":3,\"totalProtectedRoots\":21}",
                MAPPER.writeValueAsString(receipt.rootProtection()));
        assertEquals("\"completed\"", MAPPER.writeValueAsString(receipt.status()));
    }

    /** T6: the protection the workflow store counted is carried over member for member, with the plan's creation instant. */
    @Test
    void theProtectionOfTheStoreIsCarriedOver() {
        assertEquals(PROTECTION, ProtectionSummary.of("2026-11-11T00:00:00.000Z", new WorkflowStore.RootProtection(15, 1, 2, 3, 21)));
    }

    /** T7: what was signed reads back as the same document. */
    @Test
    void aSignedDocumentReadsBackAsWritten() {
        PlanPayload signed = new PlanPayload("1.0.0", "plan-1", "2026-11-11T00:00:00.000Z", new Policy(180, 30, 180), CUTOFFS, "sha256:d", PROTECTION);

        assertEquals(signed, MAPPER.readValue(MAPPER.writeValueAsBytes(signed), PlanPayload.class));
    }

    /** T8: members the document does not know are ignored, and a missing cutoff or id reads as empty, as the tree reads gave. */
    @Test
    void aDocumentWithUnknownOrMissingMembersIsReadLeniently() {
        PlanPayload read = MAPPER.readValue("{\"planId\":\"plan-1\",\"extra\":true,\"cutoffs\":{\"workflow\":\"w\",\"later\":1}}", PlanPayload.class);

        assertEquals("plan-1", read.planId());
        assertEquals("", read.candidateDigest());
        assertEquals(new Cutoffs("w", "", ""), read.cutoffs());
        assertNull(read.rootProtection());
        assertNull(MAPPER.readValue("{\"planId\":\"plan-1\"}", PlanPayload.class).cutoffs());
    }

    /** T9 (binding error): a document that is not an object, or has a member of the wrong kind, is refused by the binding. */
    @Test
    void aDocumentOfTheWrongShapeIsRefused() {
        assertThrows(JacksonException.class, () -> MAPPER.readValue("[1,2]", PlanPayload.class));
        assertThrows(JacksonException.class, () -> MAPPER.readValue("{\"planId\":\"plan-1\",\"cutoffs\":\"2026\"}", PlanPayload.class));
        assertThrows(JacksonException.class, () -> MAPPER.readValue("{\"planId\":", PlanPayload.class));
        assertNull(MAPPER.readValue("null", PlanPayload.class));
    }
}
