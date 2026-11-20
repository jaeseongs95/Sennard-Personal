// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code releaseJar} task of sennard-personal in a real Gradle build (Gradle TestKit, https://docs.gradle.org/current/userguide/test_kit.html). The build is a small one of its own whose
 * {@code bootJar} is a plain jar named like the Boot jar, and it applies java/sennard-personal/release-jar.gradle.kts, the very script the module applies, copied. The judgement is what the
 * build does to the folder build/release: an earlier build's jar in it (the failure that stopped the release build: "The destination file already exists") does not stop the task, and the
 * folder ends up with the current jar and its sha256.
 *
 * <pre>
 * Case                              Input                                                        Expected
 * old jar in build/release          build/release/sennard-6.0.0.jar and a stray file exist       SUCCESS; the jar equals the bootJar's, the stray file is gone, sha256 names that jar
 * second build, nothing changed     the same build again, with the configuration cache           UP-TO-DATE
 * jar changed                       the content of the jar changes, then the build again         SUCCESS; the folder holds the new jar and the new sha256
 * </pre>
 */
class ReleaseJarTaskBuildTest {
    private static final Path SCRIPT = Path.of(System.getProperty("sennard.repo.root")).resolve("java/sennard-personal/release-jar.gradle.kts");
    private static final String JAR = "sennard-6.0.0.jar";

    @TempDir
    Path project;

    @Test
    void anOldJarInTheReleaseFolderIsReplaced() throws IOException, NoSuchAlgorithmException {
        writeBuild("first");
        Path release = project.resolve("build/release");
        Files.createDirectories(release);
        Files.writeString(release.resolve(JAR), "the jar of an earlier build", StandardCharsets.UTF_8);
        Files.writeString(release.resolve("stray.txt"), "left by something else", StandardCharsets.UTF_8);

        BuildResult result = build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":releaseJar").getOutcome(), result.getOutput());
        assertFolderHoldsCurrentJar();
        assertFalse(Files.exists(release.resolve("stray.txt")), "a file that is not the release is left in the release folder");
    }

    @Test
    void aSecondBuildWithNothingChangedIsUpToDateAndAChangedJarIsCopiedAgain() throws IOException, NoSuchAlgorithmException {
        writeBuild("first");
        assertEquals(TaskOutcome.SUCCESS, build().task(":releaseJar").getOutcome());

        BuildResult unchanged = build();
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":releaseJar").getOutcome(), unchanged.getOutput());

        writeBuild("second");
        BuildResult changed = build();
        assertEquals(TaskOutcome.SUCCESS, changed.task(":releaseJar").getOutcome(), changed.getOutput());
        assertFolderHoldsCurrentJar();
    }

    /** build/release holds the jar the bootJar made, byte for byte, and the checksum file {@code <hex>  <name>} of that jar. */
    private void assertFolderHoldsCurrentJar() throws IOException, NoSuchAlgorithmException {
        Path made = project.resolve("build/libs").resolve(JAR);
        Path release = project.resolve("build/release");
        assertArrayEquals(Files.readAllBytes(made), Files.readAllBytes(release.resolve(JAR)), "the release jar is not the bootJar's");
        String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(made)));
        assertEquals(hex + "  " + JAR + "\n", Files.readString(release.resolve(JAR + ".sha256"), StandardCharsets.UTF_8));
    }

    /** The fixture build: a bootJar of one file with the given content, and the script of the module. */
    private void writeBuild(String content) throws IOException {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"release-jar-fixture\"\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("build.gradle.kts"), """
                plugins {
                    java
                }
                version = "6.0.0"
                tasks.register<Jar>("bootJar") {
                    archiveFileName = "sennard-${project.version}.jar"
                    from(layout.projectDirectory.dir("content"))
                    isPreserveFileTimestamps = false
                    isReproducibleFileOrder = true
                }
                apply(from = "release-jar.gradle.kts")
                """, StandardCharsets.UTF_8);
        Files.copy(SCRIPT, project.resolve("release-jar.gradle.kts"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path file = project.resolve("content/content.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** Runs the fixture build with the configuration cache, as the build of the repository does (java/gradle.properties); it must succeed. */
    private BuildResult build() {
        return GradleRunner.create().withProjectDir(project.toFile()).withArguments(":releaseJar", "--configuration-cache", "--console=plain", "--no-watch-fs").build();
    }
}
