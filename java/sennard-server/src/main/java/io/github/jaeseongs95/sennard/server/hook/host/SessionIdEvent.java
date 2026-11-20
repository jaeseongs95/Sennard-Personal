// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.util.Map;
import java.util.Optional;

/**
 * The events of the hook wire format that Claude Code and Codex share, by the name the host gives them. {@link #PRE_MODEL_REQUEST} and
 * {@link #POST_MODEL_REQUEST} are Codex's own (the host's model-request events, which Claude Code does not have); {@link #POST_MODEL_SWITCH} is Claude Code's,
 * and the shared adapter has always taken it from either host. Which of them an adapter takes is its {@code takes}.
 */
enum SessionIdEvent implements HostEventName {
    SESSION_START("SessionStart"), SESSION_END("SessionEnd"), USER_PROMPT_SUBMIT("UserPromptSubmit"), PRE_TOOL_USE("PreToolUse"), POST_TOOL_USE("PostToolUse"),
    POST_TOOL_USE_FAILURE("PostToolUseFailure"), STOP("Stop"), SUBAGENT_START("SubagentStart"), SUBAGENT_STOP("SubagentStop"), PRE_COMPACT("PreCompact"),
    POST_COMPACT("PostCompact"), POST_MODEL_SWITCH("PostModelSwitch"), PRE_MODEL_REQUEST("PreModelRequest"), POST_MODEL_REQUEST("PostModelRequest");

    private static final Map<String, SessionIdEvent> BY_NAME = Map.copyOf(HostEventName.index(SessionIdEvent.class));

    private final String hostName;

    SessionIdEvent(String hostName) {
        this.hostName = hostName;
    }

    @Override
    public String hostName() {
        return hostName;
    }

    static Optional<SessionIdEvent> named(String hostName) {
        return Optional.ofNullable(BY_NAME.get(hostName));
    }

    /** The events Codex reports for each model call of a turn. */
    boolean isModelRequest() {
        return this == PRE_MODEL_REQUEST || this == POST_MODEL_REQUEST;
    }
}
