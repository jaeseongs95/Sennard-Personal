// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.messaging;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/** The doorbell, never the message body, a delivery receipt or an acknowledgement. One outstanding attempt per target across callers and server restarts. */
@Table(schema = "sennard", value = "native_wake_reservations")
public record WakeReservation(@Id String sessionRef, long generation, String nonce, String serverId, State state, Instant updatedAt, Instant retryAfter, @Version Long version) {
    public enum State {
        READY, RESERVED, STARTED, SUBMITTED, UNKNOWN, OBSERVED, NOT_SENT, CANCELLED
    }
}
