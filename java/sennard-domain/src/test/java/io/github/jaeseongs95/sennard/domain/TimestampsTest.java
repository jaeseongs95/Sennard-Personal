// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

/**
 * The one conversion between an instant and a {@code TIMESTAMP} value. The cases that decide it: the same instant gives the same fields under every
 * JVM zone (Asia/Seoul has no daylight saving, America/New_York has a gap in March and a repeated hour in November, UTC is the reference), and the
 * value reads back as the instant it came from.
 */
class TimestampsTest {
    /** An instant that is a local time which does not exist in New York (the 2026 gap is 02:00 to 03:00 local, 07:00 to 08:00 UTC) and one in its repeated hour. */
    private static final List<Instant> INSTANTS = List.of(Instant.parse("2026-03-08T07:30:00.123Z"), Instant.parse("2026-11-01T05:30:00.456Z"),
            Instant.parse("2026-11-01T06:30:00.789Z"), Instant.parse("2026-01-02T03:04:05.006Z"), Instant.EPOCH);

    private static <T> T inZone(String zone, java.util.function.Supplier<T> body) {
        TimeZone previous = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        try {
            return body.get();
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void theSameInstantGivesTheSameUtcFieldsUnderEveryJvmZone() {
        for (Instant instant : INSTANTS) {
            LocalDateTime utc = LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
            for (String zone : List.of("UTC", "Asia/Seoul", "America/New_York")) {
                assertEquals(utc, inZone(zone, () -> Timestamps.toTimestamp(instant)), instant + " in " + zone);
            }
        }
    }

    @Test
    void aStoredValueReadsBackAsTheInstantItCameFromUnderEveryJvmZone() {
        for (Instant instant : INSTANTS) {
            LocalDateTime stored = Timestamps.toTimestamp(instant);
            for (String zone : List.of("UTC", "Asia/Seoul", "America/New_York")) {
                assertEquals(instant, inZone(zone, () -> Timestamps.toInstant(stored)), instant + " in " + zone);
            }
        }
    }

    @Test
    void aTimeIsKeptToTheMillisecondAndTheTextIsFixedWidth() {
        Instant fine = Instant.parse("2026-01-02T03:04:05.123456789Z");
        assertEquals(Instant.parse("2026-01-02T03:04:05.123Z"), Timestamps.toInstant(Timestamps.toTimestamp(fine)));
        assertEquals("2026-01-02T03:04:05.000Z", Timestamps.format(Instant.parse("2026-01-02T03:04:05Z")), "zero milliseconds stay: the text sorts as time");
        assertEquals("2026-01-02T03:04:05.123Z", Timestamps.format(Timestamps.toTimestamp(fine)));
        assertEquals(Timestamps.toTimestamp(fine), Timestamps.toTimestamp("2026-01-02T03:04:05.123456789Z"));
    }

    @Test
    void aNullColumnStaysNull() {
        assertNull(Timestamps.toTimestampOrNull(null));
        assertNull(Timestamps.toInstantOrNull(null));
    }
}
