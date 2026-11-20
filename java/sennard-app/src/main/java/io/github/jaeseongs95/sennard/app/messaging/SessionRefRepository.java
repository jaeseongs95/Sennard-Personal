// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.messaging;

import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The session counters of session messaging, in the identity area's table {@code sennard.session_ref} (identity owns the table). A counter changes by a
 * conditional UPDATE that holds the limit in its WHERE and is judged by the number of rows it changed: 1 means the limit held and the counter moved, 0 means the limit was
 * reached. No read-then-write, so two senders cannot both pass the last free place of one session.
 */
public interface SessionRefRepository extends Repository<SessionRef, String> {
    @Query("SELECT session_ref, host, sender_drafts, sender_receipts, pending_count FROM sennard.session_ref WHERE session_ref = :sessionRef")
    Optional<SessionRef> findBySessionRef(@Param("sessionRef") String sessionRef);

    /** The host the session is stored under, or null when there is no such session. */
    @Query("SELECT host FROM sennard.session_ref WHERE session_ref = :sessionRef")
    String findHost(@Param("sessionRef") String sessionRef);

    /** Whether there is this session. */
    boolean existsBySessionRef(String sessionRef);

    /** One more draft of the sender, while it has fewer than {@code limit}. */
    @Modifying
    @Query("UPDATE sennard.session_ref SET sender_drafts = sender_drafts + 1 WHERE session_ref = :sessionRef AND sender_drafts < :limit")
    int addSenderDraft(@Param("sessionRef") String sessionRef, @Param("limit") int limit);

    /** The sender's draft becomes a receipt, while it has fewer than {@code limit} receipts. */
    @Modifying
    @Query("UPDATE sennard.session_ref SET sender_receipts = sender_receipts + 1, sender_drafts = sender_drafts - 1 "
            + "WHERE session_ref = :sessionRef AND sender_receipts < :limit")
    int senderDraftBecomesReceipt(@Param("sessionRef") String sessionRef, @Param("limit") int limit);

    /**
     * A message enters the recipient's queue. The recipient's row is written by this increment and stays written until the transaction ends, so the senders to one recipient
     * make their queue numbers ({@code seq}) one after the other: the number is read after this statement (see {@link MessageRepository#highestSeq}).
     */
    @Modifying
    @Query("UPDATE sennard.session_ref SET pending_count = pending_count + 1 WHERE session_ref = :sessionRef")
    int addPending(@Param("sessionRef") String sessionRef);

    /**
     * What left the session's queue (acknowledged, recalled, lapsed messages: {@code pending}) and what lapsed of the drafts and receipts it made as a sender, given back
     * in one statement, so a session's row is written once however many of them there are.
     */
    @Modifying
    @Query("UPDATE sennard.session_ref SET pending_count = pending_count - :pending, sender_drafts = sender_drafts - :drafts, sender_receipts = sender_receipts - :receipts "
            + "WHERE session_ref = :sessionRef")
    int release(@Param("sessionRef") String sessionRef, @Param("pending") long pending, @Param("drafts") long drafts, @Param("receipts") long receipts);
}
