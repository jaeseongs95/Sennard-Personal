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
        Files.createDirectories(temporary.resolve("release"));
        Files.writeString(temporary.resolve("release/setup.cmd.template"), "cmd @SENNARD_RELEASE_JAR@ @SENNARD_FAILURE_WAIT_SECONDS@\n", StandardCharsets.UTF_8);
        Files.writeString(temporary.resolve("release/setup.sh.template"), "sh @SENNARD_RELEASE_JAR@\n", StandardCharsets.UTF_8);
        return new ReleaseAssets(new Repo(temporary));
    }

    private Path release() throws IOException {
        Path folder = Files.createDirectories(temporary.resolve("assets"));
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
    void theSetupLaunchersAreBoundToTheSameJarAndBothAreChecked() throws Exception {
        Path folder = assetFolder();
        ReleaseAssets assets = assets();
        assertEquals("cmd " + JAR + " " + ReleaseAssets.FAILURE_WAIT_SECONDS + "\r\n", Files.readString(folder.resolve("setup.cmd")));
        assertEquals("#!/bin/sh\nsh " + JAR + "\n", Files.readString(folder.resolve("setup.sh")));
        assets.checkChecksum(folder);
        Files.writeString(folder.resolve("setup.cmd"), "cmd sennard-5.0.0.jar\n");
        assertThrows(RepoTools.Failed.class, () -> assets.checkChecksum(folder));
        assertThrows(RepoTools.Failed.class, () -> assets.checkJar(folder, ""));
        Files.delete(folder.resolve("setup.cmd"));
        assertThrows(RepoTools.Failed.class, () -> assets.checkChecksum(folder));
        assets.checkJar(folder, "");
        assets.checkChecksum(folder);
    }

    @Test
    void theLineEndingBelongsToTheKindOfFileWhateverTheTemplateHas() throws Exception {
        // a template checked out with CRLF (or a mix) still gives a CRLF batch file and an LF shell script
        Files.createDirectories(temporary.resolve("java"));
        Files.writeString(temporary.resolve("java/gradle.properties"), "version=6.0.0\n", StandardCharsets.UTF_8);
        Files.createDirectories(temporary.resolve("release"));
        Files.writeString(temporary.resolve("release/setup.cmd.template"), "a @SENNARD_RELEASE_JAR@ @SENNARD_FAILURE_WAIT_SECONDS@\r\nb\nc\r\n", StandardCharsets.UTF_8);
        Files.writeString(temporary.resolve("release/setup.sh.template"), "a @SENNARD_RELEASE_JAR@\r\nb\nc\r\n", StandardCharsets.UTF_8);
        Path folder = release();
        ReleaseAssets assets = new ReleaseAssets(new Repo(temporary));
        assets.checkJar(folder, "");
        assertEquals("a " + JAR + " " + ReleaseAssets.FAILURE_WAIT_SECONDS + "\r\nb\r\nc\r\n", Files.readString(folder.resolve("setup.cmd"), StandardCharsets.UTF_8));
        assertEquals("#!/bin/sh\na " + JAR + "\nb\nc\n", Files.readString(folder.resolve("setup.sh"), StandardCharsets.UTF_8));
        assets.checkChecksum(folder);
        // a launcher with the other line ending is not the release's: the check compares the bytes
        Files.writeString(folder.resolve("setup.cmd"), "a " + JAR + " " + ReleaseAssets.FAILURE_WAIT_SECONDS + "\nb\nc\n", StandardCharsets.UTF_8);
        assertThrows(RepoTools.Failed.class, () -> assets.checkChecksum(folder));
    }

    @Test
    void aTemplateThatLacksOneOfItsPlaceholdersFailsEvenWhenTheOtherIsThere() throws Exception {
        ReleaseAssets assets = assets();
        // the wait placeholder is left, the jar placeholder is gone
        Files.writeString(temporary.resolve("release/setup.cmd.template"), "cmd @SENNARD_FAILURE_WAIT_SECONDS@\n", StandardCharsets.UTF_8);
        RepoTools.Failed noJar = assertThrows(RepoTools.Failed.class, () -> assets.launcherBytes("setup.cmd", JAR, 5));
        assertTrue(noJar.getMessage().contains("@SENNARD_RELEASE_JAR@"), noJar.getMessage());
        // the jar placeholder is left, the wait placeholder is gone
        Files.writeString(temporary.resolve("release/setup.cmd.template"), "cmd @SENNARD_RELEASE_JAR@\n", StandardCharsets.UTF_8);
        RepoTools.Failed noWait = assertThrows(RepoTools.Failed.class, () -> assets.launcherBytes("setup.cmd", JAR, 5));
        assertTrue(noWait.getMessage().contains("@SENNARD_FAILURE_WAIT_SECONDS@"), noWait.getMessage());
        // a template of a launcher that has no wait needs only the jar placeholder
        assertEquals("#!/bin/sh\nsh " + JAR + "\n", new String(assets.launcherBytes("setup.sh", JAR, 5), StandardCharsets.UTF_8));
    }

    @Test
    void aPlaceholderTheRenderDoesNotFillFails() throws Exception {
        ReleaseAssets assets = assets();
        // both known placeholders are there, and a misspelled one is left behind
        Files.writeString(temporary.resolve("release/setup.cmd.template"),
                "cmd @SENNARD_RELEASE_JAR@ @SENNARD_FAILURE_WAIT_SECONDS@ @SENNARD_FAILURE_WAIT_SECOND@\n", StandardCharsets.UTF_8);
        RepoTools.Failed misspelled = assertThrows(RepoTools.Failed.class, () -> assets.launcherBytes("setup.cmd", JAR, 5));
        assertTrue(misspelled.getMessage().contains("@SENNARD_FAILURE_WAIT_SECOND@"), misspelled.getMessage());
        Files.writeString(temporary.resolve("release/setup.sh.template"), "sh @SENNARD_RELEASE_JAR@ @SENNARD_OTHER@\n", StandardCharsets.UTF_8);
        assertThrows(RepoTools.Failed.class, () -> assets.launcherBytes("setup.sh", JAR, 5));
    }

    @Test
    void theRealTemplatesRenderToACrlfBatchFileAndAnLfShellScript() throws Exception {
        ReleaseAssets assets = new ReleaseAssets(new Repo(Path.of(System.getProperty("sennard.repo.root"))));
        String jar = "sennard-" + assets.version() + ".jar";
        Path folder = Files.createDirectories(temporary.resolve("real"));
        Files.writeString(folder.resolve(jar), "jar", StandardCharsets.UTF_8);
        assets.checkJar(folder, "");
        byte[] cmd = Files.readAllBytes(folder.resolve("setup.cmd"));
        byte[] sh = Files.readAllBytes(folder.resolve("setup.sh"));
        String cmdText = new String(cmd, StandardCharsets.UTF_8);
        String shText = new String(sh, StandardCharsets.UTF_8);
        assertTrue(cmdText.contains(jar) && !cmdText.contains("@SENNARD_RELEASE_JAR@"), "the jar name replaces the placeholder in setup.cmd");
        assertTrue(shText.contains(jar) && !shText.contains("@SENNARD_RELEASE_JAR@"), "the jar name replaces the placeholder in setup.sh");
        assertEquals(-1, indexOfBareLf(cmd), "every LF of setup.cmd follows a CR");
        assertTrue(cmdText.contains("\r\n"));
        assertEquals(-1, cmdText.replace("\r\n", "").indexOf('\r'), "every CR of setup.cmd is followed by an LF");
        assertTrue(shText.startsWith("#!/bin/sh\n"));
        assertEquals(-1, shText.indexOf('\r'), "setup.sh has no CR");
        assets.checkChecksum(folder);
    }

    private static int indexOfBareLf(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n' && (i == 0 || bytes[i - 1] != '\r')) {
                return i;
            }
        }
        return -1;
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
