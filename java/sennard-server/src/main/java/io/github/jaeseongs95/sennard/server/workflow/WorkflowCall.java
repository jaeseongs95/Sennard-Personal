// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;

/**
 * The calling session as the workflow rules see it, derived once per call from the server-resolved {@link SelfSession}. Nothing here comes
 * from tool arguments.
 *
 * @param sessionId the session whose continuity the call belongs to; a child works in its root session's task
 * @param actorId the persisted form of who called: no raw session or agent id
 * @param observedCaller the recorded form of the caller; never null for a resolved session
 * @param locale the language of texts rendered for this reader
 */
public record WorkflowCall(String host, String sessionId, String actorId, ObservedCaller observedCaller, SelfSession.Execution execution, String callId, String locale) {

    public static WorkflowCall of(SelfSession session, String locale) {
        SessionActor actor = session.actor();
        String agentKey = actor.agentKey();
        boolean child = actor.role() == HostContext.Role.CHILD && actor.basis() == HostContext.Basis.OBSERVED
                && agentKey != null && !agentKey.isBlank();
        if (session.provenance() != SelfSession.Provenance.HOST_OBSERVED || (!actor.rootEstablished() && !child)) {
            throw new BindingException(BindingCode.BINDING_REQUIRED);
        }

        String sessionId = session.hostSessionId() != null ? session.hostSessionId() : session.sessionRef();
        String host = session.host();
        String root = actorId(host, sessionId, null);
        String parent = session.parentAgentKey();
        List<String> chain = new ArrayList<>();
        if (child) {
            chain.add(root);
            if (parent != null && !Objects.equals(parent, sessionId)) {
                chain.add(actorId(host, sessionId, parent));
            }
        }
        // Only a direct child of the root has its whole chain in one call; deeper links are not in this call's metadata.
        boolean chainComplete = !child || Objects.equals(sessionId, parent);
        String id = child ? actorId(host, sessionId, agentKey) : root;
        // The root may record on behalf of an agent the host never showed, so its work counts as unobserved.
        ObservedCaller observed = new ObservedCaller(id, List.copyOf(chain), child && chainComplete ? Provenance.HOST_OBSERVED : Provenance.UNAVAILABLE);
        return new WorkflowCall(host, sessionId, id, observed, session.execution(), session.callId(), locale);
    }

    WorkflowCall withExecution(SelfSession.Execution observedExecution) {
        return new WorkflowCall(host, sessionId, actorId, observedCaller, observedExecution, callId, locale);
    }

    /**
     * The calling session as the author of what it makes and keeps (an attachment): the root's actor id, so the agents of a session are one author, and the persisted form holds no raw
     * session id.
     */
    public static String sessionActor(SelfSession session) {
        String sessionId = session.hostSessionId() != null ? session.hostSessionId() : session.sessionRef();
        return actorId(session.host(), sessionId, null);
    }

    /** Raw host session IDs do not enter persisted workflow actors or signed payloads. */
    public static String actorId(String host, String sessionId, String agentId) {
        return host + ":session-" + digest(sessionId) + (agentId != null && !agentId.isEmpty() ? ":agent-" + digest(agentId) : "");
    }

    private static String digest(String value) {
        return Digests.sha256Hex(value.getBytes(StandardCharsets.UTF_8)).substring(0, 24);
    }
}
