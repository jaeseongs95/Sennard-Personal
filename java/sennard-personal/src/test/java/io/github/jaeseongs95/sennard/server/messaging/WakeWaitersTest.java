// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.messaging.WakeWaiters.MessageQueued;
import io.github.jaeseongs95.sennard.server.messaging.WakeWaiters.Outcome;
import io.github.jaeseongs95.sennard.server.messaging.WakeWaiters.Waiter;

/** How a held hook's wait ends: by its own time, by a signal, by being replaced or released. The wait is answered by the future's completion, not by an exception. */
class WakeWaitersTest {
    private static final Duration SHORT = Duration.ofMillis(30);
    private static final Duration LONG = Duration.ofSeconds(30);

    @Test
    void aWaitThatElapsesEndsTimedOutAndTheNextWaitStillSeesALaterSignal() throws Exception {
        WakeWaiters waiters = new WakeWaiters();
        try (Waiter waiter = waiters.register("host", "s1")) {
            assertEquals(Outcome.TIMED_OUT, waiter.await(SHORT));
            // The timeout did not complete the signal: a message that arrives after it still wakes the next wait of the same waiter.
            waiters.on(new MessageQueued("host", "s1"));
            assertEquals(Outcome.SIGNALLED, waiter.await(LONG));
        }
    }

    @Test
    void aSignalReleaseAndReplacementEndTheWaitWithTheirOwnOutcome() throws Exception {
        WakeWaiters waiters = new WakeWaiters();
        try (Waiter first = waiters.register("host", "s1")) {
            try (Waiter second = waiters.register("host", "s1")) {
                assertEquals(Outcome.REPLACED, first.await(LONG));
                waiters.release("host", "s1");
                assertEquals(Outcome.RELEASED, second.await(LONG));
            }
        }
    }
}
