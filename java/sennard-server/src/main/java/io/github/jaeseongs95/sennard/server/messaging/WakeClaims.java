// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.Boundary;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.Delivered;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;

/** Takes what a waiting session may receive: the envelope of the messages it just claimed, or nothing. */
@Component
public class WakeClaims {
    private final SessionRegistry sessions;
    private final SessionMessageService messages;
    private final PeerEnvelope envelope;

    public WakeClaims(SessionRegistry sessions, SessionMessageService messages, PeerEnvelope envelope) {
        this.sessions = sessions;
        this.messages = messages;
        this.envelope = envelope;
    }

    /** A session the server does not know yet has no messages: none can be addressed to it. */
    public Optional<String> claim(String host, String sessionId) {
        return sessions.findRef(host, sessionId).flatMap(ref -> {
            // One use case: the claim of a wake input also records, in its own transaction, that an input was seen on the session (its observation row is made there when it has none).
            List<Delivered> delivered = messages.claim(host, sessionId, ref, Boundary.WAKE);
            return delivered.isEmpty() ? Optional.<String>empty() : Optional.of(envelope.render(delivered));
        });
    }
}
