// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.modules;

import java.security.SecureRandom;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.jdbc.core.SqlParameterValue;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.Timestamps;

/**
 * The value formats the stores and their services share. A time is a UTC ISO text of fixed width outside the database (what a service hands in and gets
 * back) and a {@code TIMESTAMP} value inside it; {@link Timestamps} is the only conversion between them and this class only adapts it to JDBC.
 */
public final class StoreValues {
    private StoreValues() {
    }

    /**
     * Statement parameters with every enum bound as VARCHAR, so the column holds the constant's name (what Spring Data JDBC does: "Enums get
     * mapped to their names"). JdbcClient itself binds a bare enum to nothing the PostgreSQL driver can type; EnumMappingTest has the measurement.
     * Reading needs no help: Spring's conversion service turns the name back into the constant.
     */
    public static Object[] bind(Object... params) {
        Object[] bound = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            bound[i] = switch (params[i]) {
                case Enum<?> name -> new SqlParameterValue(Types.VARCHAR, name);
                case Instant time -> Timestamps.toTimestamp(time); // an instant is always a TIMESTAMP value, never the JVM zone's java.sql.Timestamp
                case UUID id -> id.toString(); // a UUID column is CHAR(36) holding the text form: the driver would send a UUID typed as uuid, which is PostgreSQL's alone
                case null, default -> params[i];
            };
        }
        return bound;
    }

    /** Millisecond ISO text with a fixed pattern. {@code Instant.toString()} would drop zero milliseconds. */
    public static String iso(long epochMillis) {
        return Timestamps.format(Instant.ofEpochMilli(epochMillis));
    }

    public static long parseIso(String value) {
        return Timestamps.parse(value).toEpochMilli();
    }

    /** The {@code TIMESTAMP} value of a time given as ISO text; null stays null. */
    public static LocalDateTime ts(String iso) {
        return iso == null ? null : Timestamps.toTimestamp(iso);
    }

    /** The {@code TIMESTAMP} value of an instant in epoch milliseconds. */
    public static LocalDateTime ts(long epochMillis) {
        return Timestamps.toTimestamp(Instant.ofEpochMilli(epochMillis));
    }

    /** The {@code TIMESTAMP} value of an instant. */
    public static LocalDateTime ts(Instant instant) {
        return Timestamps.toTimestamp(instant);
    }

    /** A nullable {@code TIMESTAMP} value (what a row record's {@link LocalDateTime} component holds) as ISO text. The JVM's zone takes no part. */
    public static String isoOrNull(LocalDateTime value) {
        return value == null ? null : Timestamps.format(value);
    }

    /**
     * The SHA-256 of the text in hex. {@link Digests#sha256Hex} is the one implementation; this stays only because sennard-server still calls it here (ContextLayerService,
     * SessionMessageService), and goes when they call {@code Digests}.
     */
    public static String sha256Hex(String text) {
        return Digests.sha256Hex(text);
    }

    /** {@code bytes} random bytes from the application's one {@link SecureRandom}, in hex. */
    public static String randomHex(SecureRandom random, int bytes) {
        return HexFormat.of().formatHex(randomBytes(random, bytes));
    }

    /** {@code bytes} random bytes from the application's one {@link SecureRandom}, in unpadded base64url. */
    public static String randomBase64Url(SecureRandom random, int bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(random, bytes));
    }

    private static byte[] randomBytes(SecureRandom random, int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return value;
    }
}
