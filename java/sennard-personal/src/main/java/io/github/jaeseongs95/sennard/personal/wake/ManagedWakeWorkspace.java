// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

final class ManagedWakeWorkspace {
    private ManagedWakeWorkspace() {
    }

    /** Retains the supplied path and lets the adapter diagnose malformed input. */
    static Optional<Path> existingDirectory(String workspace) throws InvalidPathException {
        return Optional.ofNullable(workspace)
                .map(Path::of)
                .filter(Path::isAbsolute)
                .filter(Files::isDirectory);
    }
}
