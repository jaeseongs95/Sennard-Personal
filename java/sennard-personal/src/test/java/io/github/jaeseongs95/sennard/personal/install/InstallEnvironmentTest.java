// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.personal.Sentences;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where an installation lives and what it does with a path, without asking which operating system this is: the folders come from the home folder the environment is given
 * ({@code user.home}) and from {@code SENNARD_INSTALL_ROOT}, and whether a path can be given to a {@code java} command is the encoder's answer for the encoding the environment is
 * given, so the cases name the encoding and the same input gives the same result wherever the test runs.
 *
 * <pre>
 * e1  root      the install root is SENNARD_INSTALL_ROOT, else &lt;home&gt;/.sennard/install; a blank setting is no setting; the home is the one given, not USERPROFILE or HOME
 * e2  code      a path the file-name encoding cannot encode is not representable; one it can is, whatever its letters
 * e3  refused   an install root the encoding cannot write (the setting or the default under the home) is INSTALL_ROOT_NOT_REPRESENTABLE, in both languages with the way out
 * e4  state     a state folder text that is no path, or that the encoding cannot carry, as typed or as the absolute path it becomes, is refused with the cause kept
 * e5  command   the command to run Sennard's commands with is the java on the newest installed version's jar, quoted only when the path has a space; a running installed jar names its own
 *               installation wherever the setting points
 * s1  status    an install root with nothing installed reports no versions, no command and no server, and prints as JSON without the null fields
 * </pre>
 */
class InstallEnvironmentTest {
    @TempDir
    Path temporary;

    private InstallEnvironment environment(Map<String, String> variables, Path home, Charset encoding) {
        return new InstallEnvironment(variables, home, () -> temporary.resolve("jdk").resolve("bin").resolve("java"), (argv, environment, timeout) -> {
            throw new AssertionError("no command runs in these cases: " + argv);
        }, encoding);
    }

    private static Installer installer(InstallEnvironment environment, RunningProgram running) {
        return new Installer(environment, running, "6.0.0", new PrintStream(new ByteArrayOutputStream()));
    }

    @Test
    void theInstallRootComesFromTheSettingElseFromTheHomeFolderThatWasGiven() {
        Path configured = temporary.resolve("configured");
        Path home = temporary.resolve("home");
        assertEquals(configured, environment(Map.of("SENNARD_INSTALL_ROOT", configured.toString()), home, StandardCharsets.UTF_8).installRoot());
        assertEquals(home.resolve(".sennard").resolve("install"), environment(Map.of(), home, StandardCharsets.UTF_8).installRoot());
        assertEquals(home.resolve(".sennard").resolve("install"), environment(Map.of("SENNARD_INSTALL_ROOT", " "), home, StandardCharsets.UTF_8).installRoot(), "a blank setting is none");
        assertEquals(home.resolve(".sennard").resolve("install"), environment(Map.of("USERPROFILE", temporary.resolve("profile").toString(), "HOME", temporary.resolve("unix").toString(),
                "LOCALAPPDATA", temporary.resolve("local").toString(), "ProgramData", temporary.resolve("data").toString()), home, StandardCharsets.UTF_8).installRoot(),
                "the home is user.home, which is what the environment is given: no variable of an OS moves the root");
        assertEquals(".sennard", InstallEnvironment.HOME_FOLDER);
    }

    @Test
    void aPathIsRepresentableWhenTheFileNameEncodingCanEncodeIt() {
        assertTrue(environment(Map.of(), temporary, StandardCharsets.UTF_8).representable(Path.of("C:/설치-é-😀")));
        assertFalse(environment(Map.of(), temporary, StandardCharsets.US_ASCII).representable(Path.of("/home/설치")));
        assertTrue(environment(Map.of(), temporary, StandardCharsets.US_ASCII).representable(Path.of("/home/plain")));
        assertFalse(environment(Map.of(), temporary, StandardCharsets.US_ASCII).representable(Path.of("/home/é")));
        assertTrue(environment(Map.of(), temporary, StandardCharsets.ISO_8859_1).representable(Path.of("/home/é")), "a letter the encoding has is representable");
        assertFalse(environment(Map.of(), temporary, StandardCharsets.ISO_8859_1).representable(Path.of("/home/설치")));
    }

    @Test
    void anInstallRootTheEncodingCannotWriteIsRefusedWithTheWayOut() {
        Path foreign = temporary.resolve("설치-é-😀");
        InstallEnvironment ascii = environment(Map.of("SENNARD_INSTALL_ROOT", foreign.toString()), temporary, StandardCharsets.US_ASCII);

        InvalidInputException refused = assertThrows(InvalidInputException.class, ascii::installRoot);

        assertEquals(InputCode.INSTALL_ROOT_NOT_REPRESENTABLE, refused.code());
        for (String sentence : List.of(Sentences.english(refused), Sentences.korean(refused))) {
            assertTrue(sentence.contains(foreign.toString()) && sentence.contains("SENNARD_INSTALL_ROOT"), "the folder and the setting that names another: " + sentence);
        }
        assertEquals(foreign, environment(Map.of("SENNARD_INSTALL_ROOT", foreign.toString()), temporary, StandardCharsets.UTF_8).installRoot(), "the same folder is accepted by an encoding that has its letters");
    }

    @Test
    void theDefaultRootUnderAHomeTheEncodingCannotWriteIsRefusedToo() {
        Path foreignHome = temporary.resolve("사용자");
        InvalidInputException refused = assertThrows(InvalidInputException.class, () -> environment(Map.of(), foreignHome, StandardCharsets.US_ASCII).installRoot());
        assertEquals(InputCode.INSTALL_ROOT_NOT_REPRESENTABLE, refused.code());
        assertEquals(foreignHome.resolve(".sennard").resolve("install"), environment(Map.of(), foreignHome, StandardCharsets.UTF_8).installRoot());
    }

    @Test
    void aRootThatIsCheckedIsGivenBackAsItIsOrRefused() {
        Path plain = temporary.resolve("plain");
        InstallEnvironment ascii = environment(Map.of(), temporary, StandardCharsets.US_ASCII);
        assertSame(plain, ascii.checked(plain));
        assertEquals(InputCode.INSTALL_ROOT_NOT_REPRESENTABLE, assertThrows(InvalidInputException.class, () -> ascii.checked(temporary.resolve("é"))).code());
    }

    /** A state folder text that is no path at all is refused with the cause kept, like one the code page cannot carry. */
    @Test
    void aStateFolderTextThatIsNoPathIsRefusedWithItsCause() {
        Installer installer = installer(environment(Map.of(), temporary, StandardCharsets.UTF_8), RunningProgram.elsewhere());
        InvalidInputException refused = assertThrows(InvalidInputException.class, () -> installer.checkedStateDirectory("a\0b", temporary));
        assertEquals(InputCode.STATE_DIR_NOT_REPRESENTABLE, refused.code());
        assertTrue(refused.getCause() instanceof java.nio.file.InvalidPathException, String.valueOf(refused.getCause()));
    }

    /**
     * The second check of the state folder (the absolute path it becomes) is decided by the folder the command runs in, not by the folder this test runs in: the folder is
     * handed in, so the result is the same wherever the test is started.
     */
    @Test
    void aPlainRelativeStateFolderIsRefusedWhenTheFolderTheCommandRunsInHasANameTheEncodingCannotWrite() {
        Installer ascii = installer(environment(Map.of(), temporary, StandardCharsets.US_ASCII), RunningProgram.elsewhere());
        Path foreign = temporary.resolve("작업-é-😀");
        InvalidInputException refused = assertThrows(InvalidInputException.class, () -> ascii.checkedStateDirectory("state", foreign));
        assertEquals(InputCode.STATE_DIR_NOT_REPRESENTABLE, refused.code());
        assertThrows(InvalidInputException.class, () -> ascii.checkedStateDirectory("sub/../state", foreign));
        assertThrows(InvalidInputException.class, () -> ascii.checkedStateDirectory("state-é", temporary), "the text itself is refused too");

        Installer utf8 = installer(environment(Map.of(), temporary, StandardCharsets.UTF_8), RunningProgram.elsewhere());
        assertEquals(foreign.resolve("state").toAbsolutePath().normalize().toString(), utf8.checkedStateDirectory("sub/../state", foreign));
        assertEquals(temporary.resolve("abs").toString(), utf8.checkedStateDirectory(temporary.resolve("x/../abs").toString(), foreign), "an absolute path does not take the working folder");
    }

    @Test
    void theCommandIsTheJavaOnTheNewestInstalledJarAndAPathWithASpaceIsQuoted() throws IOException {
        Path root = temporary.resolve("root");
        Path older = installed(root, "6.0.0");
        Path newer = installed(root, "6.1.0");
        InstallEnvironment environment = environment(Map.of("SENNARD_INSTALL_ROOT", root.toString()), temporary.resolve("home"), StandardCharsets.UTF_8);

        assertEquals(Optional.of("java -jar " + newer.resolve("sennard-6.1.0.jar")), Installer.installedCommand(environment, RunningProgram.elsewhere()), "the newest version's jar, not the older " + older);

        Path spaced = installed(temporary.resolve("a root"), "6.0.0");
        InstallEnvironment withSpace = environment(Map.of("SENNARD_INSTALL_ROOT", temporary.resolve("a root").toString()), temporary.resolve("home"), StandardCharsets.UTF_8);
        assertEquals(Optional.of("java -jar \"" + spaced.resolve("sennard-6.0.0.jar") + "\""), Installer.installedCommand(withSpace, RunningProgram.elsewhere()));
    }

    /** An installed jar that runs belongs to its own installation, wherever {@code SENNARD_INSTALL_ROOT} points: a command of the old installation is not run against a new setting's. */
    @Test
    void anInstalledJarThatRunsNamesItsOwnInstallationWhateverTheSettingSays() throws IOException {
        Path rootOfTheJar = temporary.resolve("installation-of-the-jar");
        Path version = installed(rootOfTheJar, "6.0.0");
        Path settingsRoot = Files.createDirectories(temporary.resolve("another-root"));
        InstallEnvironment environment = environment(Map.of("SENNARD_INSTALL_ROOT", settingsRoot.toString()), temporary.resolve("home"), StandardCharsets.UTF_8);

        assertEquals(Optional.of("java -jar " + version.resolve("sennard-6.0.0.jar")), Installer.installedCommand(environment, RunningProgram.fromJar(version.resolve("sennard-6.0.0.jar"))));
        assertEquals(Optional.empty(), Installer.installedCommand(environment, RunningProgram.elsewhere()), "without a running installed jar the setting's root is the installation, and it holds none");
        assertEquals(Optional.empty(), Installer.installedCommand(environment, RunningProgram.fromJar(temporary.resolve("downloads").resolve("sennard-6.0.0.jar"))), "a release jar that runs where it was put");
    }

    /** The folder {@code <root>/versions/<version>} with its application jar: what makes a version installed. */
    private static Path installed(Path root, String version) throws IOException {
        Path folder = Files.createDirectories(root.resolve("versions").resolve(version));
        InstallStandIns.installedRuntime(folder, version);
        return folder;
    }

    @Test
    void anInstallRootWithNothingInstalledHasNoVersionsNoCommandAndNoServer() throws Exception {
        Path root = temporary.resolve("empty-root");
        Files.createDirectories(root.resolve("versions"));
        InstallEnvironment environment = environment(Map.of("SENNARD_INSTALL_ROOT", root.toString()), temporary.resolve("home"), StandardCharsets.UTF_8);
        Installer.Status status = installer(environment, RunningProgram.elsewhere()).status();
        assertEquals(new Installer.Status(root.toString(), null, List.of(), null, null, null, null, null, null), status);
        assertEquals(Optional.empty(), Installer.installedCommand(environment, RunningProgram.elsewhere()));
        JsonMapper json = JsonMapper.builder().build();
        String printed = json.writeValueAsString(status);
        assertFalse(printed.contains("null") || printed.contains("command") || printed.contains("healthy") || printed.contains("hostCopiesWithoutDefinitions"), printed);
        assertEquals(status, json.readValue(printed, Installer.Status.class));
    }
}
