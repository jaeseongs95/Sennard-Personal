// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.messaging;

import java.time.Instant;

/** The sender's view of one message; the fields that do not apply to the {@code state} are null. */
public record MessageStatus(String messageId, MessageState state, String targetRef, String targetHost, Instant createdAt, Instant expiresAt, Instant claimedAt, Instant acknowledgedAt,
        Long deliveryAttempts, Instant firstDeliveredAt, Instant preparedAt, DeliveryState deliveryState, Instant receiptExpiresAt, Instant recalledAt, String targetActorId) {
}
