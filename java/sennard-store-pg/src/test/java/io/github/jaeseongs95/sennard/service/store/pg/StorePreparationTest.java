// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/** The failure half of the start-up state machine (design B.3). The provider is the test's: a database that does not answer. */
class StorePreparationTest {
    @Test
    void threeFailedAttemptsEndInFailedWithTheReasonAndRetryStartsThreeMore() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<StorePhase> seen = new CopyOnWriteArrayList<>();
        StorePreparation preparation = new StorePreparation(phases -> () -> {
            attempts.incrementAndGet();
            seen.add(StorePhase.CONNECTING);
            phases.entered(StorePhase.CONNECTING);
            throw new StoreException(StoreCode.STORE_UNREACHABLE, "the server answered 503");
        }, Duration.ofMillis(20), java.time.Clock.systemUTC());
        assertEquals(StorePhase.NOT_CONFIGURED, preparation.status().phase());
        assertEquals(StoreCode.STORE_PREPARING, assertThrows(StoreException.class, preparation::config).code());
        preparation.start();
        StorePreparation.Status failed = preparation.awaitSettled(Duration.ofSeconds(60));
        assertEquals(StorePhase.FAILED, failed.phase());
        assertEquals("STORE_UNREACHABLE", failed.reason());
        assertTrue(failed.detail() != null && failed.detail().contains("503"), "the status says what failed, not only the code: " + failed.detail());
        assertEquals(3, failed.attempt());
        assertEquals(0, failed.retryAfterMs(), "waiting does not change a FAILED state");
        assertEquals(3, attempts.get(), "exactly three attempts");
        assertEquals(List.of(StorePhase.CONNECTING, StorePhase.CONNECTING, StorePhase.CONNECTING), seen);
        assertEquals(StoreCode.STORE_PREPARING, assertThrows(StoreException.class, preparation::config).code(), "no settings from a failed preparation");
        preparation.retry();
        StorePreparation.Status again = preparation.awaitSettled(Duration.ofSeconds(60));
        assertEquals(StorePhase.FAILED, again.phase());
        assertEquals(6, attempts.get(), "retry made three more");
    }

    /** FAILED is published just before the worker thread ends; a retry arriving in that gap must start, not be dropped. */
    @Test
    void aRetryTheMomentFailedIsPublishedIsNotLost() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        StorePreparation preparation = new StorePreparation(phases -> {
            attempts.incrementAndGet();
            throw new StoreException(StoreCode.STORE_FAILURE, "always");
        }, Duration.ZERO, java.time.Clock.systemUTC());
        preparation.start();
        assertEquals(StorePhase.FAILED, preparation.awaitSettled(Duration.ofSeconds(60)).phase());
        int cycles = 300;
        for (int i = 1; i <= cycles; i++) {
            preparation.retry();
            assertEquals(StorePhase.FAILED, preparation.awaitSettled(Duration.ofSeconds(60)).phase());
            assertEquals(3 * (i + 1), attempts.get(), "retry " + i + " made three more attempts");
        }
    }

    @Test
    void startAndRetryDoNothingWhileRunningOrWhenNotFailed() throws Exception {
        StorePreparation preparation = new StorePreparation(phases -> () -> {
            throw new StoreException(StoreCode.STORE_UNREACHABLE, "nothing listens");
        }, Duration.ofMillis(5), java.time.Clock.systemUTC());
        preparation.retry();
        assertEquals(StorePhase.NOT_CONFIGURED, preparation.status().phase(), "retry is for FAILED only");
        preparation.start();
        preparation.start();
        assertEquals(StorePhase.FAILED, preparation.awaitSettled(Duration.ofSeconds(60)).phase());
    }
}
