// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.util.Map;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.mcp.HostCallerAdapter;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession.Execution;

/** Codex, class A: every tool call's {@code _meta} names the root session, the calling thread and the turn's model. */
@Component
public final class CodexCallerAdapter implements HostCallerAdapter {
    private static final String TURN_METADATA = "x-codex-turn-metadata";

    @Override
    public HostId host() {
        return CodexHost.ID;
    }

    @Override
    public CallerFacts read(Map<String, Object> meta) {
        String sessionId = textOrNull(meta.get("sessionId"));
        String threadId = textOrNull(meta.get("threadId"));
        String callId = textOrNull(meta.get("callId"));
        if (sessionId == null || threadId == null || callId == null || !(meta.get(TURN_METADATA) instanceof Map<?, ?> turn)) {
            return new CallerFacts.Unavailable("CODEX_CALL_META_MISSING");
        }
        if (!sessionId.equals(turn.get("session_id")) || !threadId.equals(turn.get("thread_id"))) {
            throw invalid("CODEX_CALL_META_INVALID");
        }
        String model = textOrNull(turn.get("model"));
        Execution execution = model == null ? new Execution.Unobserved("CODEX_EXECUTION_META_MISSING")
                : new Execution.Observed(model, textOrNull(turn.get("reasoning_effort")), textOrNull(turn.get("turn_id")), Execution.Observed.Evidence.HOST_REPORT);
        Object parent = turn.get("parent_thread_id");
        if (parent == null) {
            if (turn.get("subagent_kind") != null) {
                throw invalid("CODEX_CALL_META_INVALID");
            }
            if (!threadId.equals(sessionId)) {
                throw invalid("CODEX_ROOT_THREAD_MISMATCH");
            }
            return new CallerFacts.Observed(sessionId, sessionId, SessionActor.root(Basis.OBSERVED, "CODEX_MCP_META_ROOT"), null, callId, execution);
        }
        String parentThread = textOrNull(parent);
        if (parentThread == null || threadId.equals(sessionId) || parentThread.equals(threadId)) {
            throw invalid("CODEX_CALL_META_INVALID");
        }
        return new CallerFacts.Observed(sessionId, sessionId, SessionActor.child(threadId, "CODEX_MCP_META_CHILD_THREAD"), parentThread, callId, execution);
    }

    @Override
    public HookAdmission admission(HostEvent.ToolRequested request) {
        return new HookAdmission.None();
    }

    /** The text of a meta value, or null when it is not a non-blank text. */
    private static String textOrNull(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static BindingException invalid(String reasonCode) {
        return new BindingException(BindingCode.BINDING_INVALID, Map.of(), SennardException.args("reasonCode", reasonCode));
    }
}
