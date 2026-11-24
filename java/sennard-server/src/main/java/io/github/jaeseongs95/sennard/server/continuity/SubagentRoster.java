// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import java.time.Clock;
import java.util.Collection;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.server.context.ChildSessionRoots;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Lead;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Role;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts.NativeSend;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore.Task;
import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;

/**
 * The subagents a session started, by the addresses a later message can name them by (the id the host gave one, the name it was started with), as the hooks showed them: a
 * subagent's start, and a finished tool call that started one. A message from an agent of the session to such an address stays inside the session, so it is no peer message; any other
 * recipient is not known to be one, whatever its text looks like. Each fact is a row of the session's continuity observations, kept for every epoch of the task (a subagent outlives
 * the request that started it). Each public method is one use case and one transaction.
 */
@Service
public class SubagentRoster {
    private static final String KNOWN = "subagent-known";

    private final ContinuityStore store;
    private final ChildSessionRoots childRoots;
    private final SessionRegistry sessions;
    private final Clock clock;

    public SubagentRoster(ContinuityStore store, ChildSessionRoots childRoots, SessionRegistry sessions, Clock clock) {
        this.store = store;
        this.childRoots = childRoots;
        this.sessions = sessions;
        this.clock = clock;
    }

    /** The session has started the subagents these addresses name. Already known addresses are left as they are. */
    @Transactional
    public void record(HostContext context, Collection<String> addresses) {
        String now = StoreValues.iso(clock.millis());
        Task task = store.ensureTask(correlate(context), now);
        for (String address : addresses) {
            store.recordObservationOnce(task.taskCorrelation(), task.currentEpoch(), KNOWN, hash(context, address), true, now);
        }
    }

    public enum NativeRoute {
        SAME_SESSION, FROM_MAIN, UNVERIFIED
    }

    /** The host supplies the actor and target format. The server checks both against their recorded session. */
    @Transactional(readOnly = true)
    public NativeRoute nativeRoute(HostContext context, NativeSend send) {
        String host = context.hostLabel().sessionHost();
        var actor = context.actor();
        var membership = sessions.treeMembership(host, context.sessionId())
                .filter(member -> sessions.liveMainGeneration(member.rootRef()).isPresent());
        if (membership.isEmpty()) {
            return NativeRoute.UNVERIFIED;
        }
        var sender = membership.orElseThrow();
        boolean main = actor.rootEstablished() && actor.lead() == Lead.MAIN && sender.depth() == 0;
        boolean child = actor.role() == Role.CHILD && actor.basis() == Basis.OBSERVED
                && (sender.depth() > 0 ? context.sessionId().equals(actor.agentKey())
                        : contains(context, sender.rootSessionId(), actor.agentKey()));
        if (!main && !child) {
            return NativeRoute.UNVERIFIED;
        }
        if (send.toParent() ? child : send.recipient() != null && sameSessionTarget(context, sender.rootSessionId(), send.recipient(), child)) {
            return NativeRoute.SAME_SESSION;
        }
        return main ? NativeRoute.FROM_MAIN : NativeRoute.UNVERIFIED;
    }

    private boolean sameSessionTarget(HostContext context, String root, String address, boolean child) {
        String host = context.hostLabel().sessionHost();
        if (sessions.findRef(host, address).isPresent()) {
            return sessions.treeMembership(host, address).filter(target -> root.equals(target.rootSessionId()) && (child || target.depth() > 0)).isPresent();
        }
        return contains(context, root, address);
    }

    private boolean contains(HostContext context, String root, String address) {
        Task task = store.readTask(store.opaqueDigest("session", root));
        return task != null && address != null && store.hasObservationAnyEpochAfter(task.taskCorrelation(), KNOWN, hash(context, address), 0L);
    }

    /** The session a hook counts in: the root's for a child that runs its hooks under a conversation of its own, otherwise the hook's session. */
    private String correlate(HostContext context) {
        String conversation = context.sessionId();
        boolean ownConversation = context.actor().role() == Role.CHILD && context.actor().basis() == Basis.OBSERVED && conversation.equals(context.actor().agentKey());
        String session = ownConversation ? childRoots.rootOf(context.hostLabel().sessionHost(), conversation).orElse(conversation) : conversation;
        return store.opaqueDigest("session", session);
    }

    private String hash(HostContext context, String address) {
        return store.opaqueDigest("subagent", context.hostLabel().sessionHost() + ":" + address);
    }
}
