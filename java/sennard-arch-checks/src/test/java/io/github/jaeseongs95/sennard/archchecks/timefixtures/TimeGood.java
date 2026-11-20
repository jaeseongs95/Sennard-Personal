// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.timefixtures;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** The near misses the time rule must leave alone: the injected clock, a clock argument, UTC conversions and the monotonic timer. */
final class TimeGood {
    private final Clock clock;

    TimeGood(Clock clock) {
        this.clock = clock;
    }

    Instant fromTheClock() {
        return clock.instant();
    }

    Instant nowWithAClock() {
        return Instant.now(clock);
    }

    LocalDateTime column(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    Clock fixed(Instant at) {
        return Clock.fixed(at, ZoneOffset.UTC);
    }

    long monotonic() {
        return System.nanoTime();
    }
}
