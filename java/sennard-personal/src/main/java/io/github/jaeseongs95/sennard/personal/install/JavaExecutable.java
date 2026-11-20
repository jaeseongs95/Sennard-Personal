// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.nio.file.Path;

import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;

/**
 * The {@code java} program an installation runs its own JVMs with: the one that runs the command ({@code sennard install} is run with the user's JDK, so the server, the hook client and
 * the AOT caches use the same JDK, which a cache requires). A definition in a host names it by its absolute path, as a host starts a command without the user's shell and its PATH.
 *
 * <p>A function and not a value: the command's environment is made for every command, and one that needs no {@code java} ({@code status}) must not fail on a JVM that does not report it.
 */
@FunctionalInterface
public interface JavaExecutable {
    /**
     * @return the absolute path of the program
     * @throws UnavailableException JAVA_EXECUTABLE_UNKNOWN when the JVM does not report it
     */
    Path path();

    /** The program this JVM was started from, as the JVM reports it ({@link ProcessHandle.Info#command()}); that it can be empty is the JDK's own contract. */
    static JavaExecutable ofCurrentProcess() {
        return () -> ProcessHandle.current().info().command().map(Path::of).map(Path::toAbsolutePath)
                .orElseThrow(() -> new UnavailableException(UnavailableCode.JAVA_EXECUTABLE_UNKNOWN));
    }
}
