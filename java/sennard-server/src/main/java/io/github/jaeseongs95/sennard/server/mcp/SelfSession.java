// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.mcp;

import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;

/**
 * The session a tool call came from.
 *
 * @param sessionRef the server-issued address of the session
 * @param generation the session's current generation
 * @param host the host name the session is stored under
 * @param hostSessionId the host's own id of the root session; null for a session the server minted with {@code open_session}
 * @param provenance whether the host said so or the caller only named it
 * @param actor who in the session called
 * @param parentAgentKey the direct parent of a child when the host names it, otherwise null
 * @param callId the host's identifier of this call when it has one, otherwise null
 * @param execution the model behind the call, when it was observed
 * @param transcriptPath the session's transcript as the host named it to the hook when a hook did, otherwise null; reading it is the domain's
 */
public record SelfSession(String sessionRef, long generation, String host, String hostSessionId, Provenance provenance, SessionActor actor,
        String parentAgentKey, String callId, Execution execution, String transcriptPath) {

    /** {@link #UNAVAILABLE} is a session named by a {@code sessionRef} argument: a gate on who the caller is must not accept it. */
    public enum Provenance {
        HOST_OBSERVED, UNAVAILABLE
    }

    /** What is known of the model behind a call. Not knowing is not a refusal. */
    public sealed interface Execution {
        /**
         * @param effort the reasoning effort when the host reports it, otherwise null
         * @param turnId the host's turn identifier when it reports it, otherwise null
         * @param source where the observation came from
         */
        record Observed(String model, String effort, String turnId, String source) implements Execution {
        }

        /**
         * @param reason why it was not observed, a fixed code
         * @param facts what a reader of the host's records found out when it looked and failed: codes and numbers only, never the host's text; empty when nothing was looked at
         * @param effort effort reported by the bound call even when its model is missing
         */
        record Unobserved(String reason, java.util.Map<String, Object> facts, String effort) implements Execution {
            public Unobserved {
                facts = facts == null ? java.util.Map.of() : java.util.Map.copyOf(facts);
            }

            public Unobserved(String reason) {
                this(reason, java.util.Map.of());
            }

            public Unobserved(String reason, java.util.Map<String, Object> facts) {
                this(reason, facts, null);
            }
        }
    }
}
