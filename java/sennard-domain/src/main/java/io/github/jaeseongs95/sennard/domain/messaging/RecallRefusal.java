// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.messaging;

/** Why {@code recall_session_message} did not take a message back, answered by the constant's name; a recall that worked has none. */
public enum RecallRefusal {
    /** A recipient claimed the message first. */
    DELIVERED,
    /** The message passed its expiry unclaimed. */
    EXPIRED,
    /** The ID names a prepared draft that was never sent. */
    NOT_SENT,
    /** The ID is not one of this sender's, or its record is gone. */
    UNKNOWN
}
