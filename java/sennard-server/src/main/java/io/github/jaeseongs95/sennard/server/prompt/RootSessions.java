// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.prompt;

import java.util.Optional;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.hook.HostContext;

/**
 * Which session a hook of the prompt archive is about: the main agent's, under the name the session list stores its host by. A child agent's hook (and one whose actor the
 * host could not tell) is no session of its own and archives nothing; the child's turn is part of the root's.
 */
@Component
public class RootSessions {
    /** The session as the archive keys it: the host's name for itself, as the session list stores it (the host's {@code sessionHost}, not the hook's label), and the host's session id. */
    public record RootSession(String host, String sessionId) {
    }

    /** The session of a main agent's hook; nothing for a child or an actor that could not be told. */
    public Optional<RootSession> of(HostContext context) {
        if (!context.actor().rootEstablished()) {
            return Optional.empty();
        }
        return Optional.of(new RootSession(context.hostLabel().sessionHost(), context.sessionId()));
    }
}
