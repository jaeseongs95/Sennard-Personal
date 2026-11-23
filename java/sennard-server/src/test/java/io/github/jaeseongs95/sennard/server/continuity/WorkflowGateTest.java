// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.workflow.ConvergenceLimits;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowEvents;
import tools.jackson.databind.json.JsonMapper;

class WorkflowGateTest {
    @Test
    void unreadableSelectionKeepsTheExistingFallback() {
        StopReads reads = new StopReads(Read.UNAVAILABLE);
        WorkflowGate gate = new WorkflowGate(reads, null, null, null, () -> new ConvergenceLimits(5, 2));

        WorkflowGate.StopCheck result = gate.stopCheck("session", "child", true);
        assertTrue(result.guidance().isEmpty());
        assertNull(result.terminationReason());
        assertEquals(0, reads.claims);
        assertEquals(0, reads.countReads);
    }

    @Test
    void aNoncontinuableTurnRecordsExhaustionWithoutClaimingARecheck() {
        StopStore store = new StopStore();
        WorkflowGate gate = new WorkflowGate(service(store), null, null, null, () -> {
            throw new AssertionError("A noncontinuable turn must not read the limit");
        });

        WorkflowGate.StopCheck answer = gate.stopCheck("session", null, false);
        assertTrue(answer.guidance().isEmpty());
        assertEquals(MessageRef.of("hooks.workflowGate.stopExhausted"), answer.terminationReason());
        assertEquals(answer, gate.stopCheck("session", null, false));
        assertEquals(List.of(WorkflowEvents.STOP_EXHAUSTED), store.events);
    }

    @Test
    void aPhaseAttributionFailurePreservesTheSelectionRecheck() {
        StopStore store = new StopStore();
        ContinuityService continuity = service(store);
        WorkflowGate gate = new WorkflowGate(continuity, null, null, null, () -> new ConvergenceLimits(2, 5));

        WorkflowGate.StopCheck selection = gate.stopCheck("session", null, true);
        store.failAttribution = true;
        ContinuityService.PhaseCheck phase = continuity.phaseCheck("session", false, null);

        assertEquals(List.of("hooks.workflowGate.stop"), selection.guidance().stream().map(MessageRef::key).toList());
        assertEquals(List.of("hooks.phaseCheck.unavailable"), phase.guidance().stream().map(MessageRef::key).toList());
        assertTrue(phase.violations().isEmpty());
        assertEquals(List.of(WorkflowEvents.STOP_BLOCKED), store.events);

        store.failAttribution = false;
        continuity.phaseCheck("session", false, null);
        assertEquals(List.of(WorkflowEvents.STOP_BLOCKED, "phase-check"), store.events);
    }

    @Test
    void theGateUsesTheConfiguredBudgetAboveEight() {
        StopStore store = new StopStore();
        WorkflowGate gate = new WorkflowGate(service(store), null, null, null, () -> new ConvergenceLimits(9, 2));

        for (int attempt = 1; attempt <= 9; attempt++) {
            WorkflowGate.StopCheck check = gate.stopCheck("session", null, true);
            assertNull(check.terminationReason());
            assertEquals(attempt == 9, check.guidance().stream().anyMatch(ref -> ref.key().equals("hooks.workflowGate.stopLastRecheck")));
        }
        assertEquals(MessageRef.of("hooks.workflowGate.stopExhausted"), gate.stopCheck("session", null, true).terminationReason());
        assertEquals(9L, store.events.stream().filter(WorkflowEvents.STOP_BLOCKED::equals).count());
    }

    @Test
    void onlyAMissingSelectionConsumesTheInputBudget() {
        StopReads served = new StopReads(Read.SERVED);
        WorkflowGate servedGate = new WorkflowGate(served, null, null, null, () -> new ConvergenceLimits(2, 5));
        WorkflowGate.StopCheck answer = servedGate.stopCheck("session", null, true);
        assertTrue(answer.guidance().isEmpty());
        assertNull(answer.terminationReason());
        assertEquals(0, served.claims);

        StopReads unserved = new StopReads(Read.UNSERVED);
        WorkflowGate unservedGate = new WorkflowGate(unserved, null, null, null, () -> new ConvergenceLimits(2, 5));
        assertEquals(List.of("hooks.workflowGate.stop"),
                unservedGate.stopCheck("session", null, true).guidance().stream().map(MessageRef::key).toList());
        assertEquals(List.of("hooks.workflowGate.stop", "hooks.workflowGate.stopLastRecheck"),
                unservedGate.stopCheck("session", null, true).guidance().stream().map(MessageRef::key).toList());
        assertEquals("hooks.workflowGate.stopExhausted", unservedGate.stopCheck("session", null, true).terminationReason().key());
        assertEquals(3, unserved.claims);
        assertEquals(2, unserved.countReads);
    }

    private static ContinuityService service(StopStore store) {
        return new ContinuityService(store, null, null, null, null, Clock.systemUTC(), null, null, null, null, null, null);
    }

    private static final class StopStore extends ContinuityStore {
        private final List<String> events = new ArrayList<>();
        private boolean failAttribution;

        StopStore() {
            super(null, new JsonMapper(), null, null);
        }

        @Override
        public String opaqueDigest(String kind, String value) {
            return value;
        }

        @Override
        public Task readTask(String correlation) {
            return new Task(correlation, 1, null, false, null, null, null, null, null, null, false, null);
        }

        @Override
        public Task readTaskForUpdate(String correlation) {
            return readTask(correlation);
        }

        @Override
        public ObservationAt lastObservationAnyEpoch(String correlation, String event) {
            return null;
        }

        @Override
        public Long lastObservationId(String correlation, long epoch, String event, long beforeId) {
            return event.equals("phase-edit") ? 1L : null;
        }

        @Override
        public List<String> observationEventsAfter(String correlation, long epoch, String prefix, long afterId) {
            if (failAttribution && prefix.equals(WorkflowEvents.ATTRIBUTED_PREFIX)) {
                throw new DataAccessResourceFailureException("Attribution read failed");
            }
            return List.of();
        }

        @Override
        public long countObservationsAnyEpochAfter(String correlation, String event, long afterId) {
            return events.stream().filter(event::equals).count();
        }

        @Override
        public List<String> boundRoots(String correlation, long epoch) {
            return List.of();
        }

        @Override
        public void recordObservation(String correlation, long epoch, String event, String turnHash, boolean success, String now) {
            events.add(event);
        }
    }

    private enum Read { SERVED, UNSERVED, UNAVAILABLE }

    /** Isolates the workflow-attribution read and keeps the limit observable at the gate boundary. */
    private static final class StopReads extends ContinuityService {
        private final Read read;
        private int attributionReads;
        private int claims;
        private int countReads;

        StopReads(Read read) {
            super(null, null, null, null, null, null, null, null, null, null, null, null);
            this.read = read;
        }

        @Override
        public StopSelection checkStopSelection(String rawSessionId, String actor, int limit) {
            attributionReads++;
            return switch (read) {
                case SERVED -> StopSelection.SELECTED;
                case UNSERVED -> {
                    claims++;
                    yield claims < limit ? StopSelection.RECHECK : claims == limit ? StopSelection.LAST_RECHECK : StopSelection.EXHAUSTED;
                }
                case UNAVAILABLE -> throw new DataAccessResourceFailureException("Attribution read failed");
            };
        }

        @Override
        public OpenInstanceCount openInstanceCount(String rawSessionId) {
            countReads++;
            return new OpenInstanceCount(1, List.of());
        }
    }
}
