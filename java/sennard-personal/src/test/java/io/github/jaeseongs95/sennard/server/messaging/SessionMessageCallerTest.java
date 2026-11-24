// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.domain.messaging.PeerAddress;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession.Provenance;

class SessionMessageCallerTest {
    @Test
    void aNamedSessionHasNoPeerActor() {
        var named = session(Provenance.UNAVAILABLE, SessionActor.indeterminate("CALLER_NOT_OBSERVED"));
        assertEquals(BindingCode.BINDING_REQUIRED, assertThrows(BindingException.class, () -> SessionMessageTools.caller(named)).code());
    }

    @Test
    void aProvisionalHostActorHasNoPeerAddress() {
        var provisional = session(Provenance.HOST_OBSERVED, SessionActor.root(Basis.PROVISIONAL, "AWAITING_HOST_OBSERVATION"));
        assertEquals(BindingCode.BINDING_REQUIRED, assertThrows(BindingException.class, () -> SessionMessageTools.caller(provisional)).code());
    }

    @Test
    void onlyTheObservedRootHasAPeerAddress() {
        var root = session(Provenance.HOST_OBSERVED, SessionActor.root(Basis.OBSERVED, "ROOT"));
        var child = session(Provenance.HOST_OBSERVED, SessionActor.child("child", "CHILD"));
        assertEquals(PeerAddress.session(root.sessionRef()), SessionMessageTools.caller(root));
        assertEquals(BindingCode.PEER_MAIN_SESSION_REQUIRED, assertThrows(BindingException.class, () -> SessionMessageTools.caller(child)).code());
    }

    private static SelfSession session(Provenance provenance, SessionActor actor) {
        return new SelfSession("session-ref", 1, "host", "session", provenance, actor, null, null,
                new SelfSession.Execution.Unobserved("NO_EXECUTION_FACTS"), null);
    }
}
