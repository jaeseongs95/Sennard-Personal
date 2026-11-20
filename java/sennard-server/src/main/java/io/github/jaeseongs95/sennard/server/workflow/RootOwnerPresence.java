// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.time.Clock;
import java.util.List;

/** Whether the sessions that own a root are online, read when asked and never decided on a guess. */
public interface RootOwnerPresence {

    /** One owner session as the session board shows it. */
    record OwnerSession(String host, String sessionId, String actorId, PresenceState state, String lastSeenAt, String endedAt) {
    }

    /** {@code unavailable} names why the presence could not be read, in which case the state is {@link PresenceState#UNKNOWN}. */
    record View(PresenceState state, boolean self, boolean ownerBound, List<OwnerSession> sessions, String unavailable, String observedAt) {
        static View notRead(String reason, Clock clock) {
            return new View(PresenceState.UNKNOWN, false, false, List.of(), reason, clock.instant().toString());
        }
    }

    /** @param call the calling session, which is never counted as an owner; null for a reader that is no session (the admin page) */
    View read(String rootId, WorkflowCall call);

    /** The presence as a reader that is no session sees it: every owner session counts. */
    default View read(String rootId) {
        return read(rootId, null);
    }
}
