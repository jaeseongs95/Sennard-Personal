// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * What an {@link Installer} test needs to run the installer's own steps without the JDK's programs: a release jar of the smallest kind the install accepts, and a {@link Commands} that
 * does what {@code java -Djarmode=tools extract} does to its destination (the real extraction of the real release is {@link ReleaseJarFixture#extracted()}'s). The cases that use them
 * are about what the installer decides (the state folder it records, the preset it chooses), and end the run at the first command that comes after.
 */
final class InstallStandIns {
    private InstallStandIns() {
    }

    /** A release jar made with the JDK's zip stream that holds the content folder the install copies out of the jar ({@link ReleaseJar#CONTENT}), and nothing else. */
    static Path releaseJar(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry(ReleaseJar.CONTENT + "/skills/a-skill/SKILL.md"));
            zip.write("a skill".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(ReleaseJar.CONTENT + "/contracts/a-contract.schema.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }

    /**
     * Commands that make the extracted layout of {@code version} where the extraction is told to ({@code --destination <folder>}, the last word): the application jar and
     * {@code lib/} with the hook client's jar. Every other command goes to {@code rest}.
     */
    static Commands extracting(String version, Commands rest) {
        return (argv, environment, timeout) -> {
            if (!argv.contains("extract")) {
                return rest.run(argv, environment, timeout);
            }
            Path destination = Path.of(argv.getLast());
            try {
                Files.createDirectories(destination.resolve("lib"));
                Files.writeString(destination.resolve(InstalledVersion.jarName(version)), "the application jar");
                Files.writeString(destination.resolve("lib").resolve(InstalledVersion.HOOK_CLIENT_JAR_PREFIX + version + ".jar"), "the hook client's jar");
            } catch (IOException cause) {
                throw new UncheckedIOException(cause);
            }
            return new Commands.Result(0, "");
        };
    }
}
