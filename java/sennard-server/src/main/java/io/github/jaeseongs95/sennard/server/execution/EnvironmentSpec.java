// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The environment variables a program is given: of the server process's environment only the variables with the listed names (the ones the server has), then the variables
 * added, which win over a variable of the same name. There is no default and no way to say "all of it": the server's environment comes from whatever process started the server
 * and can hold another session's variables or a host's tokens, so whoever asks for an execution names what the program may see. An empty list of names gives the program only
 * {@code variables}: nothing of the server's.
 *
 * @param inheritedNames the names of the server's variables the program is given when the server has them
 * @param variables the additions and replacements; never changed after the spec was made
 */
public record EnvironmentSpec(List<String> inheritedNames, Map<String, String> variables) {
    public EnvironmentSpec {
        Objects.requireNonNull(inheritedNames, "inheritedNames");
        inheritedNames = List.copyOf(inheritedNames);
        Objects.requireNonNull(variables, "variables");
        variables.forEach((name, value) -> {
            if (name == null || value == null) {
                throw new IllegalArgumentException("An environment variable has a name and a value");
            }
        });
        variables = Map.copyOf(variables);
    }
}
