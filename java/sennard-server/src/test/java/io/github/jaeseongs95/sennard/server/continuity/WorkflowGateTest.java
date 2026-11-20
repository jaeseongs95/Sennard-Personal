// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import io.github.jaeseongs95.sennard.server.message.MessageRef;

class WorkflowGateTest {
    @Test
    void unreadableAttributionHoldsTheTurnWithinTheStopBudget() {
        StopReads reads = new StopReads(Read.UNAVAILABLE);
        WorkflowGate gate = new WorkflowGate(reads, null, null, null);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertEquals(List.of("hooks.workflowGate.stopUnavailable"),
                    gate.stopLines("session", "child", false, true).stream().map(MessageRef::key).toList());
        }
        assertTrue(gate.stopLines("session", "child", false, true).isEmpty());
        assertEquals(4, reads.claims);
        assertEquals(0, reads.countReads);
    }

    @Test
    void anAlreadyContinuedOrNoncontinuableTurnDoesNotReadOrClaim() {
        StopReads reads = new StopReads(Read.UNAVAILABLE);
        WorkflowGate gate = new WorkflowGate(reads, null, null, null);

        assertTrue(gate.stopLines("session", null, true, true).isEmpty());
        assertTrue(gate.stopLines("session", null, false, false).isEmpty());
        assertEquals(0, reads.attributionReads);
        assertEquals(0, reads.claims);
    }

    @Test
    void readableAttributionHoldsOnlyAnUnservedTurn() {
        StopReads served = new StopReads(Read.SERVED);
        WorkflowGate servedGate = new WorkflowGate(served, null, null, null);
        assertTrue(servedGate.stopLines("session", null, false, true).isEmpty());
        assertEquals(0, served.claims);

        StopReads unserved = new StopReads(Read.UNSERVED);
        WorkflowGate unservedGate = new WorkflowGate(unserved, null, null, null);
        assertEquals(List.of("hooks.workflowGate.stop"),
                unservedGate.stopLines("session", null, false, true).stream().map(MessageRef::key).toList());
        assertEquals(1, unserved.claims);
        assertEquals(1, unserved.countReads);
    }

    private enum Read { SERVED, UNSERVED, UNAVAILABLE }

    /** Isolates the workflow-attribution read and keeps the limit observable at the gate boundary. */
    private static final class StopReads extends ContinuityService {
        private final Read read;
        private int attributionReads;
        private int claims;
        private int countReads;

        StopReads(Read read) {
            super(null, null, null, null, null, null, null, null, null, null);
            this.read = read;
        }

        @Override
        public boolean handledInTurn(String rawSessionId, String actor) {
            attributionReads++;
            return switch (read) {
                case SERVED -> true;
                case UNSERVED -> false;
                case UNAVAILABLE -> throw new DataAccessResourceFailureException("Attribution read failed");
            };
        }

        @Override
        public boolean claimStopBlock(String rawSessionId, int limit) {
            return ++claims <= limit;
        }

        @Override
        public int openInstanceCount(String rawSessionId) {
            countReads++;
            return 1;
        }
    }
}
