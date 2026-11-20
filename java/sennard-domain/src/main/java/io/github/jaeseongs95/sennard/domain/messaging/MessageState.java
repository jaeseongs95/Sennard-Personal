// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.messaging;

/**
 * Where a session message stands in the sender's view, answered by {@code get_session_message_status} by the constant's name. A prepared draft is
 * {@code PREPARED}; once sent its receipt is {@code SUBMITTED}, or {@code RECALLED} after the sender took it back; the queue row of a sent message
 * is {@code QUEUED} until a recipient claims it ({@code DELIVERED}) and acknowledges it ({@code ACKNOWLEDGED}).
 *
 * <p>Only the first three are stored: {@code prepared_messages.state} holds the constant's name (Spring Data JDBC's default enum mapping) and a CHECK
 * constraint lists them. The other three are read from the queue row's own times (claimed, acknowledged), see {@code MessageRules.queueState}.
 */
public enum MessageState {
    PREPARED(false),
    SUBMITTED(false),
    RECALLED(false),
    QUEUED(false),
    DELIVERED(true),
    ACKNOWLEDGED(true);

    private final boolean received;

    MessageState(boolean received) {
        this.received = received;
    }

    /** A recipient has claimed the message, so it can no longer be recalled. */
    public boolean isReceived() {
        return received;
    }
}
