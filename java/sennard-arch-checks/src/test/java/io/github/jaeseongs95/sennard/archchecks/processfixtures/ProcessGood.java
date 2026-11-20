// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.processfixtures;

import java.util.List;
import java.util.Map;

/** The near misses the process rule must leave alone: words of the API in names and strings, and a class that only holds the result of another one's work. */
final class ProcessGood {
    private final Map<String, String> environment;
    private final List<String> processNames = List.of("ProcessBuilder", "ProcessHandle", "Runtime.exec");

    ProcessGood(Map<String, String> environment) {
        this.environment = environment;
    }

    String describe() {
        return processNames + " " + environment.size() + " " + Runtime.version().feature();
    }

    int processors() {
        return Runtime.getRuntime().availableProcessors();
    }
}
