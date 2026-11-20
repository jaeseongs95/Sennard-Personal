// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The vocabulary of a {@code ROOT_CONFLICT} answer: what the conflicting root is doing, how the caller can release it, and the next actions
 * offered. The conflict messages select on the wire text and the model reads it, so each constant carries it ({@code @JsonValue}).
 */
final class RootConflictTerms {
    private RootConflictTerms() {
    }

    /** The tool path that tried to insert the root: {@code open_convergence_root} or {@code start_workflow}. The offered actions differ by it. */
    enum Entry {
        OPEN,
        START
    }

    /** What the caller can do about the root that blocks it. */
    enum Standing {
        RESOLVE("resolve"),
        DISJOINT("disjoint"),
        RUNNING("running"),
        CONTINUE("continue");

        private final String wire;

        Standing(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }

    /** How the blocking root can be released: by its owner ({@code OWN}), not at all because presence cannot be read ({@code NONE}), by asking an online owner session, or with the user's approval. */
    enum ReleasePath {
        OWN("own"),
        NONE("none"),
        ONLINE("online"),
        APPROVAL("approval");

        private final String wire;

        ReleasePath(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }

    /** An action a conflict answer offers the caller. */
    enum NextAction {
        RESTART_WITH_DISJOINT_WRITE_TARGETS("restart-with-disjoint-writeTargets"),
        CONTINUE_RUNNING_WORKFLOW("continue-running-workflow"),
        CONTINUE_OWN_ROOT("continue-own-root"),
        RESOLVE_GATE("resolve-gate"),
        RELEASE_OWN_ROOT("release-own-root"),
        RELEASE_WITH_USER_APPROVAL("release-with-user-approval"),
        ASK_OWNER_SESSION("ask-owner-session");

        private final String wire;

        NextAction(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }
}
