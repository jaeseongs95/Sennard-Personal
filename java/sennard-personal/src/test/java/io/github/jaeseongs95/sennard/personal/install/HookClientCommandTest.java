// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.hookclient.HookClientMain;
import io.github.jaeseongs95.sennard.hookclient.McpRelayMain;

/**
 * The words of the two programs an installation's host definitions run, made from a version folder that holds the two files the words name (files that only have the names the
 * real ones have: no program is started).
 *
 * <pre>
 * k1  hook program  hookProgram(java, version, serverFile) = the java by its absolute path, the client's options (JvmOptions.hookClient), -cp, lib/sennard-hook-client-&lt;version&gt;.jar, the hook
 *                   client's main class, --server-file=&lt;the file&gt;, --jar=&lt;the installed application jar&gt;, in this order and with nothing after: the call's arguments are the definition's
 * k2  mcp program   mcpProgram(...) is the same words but for the main class: the relay shares the client's jar, options and locations
 * k3  with options  withOptions(java, version, options) = the java, exactly those options, -cp, the jar and the hook client's main class, and no location word (the AOT steps run the
 *                   client without a server)
 * k4  one word      a path with a space (the install folder, the java) is one word as it is, never split and never quoted
 * k5  same -cp      the final words, the training's and the cache creation's have the same java, -cp value and main class, and differ only in the AOT options: a cache is made for
 *                   the class path it is run with
 * k6  no jar        a version without exactly one hook client jar in lib gives no words: an INSTALL_FAILED failure
 * </pre>
 */
class HookClientCommandTest {
    @TempDir
    Path temporary;

    /** An installed version of the given folder name, with the hook client's jar and another module's in {@code lib} and the application jar beside it. */
    private InstalledVersion version(String folderName) throws IOException {
        Path folder = temporary.resolve(folderName).resolve("versions").resolve("6.0.0");
        Path lib = Files.createDirectories(folder.resolve("lib"));
        Files.writeString(lib.resolve("sennard-hook-client-6.0.0.jar"), "a file with the name of the client's jar");
        Files.writeString(lib.resolve("sennard-personal-6.0.0.jar"), "another module's jar");
        Files.writeString(folder.resolve("sennard-6.0.0.jar"), "a file with the name of the application jar");
        return new InstalledVersion(folder);
    }

    private Path java() {
        return temporary.resolve("jdk").resolve("bin").resolve("java");
    }

    private Path serverFile() {
        return temporary.resolve("state").resolve("server.properties");
    }

    @Test
    void theHookProgramIsTheJavaTheClientsOptionsItsJarItsMainClassAndWhereToFindTheServerAndTheJar() throws IOException {
        InstalledVersion version = version("plain");
        List<String> expected = new ArrayList<>();
        expected.add(java().toString());
        expected.addAll(JvmOptions.hookClient(version));
        expected.addAll(List.of("-cp", version.folder().resolve("lib").resolve("sennard-hook-client-6.0.0.jar").toString(), HookClientMain.class.getName(),
                "--server-file=" + serverFile(), "--jar=" + version.folder().resolve("sennard-6.0.0.jar")));

        List<String> words = HookClientCommand.hookProgram(java(), version, serverFile());

        assertEquals(expected, words);
        assertEquals("io.github.jaeseongs95.sennard.hookclient.HookClientMain", HookClientMain.class.getName());
        assertTrue(Path.of(words.getFirst()).isAbsolute(), "the java by its absolute path: a host starts a command without the user's PATH");
        assertTrue(words.contains("-Xlog:disable") && words.contains("-XX:AOTMode=auto") && words.contains("-XX:AOTCache=" + version.folder().resolve("sennard-hook.aot")), words.toString());
        assertEquals(1, words.stream().filter("-cp"::equals).count(), "one class path");
        assertEquals(version.hookClientJar().toString(), words.get(words.indexOf("-cp") + 1), "the client's jar alone is the class path");
        assertEquals(List.of("--server-file=" + serverFile(), "--jar=" + version.appJar()), words.subList(words.size() - 2, words.size()), "the locations come last and nothing after them");
        assertThrows(UnsupportedOperationException.class, () -> words.add("hook"), "the words are a value: a definition adds its own arguments to a copy");
    }

    @Test
    void theMcpProgramDiffersFromTheHookProgramInTheMainClassOnly() throws IOException {
        InstalledVersion version = version("plain");
        List<String> hook = HookClientCommand.hookProgram(java(), version, serverFile());
        List<String> mcp = HookClientCommand.mcpProgram(java(), version, serverFile());

        assertEquals(hook.size(), mcp.size());
        int main = hook.indexOf("-cp") + 2;
        assertEquals(HookClientMain.class.getName(), hook.get(main));
        assertEquals(McpRelayMain.class.getName(), mcp.get(main));
        assertEquals("io.github.jaeseongs95.sennard.hookclient.McpRelayMain", McpRelayMain.class.getName());
        List<String> mcpWithHookMain = new ArrayList<>(mcp);
        mcpWithHookMain.set(main, HookClientMain.class.getName());
        assertEquals(hook, mcpWithHookMain, "the same java, options, class path and locations: the relay is the client's jar with another entry point");
        assertThrows(UnsupportedOperationException.class, () -> mcp.add("--host=claude"));
    }

    @Test
    void withOptionsGivesTheJavaTheseOptionsTheJarAndTheMainClassAndNoLocation() throws IOException {
        InstalledVersion version = version("plain");
        List<String> words = HookClientCommand.withOptions(java(), version, List.of("-XX:+UseSerialGC", "-XX:AOTMode=record"));
        assertEquals(List.of(java().toString(), "-XX:+UseSerialGC", "-XX:AOTMode=record", "-cp", version.hookClientJar().toString(), HookClientMain.class.getName()), words);
        assertEquals(List.of(java().toString(), "-cp", version.hookClientJar().toString(), HookClientMain.class.getName()), HookClientCommand.withOptions(java(), version, List.of()));
    }

    @Test
    void aPathWithASpaceIsOneWordAsItIs() throws IOException {
        InstalledVersion version = version("an install with spaces");
        Path javaWithSpace = temporary.resolve("a jdk").resolve("bin").resolve("java");
        Path serverFileWithSpace = temporary.resolve("a state folder").resolve("server.properties");
        List<String> words = HookClientCommand.hookProgram(javaWithSpace, version, serverFileWithSpace);
        assertTrue(version.folder().toString().contains(" "), "the case has a space in the path");
        assertEquals(javaWithSpace.toString(), words.getFirst());
        assertEquals(version.hookClientJar().toString(), words.get(words.indexOf("-cp") + 1));
        assertTrue(words.contains("--server-file=" + serverFileWithSpace) && words.contains("--jar=" + version.appJar()), words.toString());
        assertTrue(words.stream().noneMatch(word -> word.startsWith("\"") || word.endsWith("\"")), "no quote is part of a word: " + words);
        assertTrue(words.stream().noneMatch(String::isEmpty), words.toString());
        // Every option is free of spaces but the paths, so that a host that splits a string at spaces reads the options as they are.
        assertTrue(JvmOptions.hookClient(version).stream().filter(word -> !word.startsWith("-XX:AOTCache=")).noneMatch(word -> word.indexOf(' ') >= 0), "the options hold no space but in the cache's path");
    }

    @Test
    void theTrainingTheCacheCreationAndTheFinalRunHaveTheSameJavaClassPathAndMainClass() throws IOException {
        InstalledVersion version = version("plain");
        List<String> installed = HookClientCommand.hookProgram(java(), version, serverFile());
        List<String> training = HookClientCommand.withOptions(java(), version, JvmOptions.hookClientTraining(version));
        List<String> creation = HookClientCommand.withOptions(java(), version, JvmOptions.hookClientCacheCreation(version));

        for (List<String> step : List.of(training, creation)) {
            assertEquals(installed.getFirst(), step.getFirst(), "the same java");
            assertEquals(installed.get(installed.indexOf("-cp") + 1), step.get(step.indexOf("-cp") + 1), "the same class path: a cache refuses another");
            assertEquals(installed.get(installed.indexOf("-cp") + 2), step.get(step.indexOf("-cp") + 2), "the same main class");
            assertEquals(installed.subList(0, 6), step.subList(0, 6), "the same JVM options up to the AOT step: the collector, the heap, the JIT level, no perf data, no JVM log");
        }
        assertEquals(List.of("-XX:AOTMode=record", "-XX:AOTConfiguration=" + version.folder().resolve("sennard-hook.aotconf")), training.subList(6, 8));
        assertTrue(creation.contains("-XX:AOTMode=create") && creation.contains("-XX:AOTCache=" + version.folder().resolve("sennard-hook.aot")), creation.toString());
        assertFalse(training.stream().anyMatch(word -> word.startsWith("-XX:AOTCache=")), "recording writes no cache");
    }

    @Test
    void aVersionWithoutExactlyOneClientJarGivesNoWords() throws IOException {
        InstalledVersion noJar = version("no-jar");
        Files.delete(noJar.hookClientJar());
        assertEquals(UnavailableCode.INSTALL_FAILED, assertThrows(UnavailableException.class, () -> HookClientCommand.hookProgram(java(), noJar, serverFile())).code());
        assertEquals(UnavailableCode.INSTALL_FAILED, assertThrows(UnavailableException.class, () -> HookClientCommand.mcpProgram(java(), noJar, serverFile())).code());
        assertEquals(UnavailableCode.INSTALL_FAILED, assertThrows(UnavailableException.class, () -> HookClientCommand.withOptions(java(), noJar, List.of())).code());

        InstalledVersion twoJars = version("two-jars");
        Files.writeString(twoJars.folder().resolve("lib").resolve("sennard-hook-client-6.0.1.jar"), "x");
        assertEquals(UnavailableCode.INSTALL_FAILED, assertThrows(UnavailableException.class, () -> HookClientCommand.hookProgram(java(), twoJars, serverFile())).code());
    }
}
