// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession.Provenance;

class WorkflowCallTest {
    private static final String HOST = "fixture";
    private static final String ROOT = "root";
    private static final SelfSession.Execution EXECUTION = new SelfSession.Execution.Unobserved("MODEL_NOT_REPORTED");

    @Test
    void unresolvedActorsAreRefused() {
        assertAll("Unresolved actors cannot enter a workflow",
                Stream.of(
                        session(Provenance.UNAVAILABLE, SessionActor.root(Basis.OBSERVED, "UNBOUND"), null),
                        session(Provenance.HOST_OBSERVED, SessionActor.indeterminate("ACTOR_UNKNOWN"), null),
                        session(Provenance.HOST_OBSERVED, SessionActor.root(Basis.PROVISIONAL, "ACTOR_UNCORRELATED"), null),
                        session(Provenance.HOST_OBSERVED, SessionActor.child(" ", "AGENT_KEY_INVALID"), ROOT))
                        .map(caller -> () -> {
                            var refusal = assertThrows(BindingException.class, () -> WorkflowCall.of(caller, "en"), caller.actor().reasonCode());
                            assertEquals(BindingCode.BINDING_REQUIRED, refusal.code());
                        }));
    }

    @Test
    void establishedActorsKeepTheirIdentityAndLineageWithNoModelObservation() {
        var rootId = WorkflowCall.actorId(HOST, ROOT, null);
        for (var basis : List.of(Basis.OBSERVED, Basis.INFERRED)) {
            var root = WorkflowCall.of(session(Provenance.HOST_OBSERVED, SessionActor.root(basis, "ROOT"), null), "en");
            assertEquals(rootId, root.actorId());
            assertEquals(List.of(), root.observedCaller().parentChain());
            assertEquals(EXECUTION, root.execution());
        }

        var child = WorkflowCall.of(session(Provenance.HOST_OBSERVED, SessionActor.child("child", "CHILD"), ROOT), "en");
        assertEquals(WorkflowCall.actorId(HOST, ROOT, "child"), child.actorId());
        assertEquals(List.of(rootId), child.observedCaller().parentChain());
        assertTrue(child.observedCaller().hostObserved());
        assertEquals(EXECUTION, child.execution());
    }

    private static SelfSession session(Provenance provenance, SessionActor actor, String parent) {
        return new SelfSession("session-ref", 1, HOST, ROOT, provenance, actor, parent, "call", EXECUTION, null);
    }
}
