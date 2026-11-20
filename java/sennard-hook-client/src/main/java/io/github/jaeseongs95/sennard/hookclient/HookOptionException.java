// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

/**
 * A hook command line that cannot be understood. A host's definition never produces one (it is written from {@link HookClientCall#arguments()}), so it is a person's
 * or a test's mistake; the client says its code and exits with 2, and {@code sennard hook} turns it into its own input failure.
 */
public final class HookOptionException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /** What is wrong with the option; {@link #code()} is the word the failure carries. */
    public enum Problem {
        /** An option that takes a number was given something else. */
        NOT_A_NUMBER("notANumber"),
        /** An option was given a value its set does not have. */
        UNKNOWN_VALUE("unknownValue"),
        /** Anything else: an option that does not exist, a missing or surplus operand or value, an option given twice. */
        OTHER("other");

        private final String code;

        Problem(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Problem problem;
    private final String option;
    private final String value;

    public HookOptionException(Problem problem, String option, String value) {
        super("HOOK_OPTION_INVALID " + problem.code() + " " + option + "=" + value);
        this.problem = problem;
        this.option = option;
        this.value = value;
    }

    public Problem problem() {
        return problem;
    }

    public String option() {
        return option;
    }

    public String value() {
        return value;
    }
}
