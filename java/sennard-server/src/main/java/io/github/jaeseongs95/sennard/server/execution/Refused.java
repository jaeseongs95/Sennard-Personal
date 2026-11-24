// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

/** An execution the runner did not take; nothing was started and nothing is left behind, and no execution id was made. */
public final class Refused extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Why an execution was not taken. */
    public enum Reason {
        /** The runner already runs as many programs as it is allowed to at once; the detail is that limit. */
        TOO_MANY_RUNS,
        /** The server is stopping; there is no detail. */
        STOPPING,
        /** An argument cannot be given to the program as written; the detail is its index in {@code argv}. */
        UNSAFE_ARGUMENT
    }

    private final Reason reason;
    private final int detail;

    private Refused(Reason reason, int detail) {
        super(reason + " (" + detail + ")");
        this.reason = reason;
        this.detail = detail;
    }

    /** @param limit how many programs may run at once */
    public static Refused tooManyRuns(int limit) {
        return new Refused(Reason.TOO_MANY_RUNS, limit);
    }

    public static Refused stopping() {
        return new Refused(Reason.STOPPING, 0);
    }

    /** @param index the position in {@code argv} of the argument, 0 being the program */
    public static Refused unsafeArgument(int index) {
        return new Refused(Reason.UNSAFE_ARGUMENT, index);
    }

    public Reason reason() {
        return reason;
    }

    /** The number that goes with the reason: the limit for {@link Reason#TOO_MANY_RUNS}, the index in {@code argv} for {@link Reason#UNSAFE_ARGUMENT}, 0 otherwise. */
    public int detail() {
        return detail;
    }
}
