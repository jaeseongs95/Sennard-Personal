// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What the command runs from: a release jar ({@code java -jar sennard-<version>.jar}, or the application jar of an installed version), or anything else (classes in a folder), which is not
 * a release and so has nothing to install. The {@link Installer} takes it as a type of two cases instead of an {@link Optional} held as a field or passed as an argument, which the
 * language's own guidance keeps for a method's result.
 */
public sealed interface RunningProgram {
    /** @return the jar this program runs from, empty when it runs from anything else */
    Optional<Path> jar();

    /** Runs from this release jar. */
    static RunningProgram fromJar(Path jar) {
        return new FromJar(jar);
    }

    /** Runs from anything but a release jar. */
    static RunningProgram elsewhere() {
        return new Elsewhere();
    }

    record FromJar(Path path) implements RunningProgram {
        @Override
        public Optional<Path> jar() {
            return Optional.of(path);
        }
    }

    record Elsewhere() implements RunningProgram {
        @Override
        public Optional<Path> jar() {
            return Optional.empty();
        }
    }
}
