// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store;

import java.util.EnumSet;
import java.util.Set;

/**
 * The state of a workflow run and of a stage in its plan. One definition: the database column {@code workflow_runs.state} holds the constant's
 * name (Spring maps an enum to its name), the contracts spell the same names, and the code asks the constant, never a list of names.
 */
public enum RunState {
    READY(false, false),
    RUNNING(false, false),
    PAUSED(false, false),
    NEEDS_INPUT(false, true),
    NEEDS_APPROVAL(false, true),
    NEEDS_REDESIGN(false, true),
    FAILED(true, false),
    PASSED(true, false),
    BLOCKED(true, false);

    private final boolean terminal;
    private final boolean userGate;

    RunState(boolean terminal, boolean userGate) {
        this.terminal = terminal;
        this.userGate = userGate;
    }

    /** The run is over: only a new run goes on from here. */
    public boolean isTerminal() {
        return terminal;
    }

    /** The run waits for the user: input, an approval or a redesign. */
    public boolean isUserGate() {
        return userGate;
    }

    /** A state a stage result can carry; a plan's stage is {@link #READY} until its result arrives, and {@link #RUNNING} is the run's alone. */
    public boolean isStageOutcome() {
        return this != READY && this != RUNNING && this != PAUSED;
    }

    /**
     * The states a run in this state may be moved to: a stage result or a finalize moves a running run to any outcome, and abort (or a released
     * root) blocks any run that is not over. A terminal run has none.
     */
    public Set<RunState> next() {
        return switch (this) {
            case READY -> EnumSet.of(RUNNING, BLOCKED);
            case RUNNING -> EnumSet.of(PAUSED, NEEDS_INPUT, NEEDS_APPROVAL, NEEDS_REDESIGN, FAILED, PASSED, BLOCKED);
            case PAUSED -> EnumSet.of(RUNNING, BLOCKED);
            case NEEDS_INPUT, NEEDS_APPROVAL, NEEDS_REDESIGN -> EnumSet.of(BLOCKED);
            case FAILED, PASSED, BLOCKED -> EnumSet.noneOf(RunState.class);
        };
    }

    public boolean canMoveTo(RunState target) {
        return next().contains(target);
    }
}
