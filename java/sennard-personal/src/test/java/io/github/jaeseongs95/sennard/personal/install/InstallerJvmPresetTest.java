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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.jvm.JvmPreset;
import io.github.jaeseongs95.sennard.personal.Sentences;
import io.github.jaeseongs95.sennard.personal.SennardPersonalApplication;

/**
 * The installer's choice of the server's JVM preset, from one memory reading, and where the choice goes: the file {@code sennard-jvm.yaml} of the installed version, and from there the
 * words that start the server ({@link JvmOptions#server}) and its training run ({@link JvmOptions#training}). The installer runs on stand-ins for the JDK's programs (the release
 * jar's extraction makes the folder the real one makes; the first command after it that is the server's AOT training ends the run), so the cases are about what the installer
 * decides, with the memory and the saved file as the inputs.
 *
 * <pre>
 * i1  case table   readings 3.5 4 6 8 11.9 12 16 24 29.7 32 48 64 96 128 200 GB: the preset saved in sennard-jvm.yaml with the observed bytes, the installer's line saying "estimated
 *                  memory N GB", the options of that preset in the server's training run, and none of them in the hook client's
 * i2  upgrade      an upgrade carries the saved preset (and the user's sennard.yaml) into the new version and does not read the memory; the new version's training run has the
 *                  carried preset's options, and the old version stays until the new server runs
 * i3  saved wins   a version that already holds a preset (the admin page wrote it) starts its server with it and the memory is not read at all
 * i4  start        a version with no preset chooses one at its first start, saves it, and starts the server with it, in the installation's folder and with the command's environment
 * i5  no reading   a machine whose memory is not reported fails the install with the file to write, instead of a guessed preset
 * </pre>
 */
class InstallerJvmPresetTest {
    /** The code of the failure the stand-in for the training run throws: the install ends there, after the preset is saved. */
    private static final UnavailableCode STOP = UnavailableCode.COMMAND_NOT_STARTED;
    /** The option only the server's training run has: the hook client's training records too. */
    private static final String SERVER_TRAINING = "-Dspring.context.exit=onRefresh";

    @TempDir
    Path temporary;

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();

    private Path java() {
        return temporary.resolve("jdk").resolve("bin").resolve("java");
    }

    private PrintStream out() {
        return new PrintStream(printed, true, StandardCharsets.UTF_8);
    }

    /** Every command the installer ran after the extraction, in order; the one that ends the run is the server's training. */
    private static Commands trainingEndsTheRun(String version, List<List<String>> ran) {
        return InstallStandIns.extracting(version, (argv, environment, timeout) -> {
            ran.add(argv);
            if (argv.contains(SERVER_TRAINING)) {
                throw new UnavailableException(STOP, Map.of("command", String.valueOf(argv), "cause", "stopped by the test"));
            }
            return new Commands.Result(1, "stopped by the test"); // the hook client's training: a failed one is reported and does not stop the install
        });
    }

    private static ServerStarter noServerStart() {
        return (argv, workingDirectory, environment) -> {
            throw new AssertionError("no server is started in this case: " + argv);
        };
    }

    private InstallEnvironment environment(Path root, Commands commands, ServerStarter starter, HostMemory memory) {
        return new InstallEnvironment(Map.of("SENNARD_INSTALL_ROOT", root.toString()), temporary.resolve("home"), this::java, commands, starter, memory, StandardCharsets.UTF_8);
    }

    private static List<String> serverTraining(JvmPreset preset, InstalledVersion version, Path java) {
        return JvmOptions.command(java, JvmOptions.training(preset, version), version.appJar(), SennardPersonalApplication.class.getName());
    }

    /** What an install on a fresh folder did up to the server's training: the version that was made and the commands that were run, the last of which is the training. */
    private record Reached(InstalledVersion installed, List<List<String>> ran) {
        List<String> serverTraining() {
            return ran.getLast();
        }

        List<String> hookClientTraining() {
            return ran.getFirst();
        }
    }

    /** Runs install() on a fresh root up to the server's training run. */
    private Reached installWith(String name, HostMemory memory) throws IOException {
        Path root = temporary.resolve(name).resolve("root");
        Path jar = InstallStandIns.releaseJar(temporary.resolve(name).resolve("download").resolve("sennard-6.0.0.jar"));
        List<List<String>> ran = new ArrayList<>();
        Installer installer = new Installer(environment(root, trainingEndsTheRun("6.0.0", ran), noServerStart(), memory), RunningProgram.fromJar(jar), "6.0.0", out());
        UnavailableException stopped = assertThrows(UnavailableException.class, () -> installer.install(null));
        if (stopped.code() != STOP) {
            throw stopped;
        }
        return new Reached(new InstalledVersion(root.resolve("versions").resolve("6.0.0")), ran);
    }

    @ParameterizedTest
    @CsvSource({
        "3.5,G1_224M,4", "4,G1_224M,4", "6,G1_512M,8", "8,G1_512M,8", "11.9,G1_512M,12", "12,G1_512M,12", "16,G1_1G,16", "24,G1_1G,24", "29.7,G1_2G,32", "32,G1_2G,32",
        "48,G1_2G,48", "64,G1_4G,64", "96,G1_4G,96", "128,ZGC_6_PERCENT,128", "200,ZGC_6_PERCENT,256"
    })
    void theInstallerSavesTheDefaultPresetOfTheReadingAndTrainsTheServerWithIt(double readingGigabytes, JvmPreset expected, long estimated) throws IOException {
        long bytes = (long) (readingGigabytes * (1L << 30));
        Reached reached = installWith("gb-" + readingGigabytes, () -> bytes);

        assertEquals(new JvmSettingsFile.Content(expected, bytes), JvmSettingsFile.read(reached.installed().jvmSettingsFile()).orElseThrow());
        assertEquals(serverTraining(expected, reached.installed(), java()), reached.serverTraining(), "the options of the saved preset are the training's");
        assertTrue(reached.serverTraining().containsAll(expected.options()) && reached.serverTraining().contains("-Duser.timezone=UTC"), reached.serverTraining().toString());
        assertTrue(reached.hookClientTraining().stream().noneMatch(expected.options()::contains), "the hook client's own options do not follow the preset: " + reached.hookClientTraining());
        String said = printed.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("JVM preset " + expected + ": estimated memory " + estimated + " GB"), said);
    }

    @Test
    void anUpgradeCarriesTheSavedPresetAndTheUsersSettingsAndDoesNotReadTheMemory() throws IOException {
        Path root = temporary.resolve("upgrade").resolve("root");
        InstalledVersion previous = new InstalledVersion(root.resolve("versions").resolve("6.0.0"));
        InstallStandIns.installedRuntime(previous.folder(), previous.version());
        JvmSettingsFile.Content chosen = new JvmSettingsFile.Content(JvmPreset.ZGC_4G, 8L << 30);
        JvmSettingsFile.write(previous.jvmSettingsFile(), chosen);
        Files.writeString(previous.settingsFile(), "sennard:\n  scheduled-probes: false\n", StandardCharsets.UTF_8);
        Path jar = InstallStandIns.releaseJar(temporary.resolve("upgrade").resolve("download").resolve("sennard-6.1.0.jar"));
        List<List<String>> ran = new ArrayList<>();
        HostMemory neverRead = () -> {
            throw new AssertionError("the memory must not be read when the version carries a preset");
        };
        Installer installer = new Installer(environment(root, trainingEndsTheRun("6.1.0", ran), noServerStart(), neverRead), RunningProgram.fromJar(jar), "6.1.0", out());

        UnavailableException stopped = assertThrows(UnavailableException.class, installer::upgrade);

        assertEquals(STOP, stopped.code(), Sentences.english(stopped));
        InstalledVersion upgraded = new InstalledVersion(root.resolve("versions").resolve("6.1.0"));
        assertEquals(chosen, JvmSettingsFile.read(upgraded.jvmSettingsFile()).orElseThrow(), "not put over by a computed one");
        assertEquals("sennard:\n  scheduled-probes: false\n", Files.readString(upgraded.settingsFile(), StandardCharsets.UTF_8), "the user's file is carried too");
        assertEquals(serverTraining(JvmPreset.ZGC_4G, upgraded, java()), ran.getLast());
        assertTrue(ran.getLast().contains("-XX:+UseZGC") && ran.getLast().contains("-Xmx4g"), ran.getLast().toString());
        assertFalse(printed.toString(StandardCharsets.UTF_8).contains("estimated memory"), "nothing was estimated");
        assertTrue(Files.isRegularFile(previous.appJar()), "the old version stays until the new server runs");
    }

    /** What a start of the installed server was asked to run: the words, the folder it runs in and the environment it was given. */
    private record Started(List<String> argv, Path workingDirectory, Map<String, String> environment) {
    }

    /** Runs start() on an installed version and returns what the server start was asked to do (the stand-in for it ends the run). */
    private Started startOf(Path root, HostMemory memory) {
        List<Started> starts = new ArrayList<>();
        ServerStarter recorder = (argv, workingDirectory, environment) -> {
            starts.add(new Started(argv, workingDirectory, environment));
            throw new UnavailableException(UnavailableCode.START_FAILED, Map.of("what", "other"));
        };
        Installer installer = new Installer(environment(root, (argv, environment, timeout) -> {
            throw new AssertionError("start runs no java command: " + argv);
        }, recorder, memory), RunningProgram.elsewhere(), "6.0.0", out());
        UnavailableException stopped = assertThrows(UnavailableException.class, installer::start);
        assertEquals(UnavailableCode.START_FAILED, stopped.code());
        assertEquals(1, starts.size(), starts.toString());
        return starts.getFirst();
    }

    private static InstalledVersion installedVersion(Path root) throws IOException {
        InstalledVersion version = new InstalledVersion(root.resolve("versions").resolve("6.0.0"));
        InstallStandIns.installedRuntime(version.folder(), version.version());
        return version;
    }

    @Test
    void aPresetTheVersionAlreadyHoldsStartsTheServerAndTheMemoryIsNotRead() throws IOException {
        Path root = temporary.resolve("saved").resolve("root");
        InstalledVersion version = installedVersion(root);
        JvmSettingsFile.Content chosen = new JvmSettingsFile.Content(JvmPreset.ZGC_4G, 8L << 30);
        JvmSettingsFile.write(version.jvmSettingsFile(), chosen);
        HostMemory neverRead = () -> {
            throw new AssertionError("the memory must not be read when the version holds a preset");
        };

        Started started = startOf(root, neverRead);

        assertEquals(chosen, JvmSettingsFile.read(version.jvmSettingsFile()).orElseThrow(), "not put over by a computed one");
        assertEquals(JvmOptions.command(java(), JvmOptions.server(JvmPreset.ZGC_4G, version), version.appJar(), SennardPersonalApplication.class.getName()), started.argv());
        assertTrue(started.argv().contains("-XX:+UseZGC") && started.argv().contains("-Xmx4g") && started.argv().contains("-XX:AOTCache=" + version.folder().resolve("app-zgc.aot")), started.argv().toString());
        assertFalse(printed.toString(StandardCharsets.UTF_8).contains("estimated memory"), "nothing was estimated");
    }

    @Test
    void aVersionWithNoPresetChoosesOneAtItsFirstStartSavesItAndStartsTheServerWithIt() throws IOException {
        Path root = temporary.resolve("first").resolve("root");
        InstalledVersion version = installedVersion(root);
        long bytes = 16L << 30;

        Started started = startOf(root, () -> bytes);

        assertEquals(new JvmSettingsFile.Content(JvmPreset.G1_1G, bytes), JvmSettingsFile.read(version.jvmSettingsFile()).orElseThrow());
        assertEquals(JvmOptions.command(java(), JvmOptions.server(JvmPreset.G1_1G, version), version.appJar(), SennardPersonalApplication.class.getName()), started.argv());
        assertEquals(root.toAbsolutePath().normalize(), started.workingDirectory().toAbsolutePath().normalize(), "the server runs in the installation's folder: its JVM logs are relative names there");
        assertEquals(Map.of("SENNARD_INSTALL_ROOT", root.toString()), started.environment(), "the server gets the environment of the command");
        assertTrue(printed.toString(StandardCharsets.UTF_8).contains("JVM preset G1_1G: estimated memory 16 GB"), printed.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aMachineThatReportsNoMemoryFailsTheInstallInsteadOfGuessing() throws IOException {
        Path root = temporary.resolve("none").resolve("root");
        Path jar = InstallStandIns.releaseJar(temporary.resolve("none").resolve("download").resolve("sennard-6.0.0.jar"));
        List<List<String>> ran = new ArrayList<>();
        Installer installer = new Installer(environment(root, trainingEndsTheRun("6.0.0", ran), noServerStart(), () -> 0L), RunningProgram.fromJar(jar), "6.0.0", out());

        UnavailableException failure = assertThrows(UnavailableException.class, () -> installer.install(null));

        assertEquals(UnavailableCode.MEMORY_UNKNOWN, failure.code());
        String sentence = Sentences.english(failure);
        assertTrue(sentence.contains("sennard-jvm.yaml") && sentence.contains("sennard.jvm.preset"), sentence);
        assertEquals(List.of(), ran, "the training did not run with a preset that was guessed");
    }
}
