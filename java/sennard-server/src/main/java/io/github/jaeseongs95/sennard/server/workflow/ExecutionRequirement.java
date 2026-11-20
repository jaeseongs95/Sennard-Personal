// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What a stage asks of its execution ({@code execution-requirement.v1.schema.json}): that the host's observation of the model is recorded, or, for a stage a
 * skill's check alone decides, that none is. The requirement names no model or reasoning-effort floor, so it is the policy, the kind of work and whether the
 * observation is required. The contract ties the last to the kind: a semantic stage requires the observation and a deterministic stage does not, so a requirement
 * that says otherwise is not one.
 */
public record ExecutionRequirement(Policy policyId, ExecutionKind kind, boolean observationRequired) {

    /** The requirement of a stage that needs the observed model: every stage but the deterministic ones, and the bootstrap. */
    public static final ExecutionRequirement SEMANTIC = new ExecutionRequirement(Policy.SEMANTIC_EXECUTION_ASSURANCE_V1, ExecutionKind.SEMANTIC, true);

    /** The requirement of a stage a skill's check alone decides, which needs no observed model. */
    public static final ExecutionRequirement DETERMINISTIC = new ExecutionRequirement(Policy.SEMANTIC_EXECUTION_ASSURANCE_V1, ExecutionKind.DETERMINISTIC, false);

    public ExecutionRequirement {
        if (observationRequired != (kind == ExecutionKind.SEMANTIC)) {
            throw new IllegalArgumentException("A " + kind + " requirement " + (observationRequired ? "does not require" : "requires") + " the observation");
        }
    }

    /** True for a stage that needs the host's observation of the model. */
    boolean semantic() {
        return kind == ExecutionKind.SEMANTIC;
    }

    /** The policy a requirement names; the contract fixes the one wire text. */
    public enum Policy {
        SEMANTIC_EXECUTION_ASSURANCE_V1("semantic-execution-assurance-v1");

        private final String wire;

        Policy(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }
}
