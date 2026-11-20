// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.journal;

/**
 * The phase of a workflow that has a change journal. It is derived from the journal, not stored beside it: a journal whose baseline is not captured is still being planned
 * (reading and research only), and one whose baseline is captured is executing (the plan is accepted, so the files may change).
 */
public enum WorkflowPhase {
    PLANNING, EXECUTING;

    /** The phase a journal with this baseline is in. */
    public static WorkflowPhase of(BaselineState state) {
        return switch (state) {
            case EMPTY -> PLANNING;
            case CAPTURED -> EXECUTING;
        };
    }
}
