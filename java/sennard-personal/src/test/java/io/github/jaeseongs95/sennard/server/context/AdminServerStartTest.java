// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * The start of a server process, read from the Clock bean (D-0222): an intent issued before it belongs to an earlier run. The clock is fixed, so no test waits for time to pass.
 */
class AdminServerStartTest {
    private static final Instant START = Instant.parse("2026-11-15T09:00:00Z");

    @Test
    void anIntentIssuedBeforeTheStartPrecedesItAndOneIssuedAtOrAfterItDoesNot() {
        AdminServerStart start = new AdminServerStart(Clock.fixed(START, ZoneOffset.UTC));
        assertTrue(start.precedes(START.minusMillis(1).toEpochMilli()), "the last millisecond before the start");
        assertTrue(start.precedes(START.minus(Duration.ofMinutes(9)).toEpochMilli()), "within the ten minutes an intent lives, yet from an earlier run");
        assertFalse(start.precedes(START.toEpochMilli()), "issued at the start instant");
        assertFalse(start.precedes(START.plusSeconds(1).toEpochMilli()));
    }

    @Test
    void aServerThatStartedLaterRefusesWhatAnEarlierRunIssued() {
        Clock firstRun = Clock.fixed(START, ZoneOffset.UTC);
        Clock restart = Clock.offset(firstRun, Duration.ofMinutes(1));
        long issuedByFirstRun = firstRun.millis();
        assertFalse(new AdminServerStart(firstRun).precedes(issuedByFirstRun));
        assertTrue(new AdminServerStart(restart).precedes(issuedByFirstRun), "the restarted server's page and port are another's");
    }
}
