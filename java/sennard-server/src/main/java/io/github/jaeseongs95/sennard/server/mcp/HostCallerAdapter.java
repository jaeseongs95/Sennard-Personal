// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.mcp;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession.Execution;
import tools.jackson.databind.JsonNode;

/**
 * Everything one host does differently about saying who called. Each host is one bean of this type,
 * selected by the label its {@code Sennard-Host-Label} header and its hooks carry; a host with no bean is class D and needs nothing added.
 */
public interface HostCallerAdapter {
    /** The host this adapter answers for: its label is what the host's hooks and MCP requests carry, its session name what its sessions are stored under. */
    HostId host();

    /**
     * Whether this host's hooks can identify the actor without a correlated tool call.
     */
    default boolean hooksMarkSubagents() {
        return true;
    }

    /** What this host's {@code _meta} says about a tool call. A contradiction in it is a {@code BINDING_INVALID} failure, not an answer. */
    CallerFacts read(Map<String, Object> meta);

    /** What the PreToolUse hook of a Sennard tool should leave for the tool call that follows. */
    HookAdmission admission(HostEvent.ToolRequested request);

    /**
     * The keys that match a call to its hook on a host that gives the call no id.
     *
     * @param arguments the call's arguments, in the same form the hook reported them
     */
    default Optional<CallKeys> callKeys(CallerFacts.Observed facts, String tool, JsonNode arguments) {
        return Optional.empty();
    }

    /** Supplies actor evidence to hooks and returns whether it agrees with what this conversation is known to be. */
    default boolean matchedHook(CallerFacts.Observed facts) {
        return true;
    }

    /** The current actor for a host that correlates its hooks. Null means the hooks of this host report their own actors. */
    default SessionActor currentHookActor(String conversation) {
        return null;
    }

    /** Whether a matched tool-hook call can identify an inferred root for a managed launch. */
    default boolean acceptsInferredManagedRoot() {
        return false;
    }

    /** @param call the key of this caller, tool and arguments; @param caller the key of this caller and tool */
    record CallKeys(String call, String caller) {
    }

    /** What a tool call's {@code _meta} says. */
    sealed interface CallerFacts {
        /**
         * The host names the session itself (classes A and B).
         *
         * @param hostSessionId the root session
         * @param hookSessionId the session id this caller's own hooks carry, which differs from the root on a host that runs a child's hooks
         *     under its own conversation
         * @param parentAgentKey the direct parent of a child when the host names it, otherwise null
         * @param callId the host's identifier of the call when it gives one, otherwise null
         */
        record Observed(String hostSessionId, String hookSessionId, SessionActor actor, String parentAgentKey, String callId, Execution execution) implements CallerFacts {
        }

        /**
         * The host names only the call; the row its hook left says the rest (class C).
         *
         * @param actorFromRow who called, from the agent id of the row (null for a row that holds none)
         */
        record ClaimByCall(String callId, Function<String, SessionActor> actorFromRow) implements CallerFacts {
        }

        /** @param reason what was missing, a fixed code */
        record Unavailable(String reason) implements CallerFacts {
        }
    }

    /** What a PreToolUse hook leaves behind. */
    sealed interface HookAdmission {
        /** This host's calls say who called by themselves. */
        record None() implements HookAdmission {
        }

        /** Keep a row under this id. */
        record Row(String callId) implements HookAdmission {
        }

        /** The call cannot be admitted; the tool does not run. */
        record Refused(String reason) implements HookAdmission {
        }
    }
}
