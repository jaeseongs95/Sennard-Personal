// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.util.Map;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Role;
import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.mcp.HostCallerAdapter;

/** Claude Code, class C: {@code _meta} carries only the tool-use id, and the PreToolUse hook recorded the rest under that id. */
@Component
public final class ClaudeCallerAdapter implements HostCallerAdapter {
    private static final String TOOL_USE_ID = "claudecode/toolUseId";

    @Override
    public HostId host() {
        return ClaudeHost.ID;
    }

    @Override
    public boolean acceptsInferredManagedRoot() {
        // The matched PreToolUse row distinguishes a root by the absence of the host's agent_id.
        return true;
    }

    @Override
    public CallerFacts read(Map<String, Object> meta) {
        return meta.get(TOOL_USE_ID) instanceof String id && !id.isBlank()
                ? new CallerFacts.ClaimByCall(id, ClaudeCallerAdapter::actorOf)
                : new CallerFacts.Unavailable("CLAUDE_TOOL_USE_ID_MISSING");
    }

    /** @param agentId the agent id the PreToolUse row holds, null for a row that holds none (the root's) */
    private static SessionActor actorOf(String agentId) {
        return agentId == null ? SessionActor.root(Basis.INFERRED, "CLAUDE_NATIVE_ROOT_AGENT_ID_ABSENT") : SessionActor.child(agentId, "CLAUDE_NATIVE_CHILD_AGENT_ID");
    }

    /** A present but malformed agent marker must not leave a row that later reads as the root's. */
    @Override
    public HookAdmission admission(HostEvent.ToolRequested request) {
        if (request.context().actor().role() == Role.INDETERMINATE) {
            return new HookAdmission.Refused("HOOK_AGENT_ID_INVALID");
        }
        String callId = request.tool().callId();
        return callId == null ? new HookAdmission.Refused("HOOK_CALL_FIELDS_MISSING") : new HookAdmission.Row(callId);
    }
}
