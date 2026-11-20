// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.DispatchState;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Profile;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Usage;
import io.github.jaeseongs95.sennard.service.store.modules.JevUsageService;
import io.github.jaeseongs95.sennard.service.store.modules.JevUsageStore;

/**
 * Cases of {@link JevBudget#settle}:
 * <ol>
 * <li>An answered call whose usage cannot be recorded: the failure goes up to the caller (it is not logged and dropped, which would leave the call out of the total and let every later
 * reserve see a larger budget than there is). The reservation is released all the same.</li>
 * <li>An answered call whose usage is recorded: the one write of the call's tokens, and the reservation released.</li>
 * <li>A call that was not started, or that got no answer: nothing is recorded, nothing is raised.</li>
 * </ol>
 */
class JevBudgetTest {
    private static final double PER_CALL = 0.5;
    private static final Profile PROFILE = new Profile("acme-small", null, "acme", "small", null, null, null, null, null, null, null, 0, 0, PER_CALL, null, null);
    private static final String RESERVATION = "reservation-1";

    @Test
    void aFailureToRecordTheUsageOfAnAnsweredCallGoesUpAndTheReservationIsStillReleased() {
        CannotAcquireLockException lockFailure = new CannotAcquireLockException("the usage row is locked");
        JevBudget budget = budget(new Usage0() {
            @Override
            public void add(Instant at, String modelId, long inputTokens, long outputTokens, double estimatedUsd) {
                throw lockFailure;
            }
        });
        assertTrue(budget.reserve(PROFILE, RESERVATION, 10.0));
        CannotAcquireLockException raised = assertThrows(CannotAcquireLockException.class, () -> budget.settle(RESERVATION, DispatchState.STARTED, PROFILE, new Usage(100L, 20L, null, null)));
        assertSame(lockFailure, raised);
        assertTrue(budget.reserve(PROFILE, RESERVATION, 10.0), "the reservation was released before the failure went up");
    }

    @Test
    void anAnsweredCallIsRecordedOnceWithItsTokens() {
        AtomicInteger writes = new AtomicInteger();
        JevBudget budget = budget(new Usage0() {
            @Override
            public void add(Instant at, String modelId, long inputTokens, long outputTokens, double estimatedUsd) {
                writes.incrementAndGet();
                assertEquals("small", modelId);
                assertEquals(100L, inputTokens);
                assertEquals(20L, outputTokens);
            }
        });
        assertTrue(budget.reserve(PROFILE, RESERVATION, 10.0));
        budget.settle(RESERVATION, DispatchState.STARTED, PROFILE, new Usage(100L, 20L, null, null));
        assertEquals(1, writes.get());
    }

    @Test
    void aCallThatWasNotStartedOrGotNoAnswerRecordsNothingAndRaisesNothing() {
        JevBudget budget = budget(new Usage0() {
            @Override
            public void add(Instant at, String modelId, long inputTokens, long outputTokens, double estimatedUsd) {
                throw new AssertionError("nothing is recorded for a call without an answer");
            }
        });
        assertTrue(budget.reserve(PROFILE, RESERVATION, 10.0));
        budget.settle(RESERVATION, DispatchState.NOT_STARTED, PROFILE, new Usage(100L, 20L, null, null));
        assertTrue(budget.reserve(PROFILE, RESERVATION, 10.0));
        budget.settle(RESERVATION, DispatchState.STARTED, PROFILE, Usage.unknown());
    }

    private static JevBudget budget(Usage0 usage) {
        return new JevBudget(usage, new JevProviders(List.of(new Acme())), Clock.systemUTC());
    }

    /** The usage service with no store behind it: the total is zero, and a test says what recording does. */
    private abstract static class Usage0 extends JevUsageService {
        Usage0() {
            super(null);
        }

        @Override
        public JevUsageStore.Totals totals() {
            return new JevUsageStore.Totals(0, 0.0);
        }
    }

    /** A provider that offers one model and estimates every call at the same price. */
    private static final class Acme implements JevProvider {
        @Override
        public String providerId() {
            return "acme";
        }

        @Override
        public String label() {
            return "Acme";
        }

        @Override
        public String endpoint() {
            return "http://localhost/acme";
        }

        @Override
        public String credentialName() {
            return "acme-key";
        }

        @Override
        public double maximumCostUsd() {
            return PER_CALL;
        }

        @Override
        public double estimateUsd(long inputTokens) {
            return 0.01;
        }

        @Override
        public Optional<Profile> profileFor(String modelId) {
            return "small".equals(modelId) ? Optional.of(PROFILE) : Optional.empty();
        }

        @Override
        public int projectionBytes(SkillTypes.Request request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Evaluated classify(SkillTypes.Request request, Profile profile, SecretValue key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CatalogSnapshot catalog(SecretValue key, long nowMs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TransmittedFields transmittedFields() {
            throw new UnsupportedOperationException();
        }
    }
}
