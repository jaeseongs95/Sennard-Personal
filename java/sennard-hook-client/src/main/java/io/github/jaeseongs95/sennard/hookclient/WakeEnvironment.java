// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Selects environment variables for the local worker process, using the installed host's field list. */
public final class WakeEnvironment {
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private WakeEnvironment() {
    }

    public static Optional<Map<String, String>> forHost(String label, Map<String, String> environment) throws IOException {
        if (!LABEL.matcher(label).matches()) {
            return Optional.empty();
        }
        try (InputStream resource = WakeEnvironment.class.getResourceAsStream("/sennard-hook-wake/" + label + ".fields")) {
            if (resource == null) {
                return Optional.empty();
            }
            Map<String, String> selected = new HashMap<>();
            for (String line : new String(resource.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                String name = line.strip();
                if (name.isEmpty() || name.startsWith("#")) {
                    continue;
                }
                if (!FIELD.matcher(name).matches()) {
                    throw new IOException("Invalid wake environment resource field");
                }
                String value = environment.get(name);
                if (value != null) {
                    selected.put(name, value);
                }
            }
            return Optional.of(Map.copyOf(selected));
        }
    }
}
