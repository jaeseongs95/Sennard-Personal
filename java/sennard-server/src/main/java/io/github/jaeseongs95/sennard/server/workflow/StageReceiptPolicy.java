// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The persistence policy a provider descriptor declares for its stage results, as the descriptor and the plan's stage carry it. A stage result that is stored
 * by reference only ({@link Mode#REFERENCE_ONLY}) may name its actor by a JSON pointer, require that actor to be unique in the run, and require the actors of the
 * stages before it. The contracts allow each of {@code uniqueness} and {@code actorIdsMatch} one value, so the presence of the field is the rule.
 */
public record StageReceiptPolicy(Mode mode, @JsonInclude(JsonInclude.Include.NON_NULL) String actorIdPointer, @JsonInclude(JsonInclude.Include.NON_NULL) Uniqueness uniqueness,
        @JsonInclude(JsonInclude.Include.NON_NULL) String actorIdsPointer, @JsonInclude(JsonInclude.Include.NON_NULL) ActorIdsMatch actorIdsMatch) {

    /** How a stage result is persisted; the contract has the one mode. */
    public enum Mode {
        REFERENCE_ONLY("reference-only");

        private final String wire;

        Mode(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** What the actor must be unique across; the contract has the one scope. */
    public enum Uniqueness {
        RUN("run");

        private final String wire;

        Uniqueness(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** What the actors a stage lists must match; the contract has the one rule. */
    public enum ActorIdsMatch {
        PRIOR_POLICY_ACTORS("prior-policy-actors");

        private final String wire;

        ActorIdsMatch(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** Whether the result's own actor is named and must be unique across the run's stages. */
    boolean requiresUniqueActor() {
        return uniqueness != null;
    }

    /** Whether the result lists the actors of the stages before it. */
    boolean listsPriorActors() {
        return actorIdsMatch != null && actorIdsPointer != null;
    }
}
