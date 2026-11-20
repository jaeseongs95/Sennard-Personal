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
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.personal.Sentences;

/**
 * {@code sennard install --state-dir} records the state folder in the installed version's {@code sennard.yaml} as the absolute path of the folder the command runs in: the server
 * resolves {@code sennard.state-dir} against its own working folder, which is not necessarily that one. A folder the file-name encoding cannot write is refused before anything is made.
 * (Until the embedded PostgreSQL was removed the install also refused a folder whose names held a character a shell interprets inside double quotes, because initdb passed the path to a
 * shell; with no initdb there is nothing left to refuse.)
 *
 * <pre>
 * d1  relative      a relative --state-dir is recorded as the absolute path it is from the folder the install runs in, normalized
 * d2  characters    a folder with characters a shell would read is a name like any other: recorded, not refused
 * d3  settings file the file is the installed version's own settings file (InstalledVersion#settingsFile), written by the install and read back as YAML
 * d4  not writable  a state folder the encoding cannot write is refused (STATE_DIR_NOT_REPRESENTABLE) before the release is extracted: no version folder is made
 * d5  absolute      the recording of an absolute and a relative text
 * </pre>
 *
 * The install is run on a small release jar made here and on commands that stand in for the JDK's: the extraction makes the folder the real one makes, and the first command after it
 * (the AOT training) ends the run, so that nothing after the state folder's recording is part of the case.
 */
class StateDirectoryRecordTest {
    /** Thrown by the stand-in for the commands: the AOT training is the install's first one after sennard.yaml is written. */
    private static final UnavailableCode STOP = UnavailableCode.COMMAND_NOT_STARTED;

    @TempDir
    Path temporary;

    /** A release jar of the smallest kind the install accepts: it holds the content folder the install copies, and nothing else. */
    private Path releaseJar() throws IOException {
        return InstallStandIns.releaseJar(temporary.resolve("download").resolve("sennard-6.0.0.jar"));
    }

    /** Commands that make the extracted layout where the extraction is told to, and end the run at the first command that is not the extraction. */
    private static Commands extractThenStop(List<List<String>> ran) {
        return InstallStandIns.extracting("6.0.0", (argv, environment, timeout) -> {
            ran.add(argv);
            throw new UnavailableException(STOP, Map.of("command", String.valueOf(argv), "cause", "stopped by the test"));
        });
    }

    private InstallEnvironment environment(Path root, Commands commands, Charset encoding) {
        // The memory is the case's own: the install chooses the server's JVM preset from it before the training, whatever PC this runs on.
        return new InstallEnvironment(Map.of("SENNARD_INSTALL_ROOT", root.toString()), temporary.resolve("home"), () -> temporary.resolve("jdk").resolve("bin").resolve("java"), commands,
                () -> 16L << 30, encoding);
    }

    private SennardException installUpToTheFirstTrainingRun(Path root, String stateDirectory) throws IOException {
        Installer installer = new Installer(environment(root, extractThenStop(new ArrayList<>()), StandardCharsets.UTF_8), RunningProgram.fromJar(releaseJar()), "6.0.0",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        return assertThrows(SennardException.class, () -> installer.install(stateDirectory));
    }

    private static String recordedStateDirectory(Path root) throws IOException {
        Path settings = new InstalledVersion(root.resolve("versions").resolve("6.0.0")).settingsFile();
        Map<String, Object> document = new Yaml().load(Files.readString(settings, StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        Map<String, Object> sennard = (Map<String, Object>) document.get("sennard");
        return (String) sennard.get("state-dir");
    }

    /** install() itself records a relative --state-dir in sennard.yaml as the absolute path taken from the folder it runs in. */
    @Test
    void installRecordsARelativeStateFolderAsTheAbsolutePathOfTheFolderItRunsIn() throws Exception {
        Path root = temporary.resolve("root");
        SennardException stopped = installUpToTheFirstTrainingRun(root, "rel-state/../rel-state/x");
        assertEquals(STOP, stopped.code(), Sentences.english(stopped));
        assertEquals(Path.of("rel-state/x").toAbsolutePath().normalize().toString(), recordedStateDirectory(root));
    }

    /** A character a shell would interpret in double quotes is a name like any other now: the folder is recorded, not refused. */
    @Test
    void aStateFolderWithShellCharactersIsRecordedLikeAnyOther() throws Exception {
        Path root = temporary.resolve("dollar").resolve("root");
        Path state = temporary.resolve("state dir's $y `q`");
        SennardException stopped = installUpToTheFirstTrainingRun(root, state.toString());
        assertEquals(STOP, stopped.code(), Sentences.english(stopped));
        assertEquals(state.toString(), recordedStateDirectory(root));
    }

    /** The recording is in the installed version's own settings file, which is the one the server and the commands read, and holds the state folder and nothing else. */
    @Test
    void theRecordIsTheInstalledVersionsSettingsFileAndHoldsOnlyTheStateFolder() throws Exception {
        Path root = temporary.resolve("recorded").resolve("root");
        Path state = temporary.resolve("kept-state");
        installUpToTheFirstTrainingRun(root, state.toString());

        Path settings = new InstalledVersion(root.resolve("versions").resolve("6.0.0")).settingsFile();
        assertEquals(root.resolve("versions").resolve("6.0.0").resolve("sennard.yaml"), settings);
        Map<String, Object> document = new Yaml().load(Files.readString(settings, StandardCharsets.UTF_8));
        assertEquals(Map.of("sennard", Map.of("state-dir", state.toString())), document);
    }

    /** A relative --state-dir is taken from the folder sennard install runs in; the install records the absolute path. */
    @Test
    void aRelativeStateFolderIsRecordedAsTheAbsolutePathOfTheInstallsFolder() {
        String recorded = Installer.absoluteStateDirectory("rel/../rel/state");
        assertEquals(Path.of("rel/state").toAbsolutePath().normalize().toString(), recorded);
        assertTrue(Path.of(recorded).isAbsolute(), recorded);
        String absolute = temporary.resolve("x/../state").toString();
        assertEquals(temporary.resolve("state").toString(), Installer.absoluteStateDirectory(absolute));
    }

    /**
     * A state folder the file-name encoding cannot write is refused before the release is extracted: the server is started with the folder in a setting that the java command line
     * carries, so a folder it cannot carry would be another folder. The encoding is the environment's, so the case does not depend on the encoding of the PC it runs on.
     */
    @Test
    void aStateFolderTheEncodingCannotWriteIsRefusedBeforeAnythingIsMade() throws Exception {
        Path root = temporary.resolve("refused").resolve("root");
        List<List<String>> ran = new ArrayList<>();
        Installer installer = new Installer(environment(root, extractThenStop(ran), StandardCharsets.US_ASCII), RunningProgram.fromJar(releaseJar()), "6.0.0",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        InvalidInputException refused = assertThrows(InvalidInputException.class, () -> installer.install(temporary.resolve("상태-é").toString()));

        assertEquals(InputCode.STATE_DIR_NOT_REPRESENTABLE, refused.code());
        assertTrue(Sentences.english(refused).contains("SENNARD_STATE_DIR"), "the way out is named: " + Sentences.english(refused));
        assertEquals(List.of(), ran, "no command ran: the release was not extracted");
        assertFalse(Files.exists(root.resolve("versions")), "no version folder was made");
    }
}
