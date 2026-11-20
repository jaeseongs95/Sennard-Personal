// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

import io.github.jaeseongs95.sennard.domain.error.GateCode;
import io.github.jaeseongs95.sennard.domain.error.GateRefusedException;
import io.github.jaeseongs95.sennard.personal.Sentences;

/**
 * An uninstall started from a jar inside the install root is refused before anything changes, on every OS: the program that runs it is removed with the folder (and a file system
 * may keep a running program's files), so the same input gives the same answer wherever the JVM runs. An uninstall from a jar outside the install root goes on.
 *
 * <pre>
 * u1  inside    the running jar is an installed version's own jar (versions/&lt;v&gt;/sennard-&lt;v&gt;.jar), or any jar below the root: refused, nothing run, nothing removed
 * u2  outside   the running jar is outside the root: the uninstall goes on, here to the end for an empty root and for an installed one (stopping no server, removing the root)
 * u3  spelling  the root may be spelled differently from the folder the jar runs in (a symbolic link): the answer is about the real folders, so it is the same
 * </pre>
 */
class UninstallFromInsideTest {
    @TempDir
    Path temporary;

    private InstallEnvironment environment(Path root, List<List<String>> ran) {
        Commands recorder = (argv, environment, timeout) -> {
            ran.add(argv);
            return new Commands.Result(0, "");
        };
        return new InstallEnvironment(Map.of("SENNARD_INSTALL_ROOT", root.toString()), temporary, () -> temporary.resolve("jdk").resolve("bin").resolve("java"), recorder, StandardCharsets.UTF_8);
    }

    private static Installer installer(InstallEnvironment environment, Path runningJar, OutputStream out) {
        return new Installer(environment, RunningProgram.fromJar(runningJar), "6.0.0", new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    private static Path jar(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "a jar");
    }

    /** U1: the jar that runs is an installed version's own, so it is inside the root it is a version of: refused, nothing run and nothing removed. */
    @Test
    void anUninstallRunFromAnInstalledVersionsJarIsRefused() throws Exception {
        Path root = temporary.resolve("root");
        Path version = Files.createDirectories(root.resolve("versions/6.0.0"));
        Path jar = jar(version.resolve("sennard-6.0.0.jar"));
        List<List<String>> ran = new ArrayList<>();

        GateRefusedException refused = assertThrows(GateRefusedException.class, () -> installer(environment(root, ran), jar, OutputStream.nullOutputStream()).uninstall());

        assertEquals(GateCode.UNINSTALL_FROM_INSTALLED_COPY, refused.code(), Sentences.english(refused));
        assertEquals(List.of(), ran, "nothing was run");
        assertTrue(Files.isRegularFile(jar) && Files.isDirectory(version), "nothing was removed");
        assertTrue(Sentences.english(refused).contains(jar.toString()) && Sentences.english(refused).contains(root.toString()), Sentences.english(refused));
    }

    /** U1: a release jar that was put somewhere below the root is inside it as well, whatever the setting that names the root says. */
    @Test
    void anUninstallRunFromAnyJarBelowTheRootIsRefused() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("root"));
        Path jar = jar(root.resolve("downloads").resolve("sennard-6.0.0.jar"));
        Path version = Files.createDirectories(root.resolve("versions/5.9.0"));
        Files.writeString(version.resolve("sennard-5.9.0.jar"), "another version's jar");

        GateRefusedException refused = assertThrows(GateRefusedException.class,
                () -> installer(environment(root, new ArrayList<>()), jar, OutputStream.nullOutputStream()).uninstall());

        assertEquals(GateCode.UNINSTALL_FROM_INSTALLED_COPY, refused.code(), Sentences.english(refused));
        assertTrue(Files.isDirectory(version), "nothing was removed");
    }

    /** U2: the jar that runs is outside the install root: the uninstall goes on (here to the end: the root holds no version). */
    @Test
    void anUninstallRunFromOutsideAnEmptyRootGoesOn() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("root"));
        Path jar = jar(temporary.resolve("downloads").resolve("sennard-6.0.0.jar"));
        ByteArrayOutputStream printed = new ByteArrayOutputStream();

        installer(environment(root, new ArrayList<>()), jar, printed).uninstall();

        assertTrue(printed.toString(StandardCharsets.UTF_8).contains("Sennard is not installed."), printed.toString(StandardCharsets.UTF_8));
    }

    /**
     * U2: from outside, an installed root is removed whole: the server it names is stopped (none runs, so nothing is asked), the definitions written into host plugins are put back
     * (none were), and the root with its versions goes. The state folder stays.
     */
    @Test
    void anUninstallRunFromOutsideAnInstalledRootRemovesTheRootAndKeepsTheStateFolder() throws Exception {
        Path root = temporary.resolve("root");
        Path version = Files.createDirectories(root.resolve("versions/6.0.0"));
        jar(version.resolve("sennard-6.0.0.jar"));
        Path outside = jar(temporary.resolve("downloads").resolve("sennard-6.0.0.jar"));
        List<List<String>> ran = new ArrayList<>();
        ByteArrayOutputStream printed = new ByteArrayOutputStream();

        installer(environment(root, ran), outside, printed).uninstall();

        String said = printed.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("Removed " + root), said);
        assertFalse(Files.exists(root), "the installation is removed");
        assertTrue(said.contains("The state folder stays: "), "the state folder is named as kept: " + said);
        assertTrue(Files.isRegularFile(outside), "the jar that ran is outside and stays");
        assertEquals(List.of(), ran, "the uninstall runs no java command");
    }

    /**
     * U3: a real symbolic link in a test-only NIO provider names the folder holding the jar. The product uses its JDK path operations to resolve the alias and refuse the uninstall.
     * This portable unit fixture needs no native link-creation privilege; it does not verify the host's default provider, Windows junctions or 8.3 names. Creation errors remain failures.
     */
    @Test
    void anInstallRootSpelledThroughAnotherNameIsStillTheFolderTheJarRunsIn() throws Exception {
        try (FileSystem files = Jimfs.newFileSystem(Configuration.unix())) {
            Path home = Files.createDirectories(files.getPath("/home"));
            Path realRoot = Files.createDirectories(files.getPath("/real-root"));
            Path jar = jar(realRoot.resolve("downloads").resolve("sennard-6.0.0.jar"));
            Path version = Files.createDirectories(realRoot.resolve("versions/5.9.0"));
            Path namedRoot = home.resolve(InstallEnvironment.HOME_FOLDER).resolve("install");
            Files.createDirectories(namedRoot.getParent());
            Files.createSymbolicLink(namedRoot, realRoot);
            List<List<String>> ran = new ArrayList<>();
            Commands recorder = (argv, environment, timeout) -> {
                ran.add(argv);
                return new Commands.Result(0, "");
            };
            InstallEnvironment environment = new InstallEnvironment(Map.of(), home, () -> home.resolve("jdk").resolve("bin").resolve("java"), recorder, StandardCharsets.UTF_8);
            try {
                assertEquals(namedRoot, environment.installRoot(), "the home-based root preserves the NIO provider");
                assertTrue(Files.isSymbolicLink(namedRoot), "the alternate name is an actual symbolic link");
                assertTrue(Files.isSameFile(namedRoot, realRoot), "both names identify the same folder");
                assertFalse(jar.startsWith(namedRoot), "the jar is not lexically below the alternate root");
                GateRefusedException refused = assertThrows(GateRefusedException.class, () -> installer(environment, jar, OutputStream.nullOutputStream()).uninstall());
                assertEquals(GateCode.UNINSTALL_FROM_INSTALLED_COPY, refused.code(), Sentences.english(refused));
                assertEquals(List.of(), ran, "nothing was run");
                assertTrue(Files.isRegularFile(jar) && Files.isDirectory(version), "nothing was removed");
            } finally {
                Files.deleteIfExists(namedRoot); // remove the symbolic link itself
            }
            assertTrue(Files.isRegularFile(jar) && Files.isDirectory(version), "removing the alias preserves its target");
        }
    }
}
