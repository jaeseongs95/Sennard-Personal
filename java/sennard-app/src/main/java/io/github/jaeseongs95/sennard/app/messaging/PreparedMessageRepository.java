// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import io.github.jaeseongs95.sennard.domain.messaging.MessageState;

/**
 * The drafts and receipts of the senders. It extends the {@code Repository} marker and not {@code CrudRepository} (only the methods declared here are offered),
 * and the SQL of the {@code @Query} methods is ANSI. Times are UTC.
 */
public interface PreparedMessageRepository extends Repository<PreparedMessage, String> {
    Optional<PreparedMessage> findById(String messageId);

    /** INSERT when the version is null, else UPDATE ... WHERE version = ?, which fails with OptimisticLockingFailureException when another writer got there first. */
    PreparedMessage save(PreparedMessage message);

    /**
     * The send: the draft becomes its receipt (the body goes, the send time and the message's expiry are kept, the record lives until {@code expiresAt}), only while the record
     * is still a draft. Of several sends of one ID exactly one gets 1; the others wait for its commit, judge the state again, get 0, and answer with the receipt the first one
     * made. The sender does the rest of the send (the counters, the queue row) only after it got 1.
     */
    @Modifying
    @Query("UPDATE sennard.prepared_messages SET state = 'SUBMITTED', body = NULL, sent_at = :sentAt, message_expires_at = :messageExpiresAt, expires_at = :expiresAt, "
            + "record_bytes = :recordBytes, version = version + 1 WHERE message_id = :messageId AND state = 'PREPARED'")
    int submit(@Param("messageId") String messageId, @Param("sentAt") Instant sentAt, @Param("messageExpiresAt") Instant messageExpiresAt, @Param("expiresAt") Instant expiresAt,
            @Param("recordBytes") long recordBytes);

    /**
     * The recall: the receipt becomes RECALLED at {@code now} and lapses at {@code shorter}, or where it lapsed before when that is earlier, in one statement. The caller has
     * just removed the message's queue row, which is what decides a recall, so a receipt that is not SUBMITTED (0 rows) is a flaw and not a race.
     */
    @Modifying
    @Query("UPDATE sennard.prepared_messages SET state = 'RECALLED', recalled_at = :now, expires_at = CASE WHEN expires_at > :shorter THEN :shorter ELSE expires_at END, "
            + "version = version + 1 WHERE message_id = :messageId AND state = 'SUBMITTED'")
    int recalled(@Param("messageId") String messageId, @Param("now") Instant now, @Param("shorter") Instant shorter);

    /** The first acknowledgement shortens the receipt to {@code expiresAt}; a receipt that already lapses earlier is left as it is (it is never lengthened). */
    @Modifying
    @Query("UPDATE sennard.prepared_messages SET expires_at = :expiresAt, version = version + 1 WHERE message_id = :messageId AND expires_at > :expiresAt")
    int shortenTo(@Param("messageId") String messageId, @Param("expiresAt") Instant expiresAt);

    /** The records that lapsed at or before {@code at}: drafts that were not sent, and receipts whose time has come. */
    List<PreparedMessage> findByExpiresAtLessThanEqual(Instant at);

    /** How many drafts exist (state PREPARED): what the draft limit counts. */
    @Query("SELECT COUNT(*) FROM sennard.prepared_messages WHERE state = 'PREPARED'")
    long countDrafts();

    /** How many receipts exist (state SUBMITTED or RECALLED): what the receipt limit counts. */
    @Query("SELECT COUNT(*) FROM sennard.prepared_messages WHERE state <> 'PREPARED'")
    long countReceipts();

    /** The bytes the live drafts and receipts count for. */
    @Query("SELECT COALESCE(SUM(record_bytes), 0) FROM sennard.prepared_messages WHERE expires_at > :now")
    long liveRecordBytes(@Param("now") Instant now);

    /** The same without one message: what the others take when this one is about to change size. */
    @Query("SELECT COALESCE(SUM(record_bytes), 0) FROM sennard.prepared_messages WHERE expires_at > :now AND message_id <> :messageId")
    long liveRecordBytesExcept(@Param("now") Instant now, @Param("messageId") String messageId);

    /**
     * The live record other than {@code messageId} that lapses first (its {@code expiresAt} is when a place frees); nothing when there is none. The row is returned and not a
     * scalar {@code MIN(expires_at)}: Spring Data JDBC converts the columns of an entity with the converters of {@code UtcTimestampColumns}, and a scalar result with Spring's
     * default conversion, which reads a {@code TIMESTAMP} in the JVM's zone.
     */
    Optional<PreparedMessage> findFirstByExpiresAtGreaterThanAndMessageIdNotOrderByExpiresAtAscMessageIdAsc(Instant now, String messageId);

    default Optional<PreparedMessage> firstLapseExcept(Instant now, String messageId) {
        return findFirstByExpiresAtGreaterThanAndMessageIdNotOrderByExpiresAtAscMessageIdAsc(now, messageId);
    }

    /** The live receipt that lapses first (a draft is not a receipt); nothing when there is none. */
    Optional<PreparedMessage> findFirstByExpiresAtGreaterThanAndStateNotOrderByExpiresAtAscMessageIdAsc(Instant now, MessageState state);

    default Optional<PreparedMessage> firstReceiptLapse(Instant now) {
        return findFirstByExpiresAtGreaterThanAndStateNotOrderByExpiresAtAscMessageIdAsc(now, MessageState.PREPARED);
    }

    /** The same for one sender's receipts. */
    Optional<PreparedMessage> findFirstByExpiresAtGreaterThanAndStateNotAndSenderRefOrderByExpiresAtAscMessageIdAsc(Instant now, MessageState state, String senderRef);

    default Optional<PreparedMessage> firstReceiptLapseOfSender(Instant now, String senderRef) {
        return findFirstByExpiresAtGreaterThanAndStateNotAndSenderRefOrderByExpiresAtAscMessageIdAsc(now, MessageState.PREPARED, senderRef);
    }

    /** Removes a lapsed record when it is still the version that was read and no queue row refers to it; returns 1 when it went. */
    @Modifying
    @Query("DELETE FROM sennard.prepared_messages WHERE message_id = :messageId AND version = :version "
            + "AND NOT EXISTS (SELECT 1 FROM sennard.messages m WHERE m.message_id = prepared_messages.message_id)")
    int deleteLapsed(@Param("messageId") String messageId, @Param("version") long version);
}
