// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore;

/**
 * Resolves the session tree for hosts whose children run hooks under separate conversation IDs.
 */
@Service
public class ChildSessionRoots {
    private final ContextLayerStore store;
    private final SessionRegistry sessions;

    public ChildSessionRoots(ContextLayerStore store, SessionRegistry sessions) {
        this.store = store;
        this.sessions = sessions;
    }

    /** Stores the immediate edge after comparing its root with both current and historical observations. */
    @Transactional
    public boolean link(String host, String childSessionId, String parentSessionId, String rootSessionId) {
        if (parentSessionId == null || sessions.treeMembership(host, parentSessionId).filter(parent -> rootSessionId.equals(parent.rootSessionId())).isEmpty()) {
            return false;
        }
        String historicalRoot = store.rootOf(host, childSessionId);
        return (historicalRoot == null || historicalRoot.equals(rootSessionId)) && sessions.bindObservedChild(host, parentSessionId, childSessionId);
    }

    @Transactional(readOnly = true)
    public Optional<String> rootOf(String host, String childSessionId) {
        return sessions.treeMembership(host, childSessionId).filter(member -> member.depth() > 0).map(SessionRegistry.TreeMembership::rootSessionId);
    }
}
