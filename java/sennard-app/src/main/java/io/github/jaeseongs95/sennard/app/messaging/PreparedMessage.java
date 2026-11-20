// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.messaging;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import io.github.jaeseongs95.sennard.domain.messaging.MessageState;
import io.github.jaeseongs95.sennard.domain.messaging.PeerAddress;

/**
 * One row of {@code sennard.prepared_messages}: a message the sender prepared, and after the send its receipt. It is the sender's own record of one
 * message and what makes a message ID the server's (every queued message has one, so the ID is never the caller's).
 *
 * <p>{@code state} is the one stored state of the message and holds the constant's name ({@link MessageState#PREPARED}, {@link MessageState#SUBMITTED},
 * {@link MessageState#RECALLED}; Spring Data JDBC's default enum mapping, no converter), so the reading code is a complete switch and not a test of which columns are
 * null. The database keeps the facts that belong to each state in a CHECK: a draft has a body and nothing else, a receipt has no body and has the send time and the
 * message's expiry, a recalled one has also the time it was taken back. Who is queued, delivered or acknowledged is not stored here: that is read from the queue row's
 * own times ({@link Message}), which a claim and an acknowledgement write anyway, so a second column would only repeat them.
 *
 * <p>The times are {@code Instant}s kept in {@code TIMESTAMP} columns as UTC ({@code UtcTimestampColumns} converts at the database boundary); {@code expiresAt} is when this record goes: the draft's expiry,
 * and after the send the receipt's. The aggregate is immutable; a change is a new record saved with {@code save}, and {@code version} makes two writers of one row fail
 * with an optimistic-lock failure instead of overwriting each other.
 */
@Table(schema = "sennard", value = "prepared_messages")
public record PreparedMessage(@Id String messageId, String senderRef, String senderActorId, String targetRef, String targetActorId, String body, long ttlSeconds,
        Instant preparedAt, Instant expiresAt, MessageState state, Instant sentAt, Instant messageExpiresAt, Instant recalledAt, long recordBytes,
        @Version Long version) {

    /** A draft: the body is kept until the send. */
    public static PreparedMessage draft(String messageId, String senderRef, String targetRef, String body, long ttlSeconds, Instant preparedAt, Instant expiresAt, long recordBytes) {
        return draft(messageId, PeerAddress.session(senderRef), PeerAddress.session(targetRef), body, ttlSeconds, preparedAt, expiresAt, recordBytes);
    }

    public static PreparedMessage draft(String messageId, PeerAddress sender, PeerAddress target, String body, long ttlSeconds, Instant preparedAt, Instant expiresAt, long recordBytes) {
        return new PreparedMessage(messageId, sender.sessionRef(), sender.actorId(), target.sessionRef(), target.actorId(), body, ttlSeconds, preparedAt, expiresAt, MessageState.PREPARED,
                null, null, null, recordBytes, null);
    }

    /** The receipt of the send: the body goes (the queue row has it), the send time and the message's expiry are kept, and the record lives until {@code expiresAt}. */
    public PreparedMessage submitted(Instant sentAt, Instant messageExpiresAt, Instant expiresAt, long recordBytes) {
        return new PreparedMessage(messageId, senderRef, senderActorId, targetRef, targetActorId, null, ttlSeconds, preparedAt, expiresAt, MessageState.SUBMITTED,
                sentAt, messageExpiresAt, null, recordBytes, version);
    }

    PeerAddress sender() {
        return new PeerAddress(senderRef, senderActorId);
    }

    PeerAddress target() {
        return new PeerAddress(targetRef, targetActorId);
    }
}
