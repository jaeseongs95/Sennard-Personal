// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The advisory {@code autoWake} outlook a message status carries: whether the recipient's idle wake can work now ({@link State}) and why
 * ({@link Reason}). The outlook contract and the server instructions spell the wire text, so each constant carries it ({@code @JsonValue}).
 * It is never delivery, completion or permission evidence.
 */
final class WakeOutlook {
    private WakeOutlook() {
    }

    /** Whether a wake can ring the recipient now. */
    enum State {
        AVAILABLE("available"),
        DEGRADED("degraded"),
        UNSUPPORTED("unsupported"),
        NO_LIVE_RELAY("no-live-relay");

        private final String wire;

        State(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }

    /** What the outlook rests on. */
    enum Reason {
        RELAY_LIVE("relay-live"),
        WAKE_IN_FLIGHT("wake-in-flight"),
        WAKE_UNANSWERED("wake-unanswered"),
        RECIPIENT_ACTIVE("recipient-active"),
        RETRY_BACKOFF("retry-backoff"),
        WAKE_FAILING("wake-failing"),
        WAKE_PREREQUISITE_MISSING("wake-prerequisite-missing"),
        NO_IDLE_WAKE("no-idle-wake"),
        PRESENCE_UNKNOWN("presence-unknown"),
        PRESENCE_NOT_ONLINE("presence-not-online"),
        RELAY_LEASE_MISSING("relay-lease-missing");

        private final String wire;

        Reason(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }
}
