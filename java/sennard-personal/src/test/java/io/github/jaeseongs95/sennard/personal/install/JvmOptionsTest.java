// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.jaeseongs95.sennard.domain.jvm.JvmPreset;
import io.github.jaeseongs95.sennard.protocol.ToolProcess;

/**
 * The JVM options of each program an installed version starts, from one preset ({@link JvmOptions}).
 *
 * <pre>
 * o1  server       every preset: UTC, the preset's own options in order, compact headers, the rotated GC log, the AOT cache of the preset's collector, the AOT log; the -Xlog files are
 *                  relative names (the server runs in the installation's folder), with no folder part to carry a path separator or a drive letter's colon into the option
 * o2  cache fails  the cache is in auto mode and the JVM's own log is a file: a missing cache means a start without it, and the reason is in the file
 * o3  cache per GC G1 and ZGC name different files, in the version's folder
 * o4  training     the same collector and heap as the server, recording; the second step makes the collector's own cache
 * o5  hook client  the client's final options: a small serial set that shares none of the preset's options, every JVM log off (-Xlog:disable), AOT mode auto and the cache
 *                  sennard-hook.aot of the version's folder; its training (record into sennard-hook.aotconf, no cache) and creation (create sennard-hook.aot from sennard-hook.aotconf)
 *                  differ from them in the AOT step only; none of the three names a log file or the server's cache app-*.aot
 * o6  no quoting   no option of the client or its two AOT steps has a space, '*', ',' or any other character that a shell or a host's command string would need quoted (the case the
 *                  old {@code -Xlog:aot*=off,cds*=off} was: it had '*' and ',')
 * o7  no 512m      no preset but the 512m one carries -Xmx512m; no option is a fixed heap the preset did not choose
 * o8  command      the words of one JVM start: the program, the options, -cp, the class path, the main class; a value
 * o9  real JVM     the options of every preset start this JDK, with the cache missing, in the folder the server runs in: exit 0, the GC log written there, the missing cache named in the AOT log
 * o10 real JVM     the hook client's options start this JDK with the cache missing: exit 0 and the JVM's own output has no log line ("[warning][aot]" would be a hook's answer to its host)
 * </pre>
 */
class JvmOptionsTest {
    @TempDir
    Path temporary;

    private InstalledVersion version() {
        return new InstalledVersion(temporary.resolve("install").resolve("versions").resolve("6.0.0"));
    }

    private static String file(InstalledVersion version, String name) {
        return version.folder().resolve(name).toString();
    }

    /** The name a {@code -Xlog} option writes to, the text after {@code file=} up to the next colon. */
    private static String logFile(String option) {
        int start = option.indexOf("file=") + "file=".length();
        return option.substring(start, option.indexOf(':', start));
    }

    @ParameterizedTest
    @EnumSource(JvmPreset.class)
    void theServerStartsInUtcWithThePresetsCollectorAndHeapAndItsOwnCacheAndLogs(JvmPreset preset) {
        InstalledVersion version = version();
        List<String> options = JvmOptions.server(preset, version);
        assertEquals("-Duser.timezone=UTC", options.get(0));
        assertEquals(preset.options(), options.subList(1, 1 + preset.options().size()));
        assertEquals("-XX:+UseCompactObjectHeaders", options.get(1 + preset.options().size()));
        assertTrue(options.contains("-Xlog:gc*:file=jvm-gc.log:time,uptime,level,tags:filecount=5,filesize=10m"), options.toString());
        assertTrue(options.contains("-XX:AOTMode=auto"), "a missing or stale cache is not an error: " + options);
        assertTrue(options.contains("-XX:AOTCache=" + file(version, preset.collector().cacheFileName())), options.toString());
        assertTrue(options.contains("-Xlog:aot=info,cds=info:file=jvm-aot.log:time,level,tags:filecount=2,filesize=1m"), options.toString());
        assertEquals(1, options.stream().filter(option -> option.startsWith("-Xmx") || option.startsWith("-XX:MaxRAMPercentage")).count(), "one heap bound: " + options);
    }

    /**
     * The server runs with the installation's folder as its working folder, so a log is a name and not a path: no separator of any OS and no drive letter's colon among the
     * colons that separate the option's parts (the product does not rewrite a path's separators for the JVM).
     */
    @ParameterizedTest
    @EnumSource(JvmPreset.class)
    void theServersLogFilesAreRelativeNamesWithNoFolderPart(JvmPreset preset) {
        List<String> logs = JvmOptions.server(preset, version()).stream().filter(option -> option.startsWith("-Xlog:") && option.contains("file=")).toList();
        assertEquals(2, logs.size(), logs.toString());
        for (String option : logs) {
            String name = logFile(option);
            assertEquals(name, Path.of(name).getFileName().toString(), "a name without a folder: " + option);
            assertFalse(Path.of(name).isAbsolute(), option);
        }
        assertEquals(List.of("jvm-gc.log", "jvm-aot.log"), logs.stream().map(JvmOptionsTest::logFile).toList());
    }

    @Test
    void eachCollectorHasItsOwnCacheFileInTheVersionsFolder() {
        InstalledVersion version = version();
        String g1 = aotCache(JvmOptions.server(JvmPreset.G1_2G, version));
        String zgc = aotCache(JvmOptions.server(JvmPreset.ZGC_6_PERCENT, version));
        assertEquals(version.folder().resolve("app-g1.aot"), Path.of(g1));
        assertEquals(version.folder().resolve("app-zgc.aot"), Path.of(zgc));
        assertNotEquals(g1, zgc);
        assertEquals(g1, aotCache(JvmOptions.server(JvmPreset.G1_224M, version)), "the collector decides the file, not the heap");
    }

    private static String aotCache(List<String> options) {
        return options.stream().filter(option -> option.startsWith("-XX:AOTCache=")).findFirst().orElseThrow().substring("-XX:AOTCache=".length());
    }

    @ParameterizedTest
    @EnumSource(JvmPreset.class)
    void theTrainingRunsWithTheServersCollectorAndHeapAndTheCacheStepMakesTheCollectorsCache(JvmPreset preset) {
        InstalledVersion version = version();
        List<String> training = JvmOptions.training(preset, version);
        assertTrue(training.containsAll(preset.options()), training.toString());
        assertTrue(training.contains("-XX:AOTMode=record") && training.contains("-XX:AOTConfiguration=" + file(version, "app.aotconf")) && training.contains("-Dspring.context.exit=onRefresh"),
                training.toString());
        assertTrue(training.stream().noneMatch(option -> option.startsWith("-XX:AOTCache=")), "recording writes no cache: " + training);
        List<String> creation = JvmOptions.cacheCreation(preset, version);
        assertTrue(creation.containsAll(preset.options()), creation.toString());
        assertTrue(creation.contains("-XX:AOTMode=create") && creation.contains("-XX:AOTConfiguration=" + file(version, "app.aotconf"))
                && creation.contains("-XX:AOTCache=" + file(version, preset.collector().cacheFileName())), creation.toString());
        assertEquals(version.aotConfiguration(), version.folder().resolve("app.aotconf"));
    }

    private static final List<String> CLIENT_BASE = List.of("-XX:+UseSerialGC", "-Xmx256m", "-XX:TieredStopAtLevel=1", "-XX:-UsePerfData", "-Xlog:disable");

    @Test
    void theHookClientsFinalOptionsAreASmallSerialSetWithEveryJvmLogOffAndItsOwnCache() {
        InstalledVersion version = version();
        List<String> client = JvmOptions.hookClient(version);
        assertEquals(CLIENT_BASE, client.subList(0, 5));
        assertEquals(List.of("-XX:AOTMode=auto", "-XX:AOTCache=" + file(version, "sennard-hook.aot")), client.subList(5, 7));
        assertEquals(7, client.size(), client.toString());
        assertEquals("sennard-hook.aot", JvmOptions.HOOK_AOT_CACHE_FILE);
        assertEquals("sennard-hook.aotconf", JvmOptions.HOOK_AOT_CONFIGURATION_FILE);
        assertEquals("-Xlog:disable", JvmOptions.HOOK_LOG_OFF);
        assertTrue(client.stream().noneMatch(option -> option.startsWith("-Xlog:") && option.contains("file=")), "a hook writes no log file: " + client);
        assertFalse(client.contains("-Duser.timezone=UTC"), "the client keeps the machine's zone: it only relays what the host sent");
        for (JvmPreset preset : JvmPreset.values()) {
            assertTrue(client.stream().noneMatch(preset.options()::contains), "the client shares no option with " + preset);
            assertFalse(client.contains("-XX:AOTCache=" + file(version, preset.collector().cacheFileName())), "the server's cache is not the client's: " + preset);
            assertFalse(JvmOptions.server(preset, version).contains("-XX:+UseSerialGC"), preset.toString());
        }
    }

    @Test
    void theHookClientsCacheIsMadeWithItsOwnOptionsAndOnlyTheAotStepDiffers() {
        InstalledVersion version = version();
        List<String> training = JvmOptions.hookClientTraining(version);
        assertEquals(CLIENT_BASE, training.subList(0, 5));
        assertEquals(List.of("-XX:AOTMode=record", "-XX:AOTConfiguration=" + file(version, "sennard-hook.aotconf")), training.subList(5, 7));
        assertEquals(7, training.size(), "recording writes no cache: " + training);
        List<String> creation = JvmOptions.hookClientCacheCreation(version);
        assertEquals(CLIENT_BASE, creation.subList(0, 5));
        assertEquals(List.of("-XX:AOTMode=create", "-XX:AOTConfiguration=" + file(version, "sennard-hook.aotconf"), "-XX:AOTCache=" + file(version, "sennard-hook.aot")), creation.subList(5, 8));
        assertEquals(8, creation.size(), creation.toString());
        List<String> client = JvmOptions.hookClient(version);
        assertEquals(client.subList(0, 5), training.subList(0, 5), "the options before the AOT step are the final options'");
        assertEquals(client.subList(0, 5), creation.subList(0, 5), "the options before the AOT step are the final options'");
    }

    /**
     * The old option of the cache's log, {@code -Xlog:aot*=off,cds*=off}, had {@code *} and {@code ,}: a shell expands the one and a host's command string splits at neither, so it
     * needed quoting. A path is the one word that may hold a space or the OS's separator, so the version's folder is taken out of the options before they are read: what is under test is the
     * options' own text.
     */
    @Test
    void noOptionOfTheHookClientNeedsQuotingInAShellOrAHostsCommandString() {
        InstalledVersion version = version();
        List<String> every = new ArrayList<>();
        every.addAll(JvmOptions.hookClient(version));
        every.addAll(JvmOptions.hookClientTraining(version));
        every.addAll(JvmOptions.hookClientCacheCreation(version));
        String special = " \t*,&|<>^()\"%'`;$\\";
        String cache = file(version, "sennard-hook.aot");
        String configuration = file(version, "sennard-hook.aotconf");
        for (String option : every) {
            String own = option.replace(configuration, "CONFIGURATION").replace(cache, "CACHE");
            assertTrue(own.chars().noneMatch(character -> special.indexOf(character) >= 0), "an option with a character a shell would act on: " + option);
        }
        assertEquals("-XX:AOTCache=" + cache, JvmOptions.hookClient(version).getLast());
    }

    @Test
    void onlyThe512mPresetCarriesA512mHeap() {
        for (JvmPreset preset : JvmPreset.values()) {
            boolean has512 = JvmOptions.server(preset, version()).contains("-Xmx512m");
            assertEquals(preset == JvmPreset.G1_512M, has512, preset.toString());
        }
        assertEquals(List.of("-Xmx224m"), heap(JvmPreset.G1_224M));
        assertEquals(List.of("-XX:MaxRAMPercentage=6"), heap(JvmPreset.ZGC_6_PERCENT));
    }

    private List<String> heap(JvmPreset preset) {
        return JvmOptions.server(preset, version()).stream().filter(option -> option.startsWith("-Xmx") || option.startsWith("-XX:MaxRAMPercentage")).toList();
    }

    @Test
    void aCommandIsTheProgramTheOptionsTheClassPathAndTheMainClassAndNothingIsQuoted() {
        Path java = temporary.resolve("a jdk").resolve("bin").resolve("java");
        Path jar = temporary.resolve("an install").resolve("sennard-6.0.0.jar");
        List<String> words = JvmOptions.command(java, List.of("-XX:+UseSerialGC", "-Xmx256m"), jar, "io.example.Main");
        assertEquals(List.of(java.toString(), "-XX:+UseSerialGC", "-Xmx256m", "-cp", jar.toString(), "io.example.Main"), words);
        assertTrue(words.getFirst().contains(" ") && words.get(4).contains(" "), "a path with a space is one word as it is: " + words);
        assertTrue(words.stream().noneMatch(word -> word.startsWith("\"")), "no quote is part of a word: " + words);
        assertEquals(List.of(java.toString(), "-cp", jar.toString(), "io.example.Main"), JvmOptions.command(java, List.of(), jar, "io.example.Main"));
        assertThrows(UnsupportedOperationException.class, () -> words.add("extra"), "the words are a value: a caller adds its own to a copy");
    }

    /**
     * The options are valid for the JDK that runs the server, and a missing cache is what auto mode says it is: the JVM runs and writes why to its file. The JVM runs in the folder the
     * server runs in, so its two logs are written there by their relative names.
     */
    @ParameterizedTest
    @EnumSource(JvmPreset.class)
    void everyPresetsOptionsStartThisJvmWithoutTheCacheAndTheJvmSaysWhy(JvmPreset preset) throws Exception {
        InstalledVersion version = version();
        Files.createDirectories(version.folder());
        Path workingFolder = Files.createDirectories(temporary.resolve("working-" + preset.name()));
        List<String> command = new ArrayList<>();
        command.add(ReleaseJarFixture.javaProgram().toString());
        command.addAll(JvmOptions.server(preset, version));
        // The options under test are the product's; the heap of this test's own child JVM is stated after them, and -version starts the JVM and stops it again.
        command.add("-Xmx" + System.getProperty("sennard.test.childHeap"));
        command.add("-version");
        ToolProcess.Finished done = (ToolProcess.Finished) ToolProcess.run(ToolProcess.Request.of(command, ToolProcess.Stderr.MERGE, Duration.ofMinutes(1)).inDirectory(workingFolder));
        String output = new String(done.output(), StandardCharsets.UTF_8);
        assertEquals(0, done.exitCode(), preset + ": " + output);
        assertTrue(Files.exists(workingFolder.resolve("jvm-gc.log")), "the GC log is written in the working folder: " + output);
        String aotLog = read(workingFolder.resolve("jvm-aot.log"));
        assertTrue(aotLog.contains("Specified AOT cache not found") && aotLog.contains(preset.collector().cacheFileName()), "the JVM names the missing cache in its log: " + aotLog);
    }

    /**
     * The hook client's options are valid for this JDK, and a cache that is not there makes the JVM print no log line: whatever the JVM would write about it goes to standard
     * output, which is a hook's answer to its host. {@code -version} writes the JDK's version text, which has no line that starts with {@code [}.
     */
    @Test
    void theHookClientsOptionsStartThisJvmWithoutTheCacheAndTheJvmWritesNoLogLine() throws Exception {
        InstalledVersion version = version();
        Files.createDirectories(version.folder());
        List<String> command = new ArrayList<>();
        command.add(ReleaseJarFixture.javaProgram().toString());
        command.addAll(JvmOptions.hookClient(version));
        command.add("-Xmx" + System.getProperty("sennard.test.childHeap"));
        command.add("-version");
        ToolProcess.Finished done = (ToolProcess.Finished) ToolProcess.run(ToolProcess.Request.of(command, ToolProcess.Stderr.MERGE, Duration.ofMinutes(1)));
        String output = new String(done.output(), StandardCharsets.UTF_8);
        assertEquals(0, done.exitCode(), output);
        assertFalse(Files.exists(version.folder().resolve("sennard-hook.aot")), "the cache is not there");
        assertTrue(output.lines().noneMatch(line -> line.startsWith("[")), "a log line would reach the host as the hook's answer: " + output);
    }

    private static String read(Path file) throws IOException {
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }
}
