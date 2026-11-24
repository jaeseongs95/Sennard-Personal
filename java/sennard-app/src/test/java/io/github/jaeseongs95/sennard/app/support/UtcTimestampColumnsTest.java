// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import io.github.jaeseongs95.sennard.app.AppConfiguration;
import io.github.jaeseongs95.sennard.app.board.BoardSession;
import io.github.jaeseongs95.sennard.app.board.BoardSessionKey;
import io.github.jaeseongs95.sennard.app.board.BoardSessionRepository;
import io.github.jaeseongs95.sennard.app.messaging.PreparedMessage;
import io.github.jaeseongs95.sennard.app.messaging.PreparedMessageRepository;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder;

/**
 * The one conversion between an {@code Instant} and a {@code TIMESTAMP} column ({@link UtcTimestampColumns}), and the proof that the application layer's entities carry
 * {@code Instant}s through Spring Data JDBC and come back as the same instants. Cases:
 *
 * <pre>
 * t1  writing        an instant is the UTC fields of that moment, to the millisecond, whatever offset the moment is written in (offsets from -18:00 to +18:00, a date
 *                    boundary, a leap day): the date of the value is the UTC date, never the date at the offset
 * t2  reading        the driver's value of a column (a Timestamp of the stored fields) is the instant those fields are in UTC; writing then reading is the identity
 * t3  Spring Data    an entity with {@code Instant}s is saved and found on an embedded H2 through the context's own converters ({@code AppConfiguration}): the column holds the
 *                    UTC wall time as text, the instant comes back equal, a derived query takes an {@code Instant} parameter, and the queries for the first record to lapse
 *                    answer the row (a scalar {@code MIN(expires_at)} read by Spring's default conversion came back nine hours early in this PC's zone: that is why they answer rows)
 * t4  a null column  stays null on both sides
 * </pre>
 *
 * The JVM's own zone is not changed here (the global default is not a test's to set); the ones that are not UTC are the offsets of t1, the cases of {@code TimestampsTest} in
 * sennard-domain that set the default zone, and sennard-store-pg's tests on a real PostgreSQL that save through these converters under another default zone.
 */
class UtcTimestampColumnsTest {
    private static final List<Instant> INSTANTS = List.of(Instant.parse("2026-03-08T07:30:00.123Z"), Instant.parse("2026-11-01T05:30:00.456Z"), Instant.parse("2026-12-31T23:59:59.999Z"),
            Instant.parse("2027-01-01T00:00:00Z"), Instant.parse("2028-02-29T12:00:00.001Z"), Instant.EPOCH);
    private static final List<ZoneOffset> OFFSETS = List.of(ZoneOffset.UTC, ZoneOffset.ofHours(9), ZoneOffset.ofHours(-5), ZoneOffset.ofHoursMinutes(5, 45), ZoneOffset.MAX, ZoneOffset.MIN);

    private final UtcTimestampColumns.ToColumn writing = new UtcTimestampColumns.ToColumn();
    private final UtcTimestampColumns.FromColumn reading = new UtcTimestampColumns.FromColumn();

    @Test
    void t1AnInstantIsItsUtcFieldsWhateverOffsetItIsWrittenIn() {
        for (Instant instant : INSTANTS) {
            LocalDateTime utc = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
            for (ZoneOffset offset : OFFSETS) {
                OffsetDateTime written = instant.atOffset(offset); // the same moment, as a client in that offset writes it
                LocalDateTime column = writing.convert(written.toInstant());
                assertEquals(utc, column, instant + " written at " + offset);
                assertEquals(utc.toLocalDate(), column.toLocalDate(), "the date is the UTC date at " + offset);
                if (!ZoneOffset.UTC.equals(offset) && !instant.equals(Instant.EPOCH)) {
                    assertNotEquals(written.toLocalDateTime(), column, "the fields of the offset are not what is stored (" + offset + ")");
                }
            }
        }
        assertEquals(LocalDateTime.parse("2026-01-02T03:04:05.123"), writing.convert(Instant.parse("2026-01-02T03:04:05.123456789Z")), "to the millisecond");
    }

    @Test
    void t2WhatTheDriverReadsIsTheInstantOfTheStoredFields() {
        for (Instant instant : INSTANTS) {
            LocalDateTime column = writing.convert(instant);
            assertEquals(instant, reading.convert(Timestamp.valueOf(column)), "writing then reading is the identity: " + instant);
        }
        assertEquals(Instant.parse("2026-01-02T03:04:05.006Z"), reading.convert(Timestamp.valueOf(LocalDateTime.parse("2026-01-02T03:04:05.006"))));
    }

    // ------------------------------------------------------------------------------------------------------------------ t3, t4: Spring Data JDBC on H2

    @Configuration(proxyBeanMethods = false)
    @Import(AppConfiguration.class)
    @ImportAutoConfiguration({JdbcTemplateAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
            DataJdbcRepositoriesAutoConfiguration.class, JacksonAutoConfiguration.class})
    static class Context {
        @Bean
        EmbeddedDatabase dataSource() {
            // Spring's embedded database builder makes the in-memory H2 DataSource (the database lives as long as the bean, shut down with the context). Spring Data JDBC quotes the
            // names it makes (the schema and table as written, the columns in the dialect's case): the identifiers are lower case and case insensitive here, so the tables below
            // are made with the same names as the PostgreSQL ones; the builder appends the name to the URL, so the two H2 options ride on it.
            return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).setName(UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE").build();
        }
    }

    private static AnnotationConfigApplicationContext context;

    @BeforeAll
    static void startContext() {
        context = new AnnotationConfigApplicationContext(Context.class);
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        jdbc.execute("CREATE SCHEMA sennard");
        jdbc.execute("CREATE TABLE sennard.board_sessions (host VARCHAR(100) NOT NULL, session_id VARCHAR(100) NOT NULL, "
                + "cwd VARCHAR(200), summary VARCHAR(500), summary_at TIMESTAMP, last_prompt_at TIMESTAMP, seen_at TIMESTAMP NOT NULL, role VARCHAR(200), version BIGINT NOT NULL, "
                + "user_title " + SqlBuilder.generateLongText(SqlBuilder.Database.H2) + ", user_title_observed_at TIMESTAMP, "
                + "PRIMARY KEY (host, session_id))");
        jdbc.execute("CREATE TABLE sennard.prepared_messages (message_id VARCHAR(128) NOT NULL, sender_ref VARCHAR(100), "
                + "sender_actor_id VARCHAR(200) NOT NULL, target_ref VARCHAR(100), target_actor_id VARCHAR(200) NOT NULL, "
                + "body VARCHAR(5000), ttl_seconds BIGINT NOT NULL, prepared_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL, state VARCHAR(20) NOT NULL, "
                + "sent_at TIMESTAMP, message_expires_at TIMESTAMP, recalled_at TIMESTAMP, record_bytes BIGINT NOT NULL, version BIGINT NOT NULL, PRIMARY KEY (message_id))");
    }

    @AfterAll
    static void stopContext() {
        context.close();
    }

    @Test
    void t3AnEntityWithInstantsIsStoredAsUtcWallTimeAndComesBackTheSame() {
        BoardSessionRepository sessions = context.getBean(BoardSessionRepository.class);
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        Instant seen = Instant.parse("2026-12-31T23:59:59.999Z");
        Instant prompt = Instant.parse("2026-03-08T07:30:00.123Z");
        BoardSessionKey key = new BoardSessionKey("claude", "s1");

        sessions.save(BoardSession.first(key, seen).seen("/work", prompt, seen));

        assertEquals("2026-12-31 23:59:59.999", jdbc.queryForObject("SELECT CAST(seen_at AS VARCHAR(40)) FROM sennard.board_sessions WHERE host = ?", String.class, "claude"),
                "the column holds the UTC wall time");
        assertEquals("2026-03-08 07:30:00.123", jdbc.queryForObject("SELECT CAST(last_prompt_at AS VARCHAR(40)) FROM sennard.board_sessions WHERE host = ?", String.class, "claude"));
        BoardSession found = sessions.findByKeyHostAndKeySessionId("claude", "s1").orElseThrow();
        assertEquals(seen, found.seenAt());
        assertEquals(prompt, found.lastPromptAt());
        assertNull(found.summaryAt(), "t4: a null column stays null");
        assertNull(found.userTitle());
        assertNull(found.userTitleObservedAt());

        Instant observed = Instant.parse("2027-01-01T00:00:00.125Z");
        sessions.observeTitle("claude", "s1", "Session title", observed);
        assertEquals("2027-01-01 00:00:00.125", jdbc.queryForObject(
                "SELECT CAST(user_title_observed_at AS VARCHAR(40)) FROM sennard.board_sessions WHERE host = ? AND session_id = ?", String.class, "claude", "s1"));
        BoardSession titled = sessions.findByKeyHostAndKeySessionId("claude", "s1").orElseThrow();
        assertEquals("Session title", titled.userTitle());
        assertEquals(observed, titled.userTitleObservedAt());

        assertEquals(0, sessions.deleteBySeenAtLessThan(seen), "a derived query takes an Instant; the row is not before itself");
        assertEquals(1, sessions.deleteBySeenAtLessThan(seen.plusMillis(1)));
    }

    @Test
    void t3TheQueriesForTheFirstToLapseAnswerTheInstantOfTheRow() {
        PreparedMessageRepository drafts = context.getBean(PreparedMessageRepository.class);
        Instant prepared = Instant.parse("2026-03-08T07:00:00Z");
        Instant first = Instant.parse("2026-03-08T07:45:00.250Z");
        Instant second = Instant.parse("2026-11-01T05:30:00.456Z");
        drafts.save(PreparedMessage.draft("message-one", "a", "b", "x", 60, prepared, first, 10).submitted(prepared, first, first, 10));
        drafts.save(PreparedMessage.draft("message-two", "a", "b", "x", 60, prepared, second, 10).submitted(prepared, second, second, 10));

        assertEquals(first, drafts.firstReceiptLapse(prepared).orElseThrow().expiresAt(), "the earliest of the live receipts, as the instant it was stored");
        assertEquals(second, drafts.firstLapseExcept(prepared, "message-one").orElseThrow().expiresAt());
        assertTrue(drafts.firstReceiptLapse(second).isEmpty(), "nothing lapses after the last one: no row, not an exception");
        assertTrue(drafts.findByExpiresAtLessThanEqual(first).stream().allMatch(row -> !row.expiresAt().isAfter(first)));
    }
}
