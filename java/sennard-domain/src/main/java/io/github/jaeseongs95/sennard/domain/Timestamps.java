// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Every conversion between an instant and what the database stores, in one place. A time column is an ANSI {@code TIMESTAMP} (no time zone) that
 * holds the UTC value of the instant, to the millisecond. The value crosses the JDBC boundary as a {@link LocalDateTime}, never as a
 * {@code java.sql.Timestamp}: a {@code Timestamp} is read and written in the JVM's time zone, so the same instant would be stored as different
 * values by two JVMs (and one that falls in a daylight-saving gap would move). Nothing here reads a zone from the machine.
 *
 * <p>The application layer and the server never name a {@code LocalDateTime}: their times are {@code Instant}s, and the converters of Spring Data JDBC
 * ({@code UtcTimestampColumns} in sennard-app) call this class at the database boundary. The {@code LocalDateTime} methods stay for the modules that still read and write
 * rows themselves (the identity area in sennard-core and the stores of sennard-store-pg), until their rewrite to Spring Data JDBC removes them.
 *
 * <p>Text is the other form a time takes, on the wire and inside stored JSON: UTC with milliseconds and a fixed width
 * ({@code 2026-01-02T03:04:05.006Z}); the client turns it into its own zone. {@code Instant.toString()} drops zero milliseconds and is not used.
 */
public final class Timestamps {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Timestamps() {
    }

    /** The text form of an instant. */
    public static String format(Instant instant) {
        return FORMAT.format(instant);
    }

    /** The text form of a stored value. */
    public static String format(LocalDateTime timestamp) {
        return FORMAT.format(toInstant(timestamp));
    }

    public static Instant parse(String text) {
        return Instant.parse(text);
    }

    /** The value a {@code TIMESTAMP} column holds for this instant: its UTC fields, to the millisecond (the precision every database keeps). */
    public static LocalDateTime toTimestamp(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    /** The value a {@code TIMESTAMP} column holds for a text time. */
    public static LocalDateTime toTimestamp(String text) {
        return toTimestamp(parse(text));
    }

    /** The instant a stored {@code TIMESTAMP} value is. */
    public static Instant toInstant(LocalDateTime timestamp) {
        return timestamp.toInstant(ZoneOffset.UTC);
    }

    /** {@link #toTimestamp(Instant)} for a nullable column. */
    public static LocalDateTime toTimestampOrNull(Instant instant) {
        return instant == null ? null : toTimestamp(instant);
    }

    /** {@link #toInstant(LocalDateTime)} for a nullable column. */
    public static Instant toInstantOrNull(LocalDateTime timestamp) {
        return timestamp == null ? null : toInstant(timestamp);
    }
}
