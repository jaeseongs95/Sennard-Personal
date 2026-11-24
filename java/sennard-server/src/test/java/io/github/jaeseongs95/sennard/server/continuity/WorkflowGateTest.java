// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.workflow.ConvergenceLimits;
import io.github.jaeseongs95.sennard.server.workflow.ConvergenceRoot;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowDocuments;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowEvents;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowReceipt;
import io.github.jaeseongs95.sennard.service.store.RootState;
import io.github.jaeseongs95.sennard.service.store.RunState;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;
import tools.jackson.databind.json.JsonMapper;

class WorkflowGateTest {
    @Test
    void unreadableSelectionKeepsTheExistingFallback() {
        StopStore store = new StopStore();
        store.failAttribution = true;
        WorkflowGate gate = new WorkflowGate(service(store), null, null, null, () -> new ConvergenceLimits(5, 2));

        WorkflowGate.StopCheck result = gate.stopCheck("session", null, true);
        assertTrue(result.guidance().isEmpty());
        assertNull(result.terminationReason());
        assertTrue(store.events.isEmpty());
        assertEquals(0, store.countReads);
    }

    @Test
    void aKnownEmptyGateOmitsTheFinalWarningButUsesTheConfiguredBudget() {
        StopStore store = new StopStore();
        WorkflowGate gate = new WorkflowGate(service(store), null, null, null, () -> new ConvergenceLimits(2, 5));

        for (int attempt = 0; attempt < 2; attempt++) {
            WorkflowGate.StopCheck check = gate.stopCheck("session", null, true);
            assertEquals(List.of("hooks.workflowGate.stop"), check.guidance().stream().map(MessageRef::key).toList());
            assertNull(check.terminationReason());
            assertFalse(check.offersResume());
        }
        WorkflowGate.StopCheck exhausted = gate.stopCheck("session", null, true);
        assertTrue(exhausted.guidance().isEmpty());
        assertEquals(MessageRef.of("hooks.workflowGate.stopExhausted"), exhausted.terminationReason());
        assertEquals(exhausted, gate.stopCheck("session", null, true));
        assertEquals(List.of(WorkflowEvents.STOP_BLOCKED, WorkflowEvents.STOP_BLOCKED, WorkflowEvents.STOP_EXHAUSTED), store.events);
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
        store.open = true;
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
        store.open = true;
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
    void anAlreadySelectedRequestSkipsTheGateCountAndBudget() {
        StopStore store = new StopStore();
        store.selected = true;
        WorkflowGate servedGate = new WorkflowGate(service(store), null, null, null, () -> new ConvergenceLimits(2, 5));
        WorkflowGate.StopCheck answer = servedGate.stopCheck("session", null, true);
        assertTrue(answer.guidance().isEmpty());
        assertNull(answer.terminationReason());
        assertTrue(store.events.isEmpty());
        assertEquals(0, store.countReads);
    }

    @Test
    void anUnreadableGateCountStillUsesTheConfiguredBudget() {
        StopStore store = new StopStore();
        store.failCount = true;
        WorkflowGate unservedGate = new WorkflowGate(service(store), null, null, null, () -> new ConvergenceLimits(2, 5));
        assertEquals(List.of("hooks.workflowGate.stopUnavailable"),
                unservedGate.stopCheck("session", null, true).guidance().stream().map(MessageRef::key).toList());
        assertEquals(List.of("hooks.workflowGate.stopUnavailable", "hooks.workflowGate.stopLastRecheck"),
                unservedGate.stopCheck("session", null, true).guidance().stream().map(MessageRef::key).toList());
        assertEquals("hooks.workflowGate.stopExhausted", unservedGate.stopCheck("session", null, true).terminationReason().key());
        assertEquals(List.of(WorkflowEvents.STOP_BLOCKED, WorkflowEvents.STOP_BLOCKED, WorkflowEvents.STOP_EXHAUSTED), store.events);
    }

    private static ContinuityService service(StopStore store) {
        WorkflowDocuments documents = new WorkflowDocuments(null, null) {
            @Override
            public Read readCurrent(String rootId) {
                var root = new ConvergenceRoot("1.0.0", rootId, null, 0, RootState.OPEN, 1, null, null, null, null, null, null, null, null, List.of(),
                        Instant.EPOCH, Instant.EPOCH, List.of(), null);
                var receipt = new WorkflowReceipt("1.0.0", "run", 0, RunState.RUNNING, null, List.of(), List.of(), List.of(), null, List.of(), null);
                return new Readable(root, receipt, List.of(), List.of(), List.of());
            }
        };
        return new ContinuityService(store, null, null, null, null, Clock.systemUTC(), null, null, null, null, documents, null);
    }

    private static final class StopStore extends ContinuityStore {
        private final List<String> events = new ArrayList<>();
        private boolean failAttribution;
        private boolean failCount;
        private boolean open;
        private boolean selected;
        private int countReads;

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
            if (selected && event.equals(WorkflowEvents.turnSelected(afterId))) {
                return 1;
            }
            return events.stream().filter(event::equals).count();
        }

        @Override
        public List<String> boundRoots(String correlation, long epoch) {
            countReads++;
            if (failCount) {
                throw new DataAccessResourceFailureException("Gate count failed");
            }
            return open ? List.of("root") : List.of();
        }

        @Override
        public void recordObservation(String correlation, long epoch, String event, String turnHash, boolean success, String now) {
            events.add(event);
        }
    }

}
