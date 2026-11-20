// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.messaging;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import io.github.jaeseongs95.sennard.domain.messaging.PeerAddress;

/**
 * One row of {@code sennard.messages}: a message in its recipient's queue. The order is {@code seq}, per recipient, kept by a UNIQUE key and made in Java
 * (the recipient's highest plus one, read after the recipient's session row was written, so the senders to one recipient make their numbers one after the other). A claim
 * and an acknowledgement change the row by conditional UPDATEs ({@link MessageRepository#claim}, {@link MessageRepository#acknowledge}) that bump {@code version}, and the
 * number of rows they changed says who won: of two claimers of one row exactly one gets 1 (the claim's WHERE is "still claimable", so the loser's statement finds the
 * winner's lease).
 *
 * <p>There is no state column on purpose. Whether the message waits, was taken or was acknowledged is what {@code claimedAt} and {@code acknowledgedAt} already say,
 * and a claim whose lease lapsed makes it waiting again by the clock, not by a write: a stored state would be a second truth to keep equal to the times. The sender's view
 * (QUEUED, DELIVERED, ACKNOWLEDGED) is derived by {@code MessageRules.queueState}; the one stored state is the draft's and the receipt's ({@link PreparedMessage}).
 */
@Table(schema = "sennard", value = "messages")
public record Message(@Id String messageId, String senderRef, String senderActorId, String senderHost, String targetRef, String targetActorId, long seq, String body, long bodyBytes,
        Instant createdAt, Instant expiresAt, Instant claimedAt, Instant claimUntil, long deliveryAttempts, Instant firstDeliveredAt,
        Instant acknowledgedAt, String claimReceipt, String acknowledgementReceipt, @Version Long version) {

    /** A message that has just entered the queue. */
    public static Message queued(String messageId, PeerAddress sender, String senderHost, PeerAddress target, long seq, String body, long bodyBytes, Instant createdAt, Instant expiresAt) {
        return new Message(messageId, sender.sessionRef(), sender.actorId(), senderHost, target.sessionRef(), target.actorId(), seq, body, bodyBytes, createdAt, expiresAt,
                null, null, 0, null, null, null, null, null);
    }

    PeerAddress sender() {
        return new PeerAddress(senderRef, senderActorId);
    }

    PeerAddress target() {
        return new PeerAddress(targetRef, targetActorId);
    }
}
