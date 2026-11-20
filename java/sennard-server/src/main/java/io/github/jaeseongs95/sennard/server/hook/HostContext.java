// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

/**
 * What a hook call says about where it came from. A component the host did not report is {@code null} (the host's silence, not an empty value); no component is an {@code Optional}.
 *
 * @param hostLabel the host the hook was registered under: {@link HostId#label()} is the label it was posted with. The component keeps its name because the printed form of this
 *            record is what the adapters' golden digests compare
 * @param sessionId the host's own session identifier; never empty
 * @param workspace the working directory or first workspace the host reports, or null
 * @param transcriptPath the session's transcript as the host names it, or null; a read of it, if any, is the domain's
 * @param model the model the host reports for this call, or null
 * @param effort the reasoning effort the host reports for this call, or null
 * @param turnId the host's turn identifier, or null
 */
public record HostContext(HostId hostLabel, String sessionId, SessionActor actor, String workspace, String transcriptPath, String model, String effort, String turnId) {

    /**
     * Who in the session made the call. Adapters decide this from what their host marks; {@link Role#INDETERMINATE} means the host's marker
     * was present but unusable or there is none to infer from, and no identity may be built on it.
     *
     * @param agentKey the child's identifier, for a child; null for every other actor
     * @param lead who leads the session of a root actor; {@link Lead#MAIN} for every actor that is not a root, where it does not apply
     */
    public record SessionActor(Role role, Basis basis, String reasonCode, String agentKey, Lead lead) {
        /** The child's identifier, or {@code fallback} for an actor that has none. */
        public String agentKeyOr(String fallback) {
            return agentKey != null ? agentKey : fallback;
        }

        /** Whether the host's evidence supports root-only operations such as prompt retention and peer delivery. */
        public boolean rootEstablished() {
            return role == Role.ROOT && (basis == Basis.OBSERVED || basis == Basis.INFERRED);
        }

        public static SessionActor root(Basis basis, String reasonCode) {
            return new SessionActor(Role.ROOT, basis, reasonCode, null, Lead.MAIN);
        }

        /**
         * A root whose session the host started with an agent chosen at launch (Claude Code's {@code --agent}). It is still the root of its
         * session for every decision about who may call what; only the list of main sessions leaves it out.
         */
        public static SessionActor presetAgentRoot(Basis basis, String reasonCode) {
            return new SessionActor(Role.ROOT, basis, reasonCode, null, Lead.PRESET_AGENT);
        }

        public static SessionActor child(String agentKey, String reasonCode) {
            return new SessionActor(Role.CHILD, Basis.OBSERVED, reasonCode, agentKey, Lead.MAIN);
        }

        public static SessionActor managedChild(String conversationId) {
            return child(conversationId, "MANAGED_CHILD_CONVERSATION");
        }

        public static SessionActor indeterminate(String reasonCode) {
            return new SessionActor(Role.INDETERMINATE, Basis.UNAVAILABLE, reasonCode, null, Lead.MAIN);
        }
    }

    public enum Role {
        ROOT, CHILD, INDETERMINATE
    }

    /** Who leads a root actor's session: the host's own main agent, or an agent the host was told to run as at launch. */
    public enum Lead {
        MAIN, PRESET_AGENT
    }

    public enum Basis {
        OBSERVED, INFERRED,
        /** Treat the conversation as unclassified while allowing the hooks that discover its actor to run. */
        PROVISIONAL,
        UNAVAILABLE
    }
}
