// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.personal.ReleasedFolder;

/**
 * {@link ReleaseJar#extractInto} with the real release jar and the real {@code java}, from a folder whose name has a letter outside the system's code page. The Windows {@code java}
 * launcher reads its command line in that code page and turns such a letter into {@code ?}, so a command with the jar's absolute path in it opens a jar of another name
 * ({@code Unable to access jarfile}). The jar is opened by its file name from its own folder instead. The folder has {@code ő}, which a Korean or Western European code page cannot write
 * (the answer is the JDK's: where the code page or the locale can write it the case has nothing to catch, and runs the same way; a file system that cannot hold the name skips it). The
 * destination is the test's own ASCII-only folder, as the installation's folder is (the install checks it with {@code InstallEnvironment#checked}).
 */
class ReleaseJarOutsideCodePageTest {
    @TempDir
    Path temporary;

    @Test
    void theJarOfAFolderWithALetterOutsideTheCodePageIsExtractedByItsFileName() throws Exception {
        String version = ReleaseJarFixture.version();
        assertTrue(temporary.toString().chars().allMatch(letter -> letter < 0x80), "the destination is passed as an argument, so it is ASCII-only: " + temporary);
        Path folder;
        try {
            folder = Files.createDirectories(temporary.resolve("download ő"));
        } catch (InvalidPathException | IOException unwritable) {
            assumeTrue(false, "this file system cannot hold the folder name: " + unwritable);
            return;
        }
        Path jar = folder.resolve(InstalledVersion.jarName(version));
        Files.copy(ReleaseJarFixture.releaseJar(), jar);
        Path destination = temporary.resolve("extracted");

        ReleaseJar.of(jar).orElseThrow().extractInto(destination, version, ReleaseJarFixture.javaProgram(), new JavaCommands(), Map.of());

        assertTrue(Files.isRegularFile(destination.resolve(InstalledVersion.jarName(version))), "the application jar");
        assertTrue(Files.isDirectory(destination.resolve("lib")), "lib/");
        assertTrue(Files.isDirectory(destination.resolve("skills")), "the content folder copied out of the jar");
        ReleasedFolder.await(temporary);
    }
}
