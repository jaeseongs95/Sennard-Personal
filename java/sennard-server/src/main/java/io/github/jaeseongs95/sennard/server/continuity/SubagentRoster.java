// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import java.time.Clock;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.server.context.ChildSessionRoots;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Role;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore.Task;
import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;

/** Keeps observed subagent addresses across the turns of their delegation. */
@Service
public class SubagentRoster {
    private static final String KNOWN = "subagent-known";

    private final ContinuityStore store;
    private final ChildSessionRoots childRoots;
    private final Clock clock;

    public SubagentRoster(ContinuityStore store, ChildSessionRoots childRoots, Clock clock) {
        this.store = store;
        this.childRoots = childRoots;
        this.clock = clock;
    }

    @Transactional
    public void record(HostContext context, Set<String> addresses) {
        String now = StoreValues.iso(clock.millis());
        Task task = store.ensureTask(correlate(context), now);
        for (String address : addresses) {
            store.recordObservationOnce(task.taskCorrelation(), task.currentEpoch(), KNOWN, hash(context, address), true, now);
        }
    }

    @Transactional(readOnly = true)
    public boolean contains(HostContext context, String address) {
        Task task = store.readTask(correlate(context));
        return task != null && store.hasObservationAnyEpochAfter(task.taskCorrelation(), KNOWN, hash(context, address), 0L);
    }

    private String correlate(HostContext context) {
        String session = context.sessionId();
        var actor = context.actor();
        if (actor.role() == Role.CHILD && actor.basis() == Basis.OBSERVED && session.equals(actor.agentKey())) {
            session = childRoots.rootOf(context.hostLabel().sessionHost(), session).orElse(session);
        }
        return store.opaqueDigest("session", session);
    }

    private String hash(HostContext context, String address) {
        return store.opaqueDigest("subagent", context.hostLabel().sessionHost() + ":" + address);
    }
}
