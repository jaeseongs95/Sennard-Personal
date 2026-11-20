// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.cli;

import java.util.Map;
import java.util.Optional;

/** What a command line asks for, or why it cannot be understood. Produced by {@link CliParser}, without side effects. */
sealed interface CliRequest {
    /**
     * A command to run; {@code options} maps each given option name (with its dashes) to its value, a flag to the empty string; {@code operands} are the
     * positional arguments, in the order {@link SennardCommand#operands()} names them.
     */
    record Run(SennardCommand command, Map<String, String> options, java.util.List<String> operands) implements CliRequest {
        Run(SennardCommand command, Map<String, String> options) {
            this(command, options, java.util.List.of());
        }

        Optional<String> value(String name) {
            return Optional.ofNullable(valueOrNull(name));
        }

        /** The option's value, the empty string for a flag, or null when the option was not given (for a parameter that takes null for "not given"). */
        String valueOrNull(String name) {
            return options.get(name);
        }

        boolean has(String name) {
            return options.containsKey(name);
        }
    }

    /** {@code -h}/{@code --help}: the usage of the command, or of {@code sennard} itself when {@code command} is null. */
    record Help(SennardCommand command) implements CliRequest {
    }

    /** {@code -V}/{@code --version}. */
    record Version() implements CliRequest {
    }

    /** A usage error, exit code 2: one line saying what is wrong, then the usage of the command (or of {@code sennard} when {@code command} is null). */
    record UsageError(String message, SennardCommand command) implements CliRequest {
    }
}
