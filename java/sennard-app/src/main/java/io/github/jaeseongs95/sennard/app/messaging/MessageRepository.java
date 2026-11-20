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
import org.springframework.data.domain.Limit;

/**
 * The queue. Same shape as {@link PreparedMessageRepository}: the {@code Repository} marker (only the methods declared here), ANSI SQL, UTC times.
 * The row limits on derived queries are rendered by Spring Data's database dialect.
 */
public interface MessageRepository extends Repository<Message, String> {
    Optional<Message> findById(String messageId);

    /**
     * The INSERT of a message that enters the queue (its version is null). A row is changed after that only by the conditional statements below, whose number of changed
     * rows tells who won, so no caller saves a row it read.
     */
    Message save(Message message);

    /**
     * The claim, one statement: the row is taken only while it is still claimable (not acknowledged, not expired, no live lease: the same condition as
     * {@link #findClaimable}) and still at the delivery attempt the claimer read ({@code previousAttempts}, which the receipt of the claim is numbered from). Of several
     * claimers of one row exactly one gets 1 and the others get 0: a claimer that finds the row locked waits for the winner's commit and then judges the WHERE again, which
     * the winner's lease now fails. A claimer that gets 0 does nothing more with that message, it is another claimer's now; nothing reads the row and saves it.
     */
    @Modifying
    @Query("UPDATE sennard.messages SET claimed_at = :now, claim_until = :claimUntil, delivery_attempts = delivery_attempts + 1, first_delivered_at = :firstDeliveredAt, "
            + "claim_receipt = :receipt, version = version + 1 WHERE message_id = :messageId AND target_ref = :targetRef AND target_actor_id = :targetActorId "
            + "AND acknowledged_at IS NULL AND expires_at > :now "
            + "AND (claim_until IS NULL OR claim_until <= :now) AND delivery_attempts = :previousAttempts")
    int claim(@Param("messageId") String messageId, @Param("targetRef") String targetRef, @Param("targetActorId") String targetActorId,
            @Param("previousAttempts") long previousAttempts, @Param("now") Instant now, @Param("claimUntil") Instant claimUntil,
            @Param("firstDeliveredAt") Instant firstDeliveredAt, @Param("receipt") String receipt);

    /**
     * The acknowledgement: the recipient closes the message while it still holds the claim of this receipt. The holder, the claim and the receipt are all in the WHERE, so
     * of several acknowledgements of one claim exactly one gets 1, and an acknowledgement of a claim that was given again since (another receipt) gets 0.
     */
    @Modifying
    @Query("UPDATE sennard.messages SET acknowledged_at = :now, claim_until = NULL, acknowledgement_receipt = :receipt, version = version + 1 "
            + "WHERE message_id = :messageId AND target_ref = :targetRef AND target_actor_id = :targetActorId "
            + "AND claimed_at IS NOT NULL AND acknowledged_at IS NULL AND claim_receipt = :receipt")
    int acknowledge(@Param("messageId") String messageId, @Param("targetRef") String targetRef, @Param("targetActorId") String targetActorId,
            @Param("receipt") String receipt, @Param("now") Instant now);

    /** The next messages a claim may take, in the queue's order: unacknowledged, not expired, not under a live lease. */
    List<Message> findByTargetRefAndTargetActorIdAndAcknowledgedAtIsNullAndExpiresAtGreaterThanAndClaimUntilIsNullOrTargetRefAndTargetActorIdAndAcknowledgedAtIsNullAndExpiresAtGreaterThanAndClaimUntilLessThanEqualOrderBySeqAsc(
            String target1, String actor1, Instant expires1, String target2, String actor2, Instant expires2, Instant claimUntil, Limit limit);

    default List<Message> findClaimable(String targetRef, String targetActorId, Instant now, int limit) {
        return findByTargetRefAndTargetActorIdAndAcknowledgedAtIsNullAndExpiresAtGreaterThanAndClaimUntilIsNullOrTargetRefAndTargetActorIdAndAcknowledgedAtIsNullAndExpiresAtGreaterThanAndClaimUntilLessThanEqualOrderBySeqAsc(
                targetRef, targetActorId, now, targetRef, targetActorId, now, now, Limit.of(limit));
    }

    /** How many messages wait for the recipient (what {@link #findClaimable} would offer, without the limit). */
    @Query("SELECT COUNT(*) FROM sennard.messages WHERE target_ref = :targetRef AND target_actor_id = :targetActorId AND acknowledged_at IS NULL AND expires_at > :now "
            + "AND (claim_until IS NULL OR claim_until <= :now)")
    long countClaimable(@Param("targetRef") String targetRef, @Param("targetActorId") String targetActorId, @Param("now") Instant now);

    default long countClaimable(String targetRef, Instant now) {
        return countClaimable(targetRef, "", now);
    }

    /** How many messages the queue holds that nobody acknowledged (lapsed ones too, until they are removed): what the queue's message limit counts. */
    @Query("SELECT COUNT(*) FROM sennard.messages WHERE acknowledged_at IS NULL")
    long countUnacknowledged();

    /** The bodies' bytes of the messages that nobody acknowledged: what the queue's byte limit counts. */
    @Query("SELECT COALESCE(SUM(body_bytes), 0) FROM sennard.messages WHERE acknowledged_at IS NULL")
    long unacknowledgedBytes();

    /**
     * The highest sequence number of the recipient's queue, 0 when it has none. The next is this plus one. It is read only after the writer changed the recipient's session row
     * ({@link SessionRefRepository#addPending}), which keeps the senders to one recipient in a line, so two of them never make the same number.
     */
    @Query("SELECT COALESCE(MAX(seq), 0) FROM sennard.messages WHERE target_ref = :targetRef")
    long highestSeq(@Param("targetRef") String targetRef);

    /** The live messages between two sessions, either way, newest first (what a peer wait reads). */
    List<Message> findByExpiresAtGreaterThanAndSenderRefAndSenderActorIdAndTargetRefAndTargetActorIdOrExpiresAtGreaterThanAndSenderRefAndSenderActorIdAndTargetRefAndTargetActorIdOrderByCreatedAtDescMessageIdDesc(
            Instant expires1, String sender1, String senderActor1, String target1, String targetActor1, Instant expires2, String sender2, String senderActor2,
            String target2, String targetActor2, Limit limit);

    default List<Message> findBetween(Instant now, String first, String second, int limit) {
        return findByExpiresAtGreaterThanAndSenderRefAndSenderActorIdAndTargetRefAndTargetActorIdOrExpiresAtGreaterThanAndSenderRefAndSenderActorIdAndTargetRefAndTargetActorIdOrderByCreatedAtDescMessageIdDesc(
                now, first, "", second, "", now, second, "", first, "", Limit.of(limit));
    }

    /** Messages nobody acknowledged that lapsed at or before {@code at}. */
    List<Message> findByAcknowledgedAtIsNullAndExpiresAtLessThanEqual(Instant at);

    /**
     * The recall: removes the message only while no recipient has taken it, as one statement, so a claim that got there first (it set {@code claimed_at}) leaves nothing
     * to remove and a claim that comes later finds no row. Returns 1 when it went.
     */
    @Modifying
    @Query("DELETE FROM sennard.messages WHERE message_id = :messageId AND sender_ref = :senderRef AND sender_actor_id = :senderActorId AND claimed_at IS NULL "
            + "AND acknowledged_at IS NULL AND expires_at > :now")
    int deleteUnclaimed(@Param("messageId") String messageId, @Param("senderRef") String senderRef, @Param("senderActorId") String senderActorId, @Param("now") Instant now);

    /** Removes an unacknowledged message that lapsed, when it is still the version that was read (an acknowledgement that came first wins); returns 1 when it went. */
    @Modifying
    @Query("DELETE FROM sennard.messages WHERE message_id = :messageId AND version = :version AND acknowledged_at IS NULL")
    int deleteLapsed(@Param("messageId") String messageId, @Param("version") long version);

    /**
     * Removes the acknowledged rows that are done with: acknowledged for longer than {@code ackedBefore}, or lapsed with no live receipt behind them. Returns how many went.
     * A row nobody can claim, acknowledge or recall any more, so no race with the writers of the queue.
     */
    @Modifying
    @Query("DELETE FROM sennard.messages WHERE acknowledged_at IS NOT NULL AND (acknowledged_at <= :ackedBefore "
            + "OR (expires_at <= :now AND NOT EXISTS (SELECT 1 FROM sennard.prepared_messages p WHERE p.message_id = messages.message_id AND p.expires_at > :now)))")
    int deleteFinished(@Param("ackedBefore") Instant ackedBefore, @Param("now") Instant now);

    /** Empties the bodies of acknowledged messages that lapsed (the text is no longer needed, only the facts); returns how many were emptied. */
    @Modifying
    @Query("UPDATE sennard.messages SET body = '', body_bytes = 0, version = version + 1 WHERE acknowledged_at IS NOT NULL "
            + "AND expires_at <= :now AND body_bytes > 0")
    int emptyFinishedBodies(@Param("now") Instant now);
}
