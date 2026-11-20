// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.timefixtures;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/** Every way the time rule is broken, once each (TimeRulesTest looks for each of them). */
final class TimeBad {
    Instant instantNow() {
        return Instant.now();
    }

    LocalDateTime localNow() {
        return LocalDateTime.now();
    }

    long millis() {
        return System.currentTimeMillis();
    }

    ZoneId zone() {
        return ZoneId.systemDefault();
    }

    TimeZone timeZone() {
        return TimeZone.getDefault();
    }

    Clock ownClock() {
        return Clock.systemUTC();
    }

    Date date() {
        return new Date();
    }

    Calendar calendar() {
        return Calendar.getInstance();
    }

    Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
