// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Runs one of Sennard's own launchers to its end. Its output is UTF-8 on every code page ({@code Utf8Boundary.fixStandardStreams}). One implementation runs it;
 * a test for what comes before the first launcher stands in for it.
 */
@FunctionalInterface
public interface Commands {
    Result run(List<String> argv, Map<String, String> environment, Duration timeout);

    /** {@link #run(List, Map, Duration)} with these bytes on the launcher's standard input; an implementation that has no way to feed it ignores them. */
    default Result run(List<String> argv, Map<String, String> environment, byte[] input, Duration timeout) {
        return run(argv, environment, timeout);
    }

    /**
     * {@link #run(List, Map, Duration)} in {@code directory} as the launcher's working folder; an implementation that has no way to start it elsewhere ignores it, as it ignores
     * input. A program whose arguments cannot carry a path (the Windows {@code java} launcher reads them in the code page) is given its file by name and its folder here.
     */
    default Result run(List<String> argv, Path directory, Map<String, String> environment, Duration timeout) {
        return run(argv, environment, timeout);
    }

    record Result(int exitCode, String output) {
    }
}
