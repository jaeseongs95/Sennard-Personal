// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.messaging;

import java.time.Instant;

/**
 * A message a recipient took from its queue. {@code claimReceipt} is {@code <deliveryAttempt>:<UUID>}, issued for this claim only: the acknowledgement must
 * return it, and a later claim of the same message (the lease lapsed) has another.
 */
public record ClaimedMessage(String messageId, String senderRef, String senderHost, String recipientRef, String body, Instant createdAt, Instant expiresAt, long deliveryAttempt,
        Instant firstDeliveredAt, String claimReceipt, String senderActorId, String recipientActorId) {
    public ClaimedMessage(String messageId, String senderRef, String senderHost, String recipientRef, String body, Instant createdAt, Instant expiresAt, long deliveryAttempt,
            Instant firstDeliveredAt, String claimReceipt) {
        this(messageId, senderRef, senderHost, recipientRef, body, createdAt, expiresAt, deliveryAttempt, firstDeliveredAt, claimReceipt, "", "");
    }
}
