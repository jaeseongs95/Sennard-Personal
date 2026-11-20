// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.time.Clock;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore;
import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;

/**
 * Which root session a child belongs to, for a host whose child runs its hooks under its own conversation id. The role binding is the root's, and a hook of the
 * child carries only the child's id, so the id the hook reads the binding by is the root's once a tool call of the child has named both. It is a service of the use case
 * "link a child to its root": the one method is the transaction, and the store it calls only joins it.
 */
@Service
public class ChildSessionRoots {
    private final ContextLayerStore store;
    private final Clock clock;
    private final SessionRegistry sessions;

    public ChildSessionRoots(ContextLayerStore store, Clock clock, SessionRegistry sessions) {
        this.store = store;
        this.clock = clock;
        this.sessions = sessions;
    }

    /** Records the child's observed root and tells whether it agrees with the stored link. */
    @Transactional
    public boolean link(String host, String childSessionId, String rootSessionId) {
        return store.linkRoot(host, childSessionId, rootSessionId, StoreValues.iso(clock.millis()));
    }

    @Transactional(readOnly = true)
    public Optional<String> rootOf(String host, String childSessionId) {
        var managed = sessions.managedRootOf(host, childSessionId);
        if (managed.isPresent()) {
            return sessions.liveManagedRootSession(host, managed.orElseThrow());
        }
        return Optional.ofNullable(store.rootOf(host, childSessionId));
    }
}
