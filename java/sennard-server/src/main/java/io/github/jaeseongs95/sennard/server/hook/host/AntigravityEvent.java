// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.util.Map;
import java.util.Optional;

/** The events Antigravity's hooks are registered under (antigravity.google/docs/hooks), by the name the host gives them. */
enum AntigravityEvent implements HostEventName {
    SESSION_START("SessionStart"), PRE_INVOCATION("PreInvocation"), POST_INVOCATION("PostInvocation"), PRE_TOOL_USE("PreToolUse"), POST_TOOL_USE("PostToolUse"),
    STOP("Stop");

    private static final Map<String, AntigravityEvent> BY_NAME = Map.copyOf(HostEventName.index(AntigravityEvent.class));

    private final String hostName;

    AntigravityEvent(String hostName) {
        this.hostName = hostName;
    }

    @Override
    public String hostName() {
        return hostName;
    }

    static Optional<AntigravityEvent> named(String hostName) {
        return Optional.ofNullable(BY_NAME.get(hostName));
    }
}
