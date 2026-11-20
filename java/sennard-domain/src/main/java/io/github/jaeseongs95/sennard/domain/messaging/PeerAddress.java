// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.messaging;

import java.util.Objects;

/** An address in the peer queue. The empty actor component is the original session address. */
public record PeerAddress(String sessionRef, String actorId) {
    public PeerAddress {
        Objects.requireNonNull(sessionRef, "sessionRef");
        Objects.requireNonNull(actorId, "actorId");
        if (sessionRef.isBlank() || (!actorId.isEmpty() && (actorId.isBlank() || actorId.length() > 200
                || actorId.chars().anyMatch(Character::isISOControl)))) {
            throw new IllegalArgumentException("Invalid peer address");
        }
    }

    public static PeerAddress session(String sessionRef) {
        return new PeerAddress(sessionRef, "");
    }
}
