// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.prompt;

import java.util.Optional;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.prompt.ArchivedSession;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;

/**
 * The key a prompt of the calling session is archived under: the host's name for itself and the host's own session id, which is what the hooks stored it by
 * ({@link RootSessions}). A call from a session the host named (its {@code _meta} carries the host's session id) has it in hand. A call that only holds the session reference
 * (a host that cannot attach its session to a call, Gemini CLI among them) has the reference of a session the server registered from that session's hooks, and the registry
 * says which host session it stands for ({@link SessionRegistry#identityOf}); using the reference itself as the id would look for prompts under a key no hook ever wrote.
 * A session the registry knows by no host session id (one the server minted for {@code open_session}) has no hooks, so nothing of it is archived and the answer says so,
 * instead of finding nothing.
 */
@Component
class ArchiveSessionKeys {
    private final SessionRegistry registry;

    ArchiveSessionKeys(SessionRegistry registry) {
        this.registry = registry;
    }

    /** @throws InvalidInputException {@link InputCode#PROMPT_SESSION_UNHOOKED} when the session is not one the hooks observe */
    ArchivedSession of(SelfSession session) {
        if (session.hostSessionId() != null) {
            return new ArchivedSession(session.host(), session.hostSessionId());
        }
        return registry.identityOf(session.sessionRef()).flatMap(identity -> identity.hostSession().map(id -> new ArchivedSession(identity.host(), id)))
                .orElseThrow(() -> new InvalidInputException(InputCode.PROMPT_SESSION_UNHOOKED));
    }
}
