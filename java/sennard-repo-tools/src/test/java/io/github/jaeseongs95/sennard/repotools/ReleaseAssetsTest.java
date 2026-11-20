// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the release workflows check about the files of a release: the jar's name and checksum, and the jar against its checksum file. */
class ReleaseAssetsTest {
    private static final String JAR = "sennard-6.0.0.jar";

    @TempDir
    Path temporary;

    private ReleaseAssets assets() throws IOException {
        Files.createDirectories(temporary.resolve("java"));
        Files.writeString(temporary.resolve("java/gradle.properties"), "org.gradle.caching=true\nversion=6.0.0\n", StandardCharsets.UTF_8);
        return new ReleaseAssets(new Repo(temporary));
    }

    private Path release() throws IOException {
        Path folder = Files.createDirectories(temporary.resolve("release"));
        Files.writeString(folder.resolve(JAR), "jar", StandardCharsets.UTF_8);
        return folder;
    }

    @Test
    void theVersionIsTheOneOfGradleProperties() throws Exception {
        assertEquals("6.0.0", assets().version());
    }

    @Test
    void theJarGetsItsChecksumFile() throws Exception {
        Path release = release();
        assertEquals(JAR, assets().checkJar(release, "v6.0.0"));
        String line = Files.readString(release.resolve(JAR + ".sha256"), StandardCharsets.UTF_8);
        assertEquals(ReleaseAssets.sha256(release.resolve(JAR)) + "  " + JAR + "\n", line);
        assertEquals(64, line.indexOf(' '));
        // the build may have written the checksum already: the same line passes
        assertEquals(JAR, assets().checkJar(release, ""));
    }

    @Test
    void aTagThatIsNotTheVersionAJarOfAnotherNameAStrayFileAndAStaleChecksumFail() throws Exception {
        Path release = release();
        ReleaseAssets assets = assets();
        assertThrows(RepoTools.Failed.class, () -> assets.checkJar(release, "v6.0.1"));
        Files.writeString(release.resolve("stray.txt"), "x", StandardCharsets.UTF_8);
        assertThrows(RepoTools.Failed.class, () -> assets.checkJar(release, ""));
        Files.delete(release.resolve("stray.txt"));
        Files.writeString(release.resolve(JAR + ".sha256"), "0".repeat(64) + "  " + JAR + "\n", StandardCharsets.UTF_8);
        assertThrows(RepoTools.Failed.class, () -> assets.checkJar(release, ""));
        Files.delete(release.resolve(JAR + ".sha256"));
        Files.move(release.resolve(JAR), release.resolve("sennard-6.0.0-linux-amd64.jar"));
        assertThrows(RepoTools.Failed.class, () -> assets.checkJar(release, ""));
    }

    private Path assetFolder() throws IOException {
        Path folder = release();
        assets().checkJar(folder, "");
        return folder;
    }

    @Test
    void theJarMustMatchItsChecksum() throws Exception {
        Path folder = assetFolder();
        ReleaseAssets assets = assets();
        assets.checkChecksum(folder);
        Files.writeString(folder.resolve(JAR), "changed", StandardCharsets.UTF_8);
        RepoTools.Failed changed = assertThrows(RepoTools.Failed.class, () -> assets.checkChecksum(folder));
        assertTrue(changed.getMessage().contains(JAR), changed.getMessage());
    }

    @Test
    void aFolderWithAnExtraFileOrAChecksumNamingAnotherFileFails() throws Exception {
        Path folder = assetFolder();
        ReleaseAssets assets = assets();
        Files.writeString(folder.resolve("extra.txt"), "x", StandardCharsets.UTF_8);
        assertThrows(RepoTools.Failed.class, () -> assets.checkChecksum(folder));
        Files.delete(folder.resolve("extra.txt"));
        Files.writeString(folder.resolve(JAR + ".sha256"), ReleaseAssets.sha256(folder.resolve(JAR)) + "  other.jar\n", StandardCharsets.UTF_8);
        assertThrows(RepoTools.Failed.class, () -> assets.checkChecksum(folder));
    }
}
