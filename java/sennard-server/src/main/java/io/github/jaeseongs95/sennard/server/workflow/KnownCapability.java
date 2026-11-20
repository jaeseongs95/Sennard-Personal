// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

/**
 * The capabilities the plan and its gates treat specially, by the name the skill registry writes. A capability is a skill's own name and the set of them is open: this is only the few
 * the engine itself has to recognise, and every other capability passes through as the text it is.
 */
enum KnownCapability {
    /** The capability an audit stage satisfies; a plan of a risk that needs an audit always carries it. */
    INDEPENDENT_AUDIT("independent-audit"),
    /** The capability of the evaluation-validity audit skill. */
    EVALUATION_VALIDITY_AUDIT("evaluation-validity-audit");

    private final String wire;

    KnownCapability(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }

    /** Whether the capability, as the registry or a stage writes it, is this one. */
    boolean is(String capability) {
        return wire.equals(capability);
    }
}
