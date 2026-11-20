// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.support;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.Timestamps;

/**
 * The one place where an {@link Instant} meets what a {@code TIMESTAMP} column holds, and the only class of the application layer that names {@link LocalDateTime}. Everywhere
 * else a time is an {@code Instant}: the entities and the repositories of Spring Data JDBC carry {@code Instant}s, and these converters (registered with Spring Data JDBC by
 * {@code AppConfiguration}) write and read the column. A {@code TIMESTAMP} column has no time zone; it holds the UTC fields of the instant, to the millisecond
 * ({@link Timestamps}).
 *
 * <p>Writing gives the driver a {@code LocalDateTime}, which it stores as it is, so the JVM's zone takes no part. Reading takes what Spring Data JDBC reads from the result set:
 * the driver's {@link Timestamp} of the column's fields (the driver builds it in the JVM's zone and {@link Timestamp#toLocalDateTime()} undoes exactly that, so the
 * fields come back as stored); a time that does not exist in the JVM's own zone (the gap of a daylight-saving change) is the one case the driver cannot hand over, and the
 * product's JVM runs in UTC, which has no gap. A plain query that reads a row itself asks the driver for the {@code LocalDateTime} directly ({@link #instant}).
 */
@Component
public class UtcTimestampColumns {
    /**
     * The conversions Spring Data JDBC needs for {@code Instant} properties and parameters. A list of instances because Spring Data JDBC takes converter objects, not beans:
     * {@code JdbcCustomConversions} is made once, in {@code AppConfiguration}.
     */
    public static List<Converter<?, ?>> converters() {
        return List.of(new ToColumn(), new FromColumn());
    }

    /** The time a plain query read from a {@code TIMESTAMP} column; null for a null column. */
    public Instant instant(ResultSet row, String column) throws SQLException {
        return Timestamps.toInstantOrNull(row.getObject(column, LocalDateTime.class));
    }

    /** The UTC fields bound to a plain TIMESTAMP column. */
    public LocalDateTime column(Instant instant) {
        return Timestamps.toTimestamp(instant);
    }

    /** The column value of an instant: its UTC fields. */
    @WritingConverter
    static final class ToColumn implements Converter<Instant, LocalDateTime> {
        @Override
        public LocalDateTime convert(Instant instant) {
            return Timestamps.toTimestamp(instant);
        }
    }

    /** The instant of a column value the driver handed over as a {@link Timestamp}. */
    @ReadingConverter
    static final class FromColumn implements Converter<Timestamp, Instant> {
        @Override
        public Instant convert(Timestamp column) {
            return Timestamps.toInstant(column.toLocalDateTime());
        }
    }
}
