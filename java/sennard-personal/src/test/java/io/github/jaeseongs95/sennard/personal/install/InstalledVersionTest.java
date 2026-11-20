// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.jvm.JvmPreset;

/**
 * {@link InstalledVersion}: the folder {@code <installation>/versions/<version>/} and what is where in it. It is a value of paths, so the cases make the folders and files it names
 * and nothing runs.
 *
 * <pre>
 * v1  folder    the folder is absolute and normalized; its name is the version; every file is named from it
 * v2  at        a folder is a version when its application jar is there; a leftover folder without it is not
 * v3  ofJar     a jar is the application jar of a version only as versions/&lt;v&gt;/sennard-&lt;v&gt;.jar; the same jar anywhere else, or another version's name, is a release that runs where it is
 * v4  ofFolder  the folder a server's sennard.home names is that version
 * v5  root      the installation's folder is two levels above the application jar; a folder that was only extracted has none
 * v6  files     the application jar, the two settings files, the AOT files of the collector and the training's recording are named for the version's folder
 * v7  hook jar  exactly one lib/sennard-hook-client-*.jar is the client's; none, two or no lib folder is INSTALL_FAILED
 * </pre>
 */
class InstalledVersionTest {
    @TempDir
    Path temporary;

    private Path folder(String root, String version) throws IOException {
        return Files.createDirectories(temporary.resolve(root).resolve("versions").resolve(version));
    }

    @Test
    void theFolderIsAbsoluteAndNormalizedAndItsNameIsTheVersion() {
        InstalledVersion version = new InstalledVersion(Path.of("some", "..", "root", "versions", "6.0.0"));
        assertTrue(version.folder().isAbsolute(), version.folder().toString());
        assertEquals(Path.of("root", "versions", "6.0.0").toAbsolutePath().normalize(), version.folder());
        assertEquals("6.0.0", version.version());
        assertEquals(new InstalledVersion(temporary.resolve("a").resolve("..").resolve("b")), new InstalledVersion(temporary.resolve("b")), "a value: the same folder is the same version");
    }

    @Test
    void aFolderIsAVersionWhenItsApplicationJarIsThere() throws IOException {
        Path installed = folder("root", "6.0.0");
        Path leftover = folder("root", ".publishing-6.0.1");
        Files.writeString(installed.resolve("sennard-6.0.0.jar"), "the application jar");

        assertEquals(Optional.of(new InstalledVersion(installed)), InstalledVersion.at(installed));
        assertEquals(Optional.empty(), InstalledVersion.at(leftover), "a leftover of a publish that failed holds no application jar");
        assertEquals(Optional.empty(), InstalledVersion.at(temporary.resolve("root").resolve("versions").resolve("6.0.2")), "a folder that is not there");
        Files.writeString(leftover.resolve("sennard-6.0.0.jar"), "the jar of another version's name");
        assertEquals(Optional.empty(), InstalledVersion.at(leftover), "the application jar is named for the version of the folder");
    }

    @Test
    void aJarIsAnInstalledVersionsApplicationJarOnlyAtItsOwnPlace() throws IOException {
        Path folder = folder("root", "6.0.0");
        Path jar = folder.resolve("sennard-6.0.0.jar");

        assertEquals(Optional.of(new InstalledVersion(folder)), InstalledVersion.ofJar(jar));
        assertEquals(Optional.of(new InstalledVersion(folder)), InstalledVersion.ofJar(folder.resolve("lib").resolve("..").resolve("sennard-6.0.0.jar")), "the jar's path is normalized first");
        assertEquals(Optional.empty(), InstalledVersion.ofJar(folder.resolve("sennard-6.0.1.jar")), "another version's name in this version's folder");
        assertEquals(Optional.empty(), InstalledVersion.ofJar(temporary.resolve("downloads").resolve("sennard-6.0.0.jar")), "a release jar that runs where the user put it");
        assertEquals(Optional.empty(), InstalledVersion.ofJar(temporary.resolve("root").resolve("sennard-6.0.0.jar")), "a jar directly in an installation's folder is not in a versions folder");
        assertEquals(Optional.empty(), InstalledVersion.ofJar(temporary.resolve("elsewhere").resolve("6.0.0").resolve("sennard-6.0.0.jar")), "a folder named for a version, but not under versions");
        assertEquals(Optional.empty(), InstalledVersion.ofJar(Path.of("sennard-6.0.0.jar")), "a relative name has no versions folder above it");
        assertEquals(Optional.empty(), InstalledVersion.ofJar(temporary.getRoot().resolve("sennard-6.0.0.jar")), "a jar at the top of a file system has no folder above it");
    }

    @Test
    void theFolderAServerRunsFromIsThatVersion() throws IOException {
        Path folder = folder("root", "6.0.0");
        assertEquals(new InstalledVersion(folder), InstalledVersion.ofFolder(folder));
        assertEquals("6.0.0", InstalledVersion.ofFolder(folder).version());
    }

    @Test
    void theInstallationsFolderIsTwoLevelsAboveTheJarAndAFolderThatWasOnlyExtractedHasNone() throws IOException {
        Path folder = folder("root", "6.0.0");
        assertEquals(Optional.of(temporary.resolve("root")), new InstalledVersion(folder).installRoot());
        assertEquals(Optional.empty(), new InstalledVersion(Files.createDirectories(temporary.resolve("extracted").resolve("6.0.0"))).installRoot());
        assertEquals(Optional.empty(), new InstalledVersion(temporary.getRoot().resolve("6.0.0")).installRoot(), "a version folder at the top of a file system");
    }

    @Test
    void theFilesAreNamedForTheVersionsFolder() throws IOException {
        Path folder = folder("root", "6.0.1");
        InstalledVersion version = new InstalledVersion(folder);

        assertEquals("sennard-6.0.1.jar", InstalledVersion.jarName("6.0.1"));
        assertEquals(folder.resolve("sennard-6.0.1.jar"), version.appJar());
        assertEquals(folder.resolve("sennard.yaml"), version.settingsFile());
        assertEquals(folder.resolve("sennard-jvm.yaml"), version.jvmSettingsFile());
        assertEquals(folder.resolve("app-g1.aot"), version.aotCache(JvmPreset.G1_2G.collector()));
        assertEquals(folder.resolve("app-zgc.aot"), version.aotCache(JvmPreset.ZGC_6_PERCENT.collector()));
        assertEquals(folder.resolve("app.aotconf"), version.aotConfiguration());
        assertEquals("sennard-hook.aot", JvmOptions.HOOK_AOT_CACHE_FILE);
        assertEquals("sennard.yaml", version.settingsFile().getFileName().toString(), "the user's file; the programs' own settings are in the other one");
    }

    @Test
    void exactlyOneHookClientJarInLibIsTheClients() throws IOException {
        InstalledVersion version = new InstalledVersion(folder("root", "6.0.0"));
        Path lib = Files.createDirectories(version.folder().resolve("lib"));
        Files.writeString(lib.resolve("sennard-personal-6.0.0.jar"), "another module");
        Files.writeString(lib.resolve("sennard-hook-client-6.0.0.jar.sha256"), "not a jar");
        assertEquals(UnavailableCode.INSTALL_FAILED, assertThrows(UnavailableException.class, version::hookClientJar).code(), "no client jar");

        Path client = lib.resolve("sennard-hook-client-6.0.0.jar");
        Files.writeString(client, "the hook client");
        assertEquals(client, version.hookClientJar());

        Files.writeString(lib.resolve("sennard-hook-client-6.0.1.jar"), "another client");
        assertEquals(UnavailableCode.INSTALL_FAILED, assertThrows(UnavailableException.class, version::hookClientJar).code(), "two client jars: the cache and the class path would be for either");
    }

    @Test
    void aVersionWithoutALibFolderHasNoClientJarAndTheCauseIsKept() throws IOException {
        InstalledVersion version = new InstalledVersion(folder("root", "6.0.0"));
        UnavailableException failure = assertThrows(UnavailableException.class, version::hookClientJar);
        assertEquals(UnavailableCode.INSTALL_FAILED, failure.code());
        assertInstanceOf(NoSuchFileException.class, failure.getCause());
    }
}
