// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;

/**
 * Reads whether a root's owner sessions are online from the session registry: a live generation that an owning client holds, and whose
 * session bound this root. The caller's own session is not its owner here; a reader that is no session (the admin page) has none to leave out.
 *
 * <p>ceiling: the registry keeps only live generations for this read, so an owner that has ended and one never seen both read {@code unknown};
 * the rule that matters (online refuses, anything else needs the user's decision) does not depend on telling them apart.
 */
@Component
class SessionRootOwnerPresence implements RootOwnerPresence {
    private static final Logger LOG = LoggerFactory.getLogger(SessionRootOwnerPresence.class);

    private final SessionRegistry registry;
    private final ContinuityStore continuityStore;
    private final WorkflowContinuity continuity;
    private final Clock clock;

    SessionRootOwnerPresence(SessionRegistry registry, ContinuityStore continuityStore, WorkflowContinuity continuity, Clock clock) {
        this.clock = clock;
        this.registry = registry;
        this.continuityStore = continuityStore;
        this.continuity = continuity;
    }

    @Override
    public View read(String rootId, WorkflowCall call) {
        String observedAt = clock.instant().toString();
        try {
            boolean ownerBound = continuityStore.rootBound(rootId);
            List<OwnerSession> sessions = new ArrayList<>();
            if (ownerBound) {
                for (SessionRegistry.OwnedGeneration live : registry.activeOwned()) {
                    boolean self = call != null && Objects.equals(live.host(), call.host()) && Objects.equals(live.hostSessionId(), call.sessionId());
                    if (!self && continuityStore.ownsRoot(continuity.correlate(live.hostSessionId()), rootId)) {
                        sessions.add(new OwnerSession(live.host(), live.hostSessionId(), WorkflowCall.actorId(live.host(), live.hostSessionId(), null), PresenceState.ONLINE, null, null));
                    }
                }
            }
            return new View(sessions.isEmpty() ? PresenceState.UNKNOWN : PresenceState.ONLINE, false, ownerBound, sessions, null, observedAt);
        } catch (DataAccessException unreadable) {
            LOG.warn("The presence of the owner of root {} could not be read", rootId, unreadable);
            return new View(PresenceState.UNKNOWN, false, false, List.of(), "PRESENCE_UNAVAILABLE", observedAt);
        }
    }
}
