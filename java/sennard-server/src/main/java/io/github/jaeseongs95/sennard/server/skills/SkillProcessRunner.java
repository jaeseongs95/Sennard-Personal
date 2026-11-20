// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Starts a caller-supplied program for the test-engineering receipt, on this computer.
 *
 * <p>Everything that concerns how a program is started is the implementation's: where the program is looked for, which of the server's environment it gets, what argument it
 * cannot be given, how many run at once, how much they may write and what is left behind. The caller of this interface judges the request (the shape of its arguments and the
 * snapshot) and builds the receipt from what comes back.
 */
public interface SkillProcessRunner {
    /** How the program's run ended. Each form carries what the program wrote, as UTF-8 text. */
    sealed interface Completed permits Exited, TimedOut, Failed {
        String stdout();

        String stderr();
    }

    /**
     * The program ended by itself.
     *
     * @param outputTruncated whether it wrote more to stdout or stderr than the runner keeps (the text is the kept start)
     */
    record Exited(int exitCode, String stdout, String stderr, boolean outputTruncated) implements Completed {
    }

    /** The program was still running at the limit; it and every process it started were killed. */
    record TimedOut(String stdout, String stderr) implements Completed {
    }

    /**
     * The program did not run to an end: it could not be started, the wait for it was interrupted or the server stopped, or it wrote more than the runner keeps while it was
     * still running and was killed for it (the text is then the kept start).
     */
    record Failed(String stdout, String stderr) implements Completed {
    }

    /** Why a run was not started. */
    enum Reason {
        /** The runner already runs as many commands as it is allowed to at once; the detail is that limit. */
        TOO_MANY_RUNS,
        /** The server is stopping; there is no detail. */
        STOPPING,
        /** An argument cannot be given to the program as written; the detail is its index in {@code argv}. */
        UNSAFE_ARGUMENT
    }

    /** A run the runner did not start; nothing was started and nothing is left behind. */
    final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Reason reason;
        private final int detail;

        private Refused(Reason reason, int detail) {
            super(reason + " (" + detail + ")");
            this.reason = reason;
            this.detail = detail;
        }

        /** @param limit how many commands may run at once */
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

    /** Where the program ran, as a receipt records it: the operating system, its version and architecture, as the JVM reports them. */
    String platform();

    /**
     * What a receipt says of the environment the program gets when the caller gave no note of its own: which of the server's variables are passed and the names (never the
     * values) of the caller's.
     */
    String describeEnvironment(Set<String> callerVariableNames);

    /**
     * Runs {@code argv} with {@code workingDirectory} as its directory, and waits at most {@code timeout}. The program's environment is the listed variables of the server's plus
     * {@code environment} (the caller's, which wins over a listed name). A program named with a path that is not absolute is looked for from {@code workingDirectory}.
     *
     * @throws Refused when the run is not started: too many are running, the server is stopping, or an argument cannot be given as written
     */
    Completed run(Path workingDirectory, List<String> argv, Map<String, String> environment, Duration timeout);
}
