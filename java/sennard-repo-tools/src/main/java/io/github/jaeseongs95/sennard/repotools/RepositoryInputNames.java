// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The exact sorted names the repository checks see, for Gradle's incremental test input. */
public final class RepositoryInputNames {
    private RepositoryInputNames() {
    }

    public static void main(String[] args) throws IOException {
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.write(output, Repo.JSON.writeValueAsBytes(new Repo(Path.of(args[0])).trackedFiles()));
    }
}
