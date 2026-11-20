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

import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.hookclient.HookClientMain;
import io.github.jaeseongs95.sennard.hookclient.McpRelayMain;
import io.github.jaeseongs95.sennard.personal.Sentences;
import io.github.jaeseongs95.sennard.server.hook.HostRegistrationFile;
import io.github.jaeseongs95.sennard.serverit.HookAdapters;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code sennard hooks} command itself ({@link Installer#hooks()}, which {@code SennardCli} runs for {@code hooks}) on an installation folder and a home in a temporary folder.
 * The host files are laid out as the Claude Code documentation describes them; they are not a recording of a real Claude Code (none exists), and nothing here reads the
 * user's own files: the home the host locations are taken from is the environment's {@code home}, a temporary folder. The command first makes the hook client's AOT cache with the
 * {@code java} the definitions will name, and then writes the definitions ({@code Installer#hooks}): the stand-in {@link Commands} here takes the training's call and makes no cache (the
 * training itself is {@code CommandAotCacheTest}'s), which the command reports as a warning and goes on from, as a missing cache costs start-up time only.
 *
 * <pre>
 * h1  local marketplace  a plugin of a marketplace added from a local folder, whose record names a cache copy: nothing is written in the host's folders, the command fails with the sentence
 *                        (en and ko), which names the program the definition runs and the files to copy it into; the definitions are generated in the installation (the hooks and the MCP server)
 * h2  fetched            the same record of a fetched marketplace: the cache copy's hook definition and MCP registration are written and the command succeeds
 * </pre>
 * Both cases check that the command asked the {@code java} it names for the hook client's training once, before it wrote anything.
 */
class HooksCommandTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temporary;

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();
    /** The commands the stand-in took: the hook client's training run, as the {@code java} the definitions name would run it. */
    private final List<List<String>> trainings = new ArrayList<>();

    private Path java() {
        return temporary.resolve("jdk").resolve("bin").resolve("java");
    }

    private Installer installer() throws IOException {
        Path root = temporary.resolve("install");
        InstalledVersion version = new InstalledVersion(root.resolve(InstalledVersion.VERSIONS).resolve("6.0.0"));
        // The files that have the names the command reads: the application jar makes the folder an installed version, and the hook client's jar is what the definitions run.
        write(version.appJar(), "a file with the name of the application jar");
        write(version.folder().resolve("lib").resolve("sennard-hook-client-6.0.0.jar"), "a file with the name of the hook client's jar");
        // The settings file keeps the server's state folder in the temporary folder, away from the user's own.
        write(version.settingsFile(), "sennard:\n  state-dir: '" + temporary.resolve("state") + "'\n");
        Commands training = (argv, variables, timeout) -> {
            trainings.add(argv);
            return new Commands.Result(0, "");
        };
        InstallEnvironment environment = new InstallEnvironment(Map.of("SENNARD_INSTALL_ROOT", root.toString()), temporary.resolve("home"), () -> java(), training, StandardCharsets.UTF_8);
        return new Installer(environment, RunningProgram.elsewhere(), "6.0.0", new PrintStream(printed, true, StandardCharsets.UTF_8));
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private Path plugins() {
        return temporary.resolve("home/.claude/plugins");
    }

    private void record(Path installPath, String version) throws IOException {
        write(plugins().resolve("installed_plugins.json"), "{\"version\":2,\"plugins\":{\"sennard@m\":[{\"scope\":\"user\",\"installPath\":"
                + JSON.writeValueAsString(installPath.toString()) + ",\"version\":\"" + version + "\"}]}}");
    }

    /** The content of the file a package ships for Claude Code at {@code relativePath}: what the host's copy holds until the installation writes its definition. */
    private static String shipped(String relativePath) {
        return HookAdapters.claude().shippedFiles().stream().filter(file -> file.relativePath().equals(Path.of(relativePath))).findFirst().map(HostRegistrationFile::content)
                .orElseThrow(() -> new AssertionError("the package ships no " + relativePath)).toPrettyString();
    }

    /** The training ran once, with the {@code java} the command names and the hook client's main class: before the definitions were written, which name that very program. */
    private void assertTrainedWithTheJavaTheDefinitionsName() {
        assertEquals(1, trainings.size(), trainings.toString());
        List<String> training = trainings.get(0);
        assertEquals(java().toString(), training.get(0), "the java of the environment");
        assertTrue(training.contains("-XX:AOTMode=record") && training.contains(HookClientMain.class.getName()), training.toString());
    }

    @Test
    void aLocalFolderMarketplacesPluginIsNotWrittenAndTheCommandFailsWithWhatToDo() throws IOException {
        Path marketplace = temporary.resolve("checkout");
        Path source = marketplace.resolve("claude-plugin");
        String shippedHooks = shipped("hooks/hooks.json");
        write(source.resolve("hooks/hooks.json"), shippedHooks);
        write(marketplace.resolve(".claude-plugin/marketplace.json"), "{\"name\":\"m\",\"owner\":{\"name\":\"o\"},\"plugins\":[{\"name\":\"sennard\",\"source\":\"./claude-plugin\"}]}");
        write(plugins().resolve("known_marketplaces.json"), "{\"m\":{\"source\":{\"source\":\"directory\",\"path\":" + JSON.writeValueAsString(marketplace.toString())
                + "},\"installLocation\":" + JSON.writeValueAsString(marketplace.toString()) + "}}");
        Path cacheCopy = plugins().resolve("cache/m/sennard/unknown");
        write(cacheCopy.resolve("hooks/hooks.json"), shippedHooks);
        record(cacheCopy, "unknown");

        UnavailableException failure = assertThrows(UnavailableException.class, installer()::hooks);

        assertEquals(UnavailableCode.HOST_HOOKS_NOT_REACHED, failure.code());
        assertTrainedWithTheJavaTheDefinitionsName();
        Path hooksTarget = source.toAbsolutePath().normalize().resolve("hooks/hooks.json");
        Path mcpTarget = source.toAbsolutePath().normalize().resolve(".mcp.json");
        Path hooksDefinition = temporary.resolve("install/host-packages/claude/hooks/hooks.json");
        Path mcpDefinition = temporary.resolve("install/host-packages/claude/.mcp.json");
        for (String sentence : new String[] {Sentences.english(failure), Sentences.korean(failure)}) {
            assertTrue(sentence.contains(hooksTarget.toString()) && sentence.contains(mcpTarget.toString()), sentence);
            assertTrue(sentence.contains(hooksDefinition.toString()) && sentence.contains(mcpDefinition.toString()), sentence);
            assertTrue(sentence.contains(java().toString()), "the sentence names the program the definition runs: " + sentence);
        }
        assertTrue(Sentences.english(failure).contains("hooks command"), Sentences.english(failure));
        assertTrue(Sentences.korean(failure).contains("hooks 명령"), Sentences.korean(failure));
        assertEquals(shippedHooks, Files.readString(cacheCopy.resolve("hooks/hooks.json"), StandardCharsets.UTF_8), "the cache copy is not written");
        assertEquals(shippedHooks, Files.readString(source.resolve("hooks/hooks.json"), StandardCharsets.UTF_8), "the user's folder is not written");
        assertFalse(Files.exists(source.resolve(".mcp.json")), "and no file is made in it");
        assertTrue(Files.isRegularFile(hooksDefinition) && Files.isRegularFile(mcpDefinition), "the installation's definitions are where the sentence says");
        String output = printed.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("left " + hooksTarget) && output.contains("left " + mcpTarget), output);
    }

    @Test
    void aFetchedMarketplacesPluginIsWrittenInTheCacheAndTheCommandSucceeds() throws IOException {
        Path cacheCopy = plugins().resolve("cache/m/sennard/6.0.0");
        write(cacheCopy.resolve("hooks/hooks.json"), shipped("hooks/hooks.json"));
        write(cacheCopy.resolve(".mcp.json"), shipped(".mcp.json"));
        record(cacheCopy, "6.0.0");
        write(plugins().resolve("known_marketplaces.json"), "{\"m\":{\"source\":{\"source\":\"github\",\"repo\":\"o/r\"},\"installLocation\":" + JSON.writeValueAsString(plugins().resolve("marketplaces/m").toString()) + "}}");

        installer().hooks();

        assertTrainedWithTheJavaTheDefinitionsName();
        String output = printed.toString(StandardCharsets.UTF_8);
        Path hooks = cacheCopy.toAbsolutePath().normalize().resolve("hooks/hooks.json");
        Path mcp = cacheCopy.toAbsolutePath().normalize().resolve(".mcp.json");
        assertTrue(output.contains("wrote " + hooks) && output.contains("wrote " + mcp), output);
        // What was written is the installation's own: the definition runs the java the command names, and the MCP server is the relay, not the shipped empty form.
        JsonNode start = JSON.readTree(Files.readString(hooks, StandardCharsets.UTF_8)).path("hooks").path("SessionStart").path(0).path("hooks").path(0);
        assertEquals(java().toString(), start.path("command").stringValue(), "the hook definition runs the installation's java");
        assertTrue(start.path("args").valueStream().anyMatch(argument -> argument.stringValue().equals(HookClientMain.class.getName())), start.toString());
        JsonNode relay = JSON.readTree(Files.readString(mcp, StandardCharsets.UTF_8)).path("mcpServers").path("sennard");
        assertEquals(java().toString(), relay.path("command").stringValue(), "the MCP registration runs the installation's java");
        assertTrue(relay.path("args").valueStream().anyMatch(argument -> argument.stringValue().equals(McpRelayMain.class.getName())), relay.toString());
    }
}
