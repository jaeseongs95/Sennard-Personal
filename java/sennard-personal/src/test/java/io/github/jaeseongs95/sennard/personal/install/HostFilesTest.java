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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.personal.Sentences;
import io.github.jaeseongs95.sennard.server.hook.HookBody;
import io.github.jaeseongs95.sennard.server.hook.HookInstallation;
import io.github.jaeseongs95.sennard.server.hook.HookOutput;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostRegistrationFile;
import io.github.jaeseongs95.sennard.server.hook.host.AntigravityHost;
import io.github.jaeseongs95.sennard.server.hook.host.ClaudeHost;
import io.github.jaeseongs95.sennard.server.hook.host.CodexHost;
import io.github.jaeseongs95.sennard.server.hook.host.GeminiHost;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code sennard hooks} against host plugin folders laid out the way the hosts lay them out (Claude Code's {@code installed_plugins.json} and version cache, Codex's plugin cache, agy's
 * single copy, Gemini CLI's extension folder), in a temporary home. The adapters are stand-ins that write the shape the real ones write ({@link HostRegistrationFile}: the hook
 * definition and the MCP registration, each a whole file or one member of a file the host owns) from the installation's programs, so the cases are about where {@link HostFiles}
 * puts them and what it keeps: the words are the real {@link HookClientCommand}'s, the folders are the real package locations'. An installation's definition runs the user's {@code java} by its
 * absolute path with the hook client's options, jar and main class, so it differs from the empty one the plugin package ships on every OS, and every OS gets the same form.
 *
 * <pre>
 * h1  claude copy   the copy Claude loads gets both definitions, whole files (the hooks and the MCP server: command = java, args = the client's words and then the call's); another major
 *                   version is left
 * h2  no plugin     no plugin: each host's definitions are still generated, a member under the name of its file and member, and the report says to run sennard hooks after installing it
 * h3  held already  a copy that already holds the definition is not written, and is not on the uninstall's list
 * h4  replaced      the shipped (empty) definition is replaced every time, the same way; a copy that is empty again after the host re-copied the plugin is named by emptyCopies
 * h5  in place      a folder the host loads in place is not written and the report names the file, the generated definition and the java
 * h6  member        a member of a file the host owns is set and the rest of the file is kept; a file the host does not have is not made; an upgrade keeps what the file held before Sennard
 * h7  codex         every version of every marketplace of the plugin cache of the same major version is written, the others are left
 * h8  uninstall     what Sennard wrote is removed, a member is put back to what it held, what changed since is kept
 * h9  unreadable    a host record that cannot be read fails the command with the cause; a record of placed files that cannot be read keeps every file and says why
 * </pre>
 */
class HostFilesTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HOOKS_COMMAND = "java -jar sennard-6.0.0.jar hooks";

    @TempDir
    Path temporary;

    private final StandIn claude = new StandIn(ClaudeHost.ID, "hooks/hooks.json", ".mcp.json", null);
    private final StandIn codex = new StandIn(CodexHost.ID, "hooks/hooks.json", ".mcp.json", null);
    private final StandIn agy = new StandIn(AntigravityHost.ID, "hooks.json", "mcp_config.json", null);
    private final StandIn gemini = new StandIn(GeminiHost.ID, "hooks/hooks.json", "gemini-extension.json", "mcpServers");
    private final List<HostHookAdapter> adapters = List.of(claude, codex, agy, gemini);
    private final List<HostPackageLocation> locations = List.of(new ClaudePackageLocation(JSON), new AntigravityPackageLocation(JSON));
    private final List<HostPackageLocation> everyLocation = List.of(new ClaudePackageLocation(JSON), new CodexPackageLocation(), new AntigravityPackageLocation(JSON),
            new GeminiPackageLocation(JSON));

    /**
     * What an adapter of a host writes, in the shapes the real ones write, from the installation's programs: a hook definition of command hooks, and an MCP registration of one stdio
     * server. A stand-in never answers a hook.
     */
    private static final class StandIn implements HostHookAdapter {
        private final HostId host;
        private final Path hooksFile;
        private final Path mcpFile;
        private final String mcpMember;

        /** @param mcpMember the member of {@code mcpFile} the registration is, or null when the registration is a file of its own */
        StandIn(HostId host, String hooksFile, String mcpFile, String mcpMember) {
            this.host = host;
            this.hooksFile = Path.of(hooksFile);
            this.mcpFile = Path.of(mcpFile);
            this.mcpMember = mcpMember;
        }

        @Override
        public HostId host() {
            return host;
        }

        @Override
        public Class<?> inputType() {
            return Object.class;
        }

        @Override
        public HostEvent read(String event, HookBody body) {
            throw new UnsupportedOperationException("a stand-in answers no hook");
        }

        @Override
        public HookOutput respond(String event, HostEvent read, HookVerdict verdict) {
            throw new UnsupportedOperationException("a stand-in answers no hook");
        }

        @Override
        public List<HostRegistrationFile> registrationFiles(HookInstallation installation) {
            List<String> hook = new ArrayList<>(installation.hookProgram().subList(1, installation.hookProgram().size()));
            hook.addAll(List.of("hook", host.label(), "SessionStart"));
            List<String> relay = new ArrayList<>(installation.mcpProgram().subList(1, installation.mcpProgram().size()));
            relay.add("--host=" + host.label());
            JsonNode hooks = JSON.readTree("{\"hooks\":{\"SessionStart\":[{\"hooks\":[{\"type\":\"command\",\"command\":" + JSON.writeValueAsString(installation.hookProgram().getFirst())
                    + ",\"args\":" + JSON.writeValueAsString(hook) + "}]}]}}");
            JsonNode servers = JSON.readTree("{\"mcpServers\":{\"sennard\":{\"command\":" + JSON.writeValueAsString(installation.mcpProgram().getFirst()) + ",\"args\":" + JSON.writeValueAsString(relay) + "}}}");
            return List.of(new HostRegistrationFile.WholeFile(hooksFile, hooks), mcpMember == null ? new HostRegistrationFile.WholeFile(mcpFile, servers)
                    : new HostRegistrationFile.Member(mcpFile, "/" + mcpMember, servers.path(mcpMember)));
        }

        @Override
        public List<HostRegistrationFile.WholeFile> shippedFiles() {
            List<HostRegistrationFile.WholeFile> shipped = new ArrayList<>();
            shipped.add(new HostRegistrationFile.WholeFile(hooksFile, JSON.readTree("{\"hooks\":{}}")));
            if (mcpMember == null) {
                shipped.add(new HostRegistrationFile.WholeFile(mcpFile, JSON.readTree("{\"mcpServers\":{}}")));
            }
            return shipped;
        }

        Path hooksFile() {
            return hooksFile;
        }

        Path mcpFile() {
            return mcpFile;
        }

        JsonNode hooksContent(HookInstallation installation) {
            return registrationFiles(installation).getFirst().content();
        }

        JsonNode mcpContent(HookInstallation installation) {
            return registrationFiles(installation).get(1).content();
        }
    }

    private Path home() {
        return temporary.resolve("home");
    }

    private Path installRoot() {
        return temporary.resolve("install");
    }

    private Path generated() {
        return installRoot().resolve("host-packages");
    }

    private InstallEnvironment environment() {
        return environment(Map.of());
    }

    private InstallEnvironment environment(Map<String, String> variables) {
        return new InstallEnvironment(variables, home(), () -> java(), (argv, environment, timeout) -> {
            throw new AssertionError("no command runs in these cases: " + argv);
        }, StandardCharsets.UTF_8);
    }

    private Path java() {
        return temporary.resolve("jdk").resolve("bin").resolve("java");
    }

    /** The installed version the definitions run: the folder with the hook client's jar in {@code lib} and the application jar. */
    private InstalledVersion version() throws IOException {
        Path folder = installRoot().resolve("versions").resolve("6.0.0");
        write(folder.resolve("lib").resolve("sennard-hook-client-6.0.0.jar"), "the hook client's jar");
        write(folder.resolve("sennard-6.0.0.jar"), "the application jar");
        return new InstalledVersion(folder);
    }

    private HookInstallation installation(Path java) throws IOException {
        InstalledVersion version = version();
        Path serverFile = temporary.resolve("state").resolve("server.properties");
        return new HookInstallation(HookClientCommand.hookProgram(java, version, serverFile), HookClientCommand.mcpProgram(java, version, serverFile));
    }

    private HostFiles.Placement placement(HostFiles files, InstallEnvironment environment, Path java) throws IOException {
        return files.place(adapters, environment, installation(java), generated());
    }

    private HostFiles.Placement placement(HostFiles files, InstallEnvironment environment) throws IOException {
        return placement(files, environment, java());
    }

    private List<String> place(HostFiles files, InstallEnvironment environment) throws IOException {
        return placement(files, environment).report();
    }

    private HostFiles hostFiles() {
        return new HostFiles(locations, "6.0.0", HOOKS_COMMAND, JSON);
    }

    /** What the host copies when it installs the plugin: the files of the package, with nothing registered. */
    private static void writeShipped(Path root, StandIn adapter) throws IOException {
        for (HostRegistrationFile.WholeFile shipped : adapter.shippedFiles()) {
            write(root.resolve(shipped.relativePath()), shipped.content().toPrettyString());
        }
    }

    /** A marketplace install as Claude Code records it: a copy in the version cache holding the shipped definitions. */
    private Path claudeCopy(String version) throws IOException {
        Path root = home().resolve(".claude/plugins/cache/sennard-claude/sennard").resolve(version);
        writeShipped(root, claude);
        return root;
    }

    private void claudeRecord(String entries) throws IOException {
        write(home().resolve(".claude/plugins/installed_plugins.json"), "{\"version\":2,\"plugins\":{\"sennard@sennard-claude\":[" + entries + "]}}");
    }

    private static String entry(String scope, Path root, String version) {
        return "{\"scope\":\"" + scope + "\",\"installPath\":" + JSON.writeValueAsString(root.toString()) + ",\"version\":\"" + version + "\"}";
    }

    private Path agyCopy(String version) throws IOException {
        Path root = home().resolve(".gemini/config/plugins/sennard");
        write(root.resolve("plugin.json"), "{\"name\":\"sennard\",\"version\":\"" + version + "\"}");
        writeShipped(root, agy);
        return root;
    }

    private Path codexCopy(String marketplace, String version) throws IOException {
        Path root = home().resolve(".codex/plugins/cache").resolve(marketplace).resolve("sennard").resolve(version);
        writeShipped(root, codex);
        return root;
    }

    /** Gemini CLI's extension folder: its own manifest (with the members the test gives) and the shipped hooks. */
    private Path geminiExtension(String manifest) throws IOException {
        Path root = home().resolve(".gemini/extensions/sennard");
        if (manifest != null) {
            write(root.resolve("gemini-extension.json"), manifest);
        }
        writeShipped(root, gemini);
        return root;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static JsonNode read(Path file) throws IOException {
        return JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static List<String> words(JsonNode array) {
        List<String> words = new ArrayList<>();
        array.forEach(word -> words.add(word.asString()));
        return words;
    }

    @Test
    void theClaudeCopyClaudeLoadsGetsTheInstallationsDefinitionsAndAnotherMajorIsLeftAlone() throws IOException {
        Path current = claudeCopy("6.0.0");
        Path older = claudeCopy("5.1.0");
        write(older.resolve("hooks/hooks.json"), "{\"hooks\":{\"marker\":[]}}");
        claudeRecord(entry("user", current, "6.0.0") + "," + entry("project", older, "5.1.0"));
        HookInstallation installation = installation(java());

        List<String> report = place(hostFiles(), environment());

        JsonNode hooks = read(current.resolve("hooks/hooks.json"));
        assertEquals(claude.hooksContent(installation), hooks, report.toString());
        assertEquals(claude.mcpContent(installation), read(current.resolve(".mcp.json")), report.toString());
        JsonNode command = hooks.path("hooks").path("SessionStart").get(0).path("hooks").get(0);
        assertEquals(java().toString(), command.path("command").asString(), "the java by its absolute path, no shell");
        List<String> hookWords = installation.hookProgram().subList(1, installation.hookProgram().size());
        assertEquals(hookWords, words(command.path("args")).subList(0, hookWords.size()), "the client's options, class path, main class and locations come first, one word each");
        assertEquals(List.of("hook", "claude", "SessionStart"), words(command.path("args")).subList(hookWords.size(), hookWords.size() + 3));
        JsonNode server = read(current.resolve(".mcp.json")).path("mcpServers").path("sennard");
        assertEquals(installation.mcpProgram().getFirst(), server.path("command").asString());
        assertTrue(words(server.path("args")).contains("--host=claude"), server.toString());
        assertTrue(report.contains("wrote " + current.resolve("hooks/hooks.json")) && report.contains("wrote " + current.resolve(".mcp.json")), report.toString());
        assertEquals("{\"hooks\":{\"marker\":[]}}", Files.readString(older.resolve("hooks/hooks.json"), StandardCharsets.UTF_8));
        assertEquals(claude.shippedFiles().get(1).content(), read(older.resolve(".mcp.json")), "the other major's files are all left");
        assertTrue(report.stream().anyMatch(line -> line.startsWith("left " + older.resolve("hooks/hooks.json")) && line.contains("5.1.0")), report.toString());
        assertFalse(report.stream().anyMatch(line -> line.startsWith("claude plugin is not installed yet")), report.toString());
    }

    @Test
    void withoutTheHostPluginsTheCommandSaysToRunItAgainAfterInstallingThePluginAndKeepsEachGeneratedDefinition() throws IOException {
        List<String> report = place(hostFiles(), environment());

        assertTrue(report.contains("claude plugin is not installed yet; after installing it, run: " + HOOKS_COMMAND), report.toString());
        assertTrue(report.contains("agy plugin is not installed yet; after installing it, run: " + HOOKS_COMMAND), report.toString());
        assertFalse(report.stream().anyMatch(line -> line.startsWith("codex") || line.startsWith("gemini")), "a host with no package location says nothing: " + report);
        Set<Path> files;
        try (Stream<Path> walked = Files.walk(generated())) {
            files = walked.filter(Files::isRegularFile).map(generated()::relativize).collect(Collectors.toSet());
        }
        assertEquals(Set.of(Path.of("claude/hooks/hooks.json"), Path.of("claude/.mcp.json"), Path.of("codex/hooks/hooks.json"), Path.of("codex/.mcp.json"), Path.of("agy/hooks.json"),
                Path.of("agy/mcp_config.json"), Path.of("gemini/hooks/hooks.json"), Path.of("gemini/gemini-extension.json.mcpServers.json"), Path.of("placed.json")), files,
                "each definition under the host's name, a member under the name of its file and member, and the record of what was placed");
        HookInstallation installation = installation(java());
        assertEquals(gemini.mcpContent(installation), read(generated().resolve("gemini/gemini-extension.json.mcpServers.json")), "the member's value, ready to put in the host's file");
        assertEquals(claude.hooksContent(installation), read(generated().resolve("claude/hooks/hooks.json")));
    }

    /** A copy that already holds an installation's definitions is not written again, and what Sennard did not write is not on the uninstall's list. */
    @Test
    void aCopyThatAlreadyHoldsThisInstallationsDefinitionsIsNotWrittenOrAsked() throws IOException {
        HookInstallation installation = installation(java());
        Path current = claudeCopy("6.0.0");
        write(current.resolve("hooks/hooks.json"), claude.hooksContent(installation).toPrettyString());
        write(current.resolve(".mcp.json"), claude.mcpContent(installation).toPrettyString());
        claudeRecord(entry("user", current, "6.0.0"));
        Path agyRoot = agyCopy("6.0.0");
        write(agyRoot.resolve("hooks.json"), agy.hooksContent(installation).toPrettyString());
        write(agyRoot.resolve("mcp_config.json"), agy.mcpContent(installation).toPrettyString());
        String claudeBefore = Files.readString(current.resolve("hooks/hooks.json"), StandardCharsets.UTF_8);
        String agyBefore = Files.readString(agyRoot.resolve("mcp_config.json"), StandardCharsets.UTF_8);

        List<String> report = place(hostFiles(), environment());

        assertEquals(claudeBefore, Files.readString(current.resolve("hooks/hooks.json"), StandardCharsets.UTF_8));
        assertEquals(agyBefore, Files.readString(agyRoot.resolve("mcp_config.json"), StandardCharsets.UTF_8));
        assertFalse(report.stream().anyMatch(line -> line.startsWith("wrote ") || line.contains("not installed yet")), report.toString());
        assertEquals(List.of(), hostFiles().removePlaced(generated()), "uninstall leaves files Sennard did not write");
        assertTrue(Files.exists(current.resolve("hooks/hooks.json")));
    }

    /** The shipped definition holds nothing: the installation's own always replaces it, and a second run gives the same copy. */
    @Test
    void theShippedDefinitionIsReplacedByTheInstallationsOwnEveryTime() throws IOException {
        HookInstallation installation = installation(java());
        Path current = claudeCopy("6.0.0");
        claudeRecord(entry("user", current, "6.0.0"));
        Path agyRoot = agyCopy("6.0.0");
        assertEquals("{\"hooks\":{}}", read(agyRoot.resolve("hooks.json")).toString(), "the package ships no definition");

        place(hostFiles(), environment());
        String first = Files.readString(current.resolve("hooks/hooks.json"), StandardCharsets.UTF_8) + Files.readString(agyRoot.resolve("hooks.json"), StandardCharsets.UTF_8);
        writeShipped(current, claude);
        writeShipped(agyRoot, agy);
        place(hostFiles(), environment());
        String second = Files.readString(current.resolve("hooks/hooks.json"), StandardCharsets.UTF_8) + Files.readString(agyRoot.resolve("hooks.json"), StandardCharsets.UTF_8);

        assertEquals(first, second, "a definition is the same every time it is placed");
        assertEquals(agy.hooksContent(installation), read(agyRoot.resolve("hooks.json")));
        assertEquals(java().toString(), read(current.resolve("hooks/hooks.json")).path("hooks").path("SessionStart").get(0).path("hooks").get(0).path("command").asString());
    }

    /** The host re-copies the plugin when it is installed or updated: its files hold nothing again, which {@code status} reports so that {@code hooks} is run again. */
    @Test
    void theCopiesThatHoldTheShippedDefinitionAreNamedAndNothingIsWritten() throws IOException {
        Path current = claudeCopy("6.0.0");
        claudeRecord(entry("user", current, "6.0.0"));
        Path agyRoot = agyCopy("6.0.0");
        Files.delete(agyRoot.resolve("mcp_config.json"));
        String before = Files.readString(current.resolve("hooks/hooks.json"), StandardCharsets.UTF_8);

        List<Path> empty = hostFiles().emptyCopies(adapters, environment());

        assertEquals(List.of(current.resolve("hooks/hooks.json"), current.resolve(".mcp.json"), agyRoot.resolve("hooks.json")), empty, "the copies the host made, whole files only; a missing file holds nothing to name");
        assertEquals(before, Files.readString(current.resolve("hooks/hooks.json"), StandardCharsets.UTF_8), "nothing is written");

        place(hostFiles(), environment());
        assertEquals(List.of(), hostFiles().emptyCopies(adapters, environment()), "after hooks ran, no copy is empty");
        writeShipped(current, claude);
        assertEquals(List.of(current.resolve("hooks/hooks.json"), current.resolve(".mcp.json")), hostFiles().emptyCopies(adapters, environment()), "the host re-copied the plugin");
    }

    @Test
    void aFolderClaudeLoadsInPlaceIsTheUsersAndIsNotWritten() throws IOException {
        Path local = temporary.resolve("checkout/claude-plugin");
        writeShipped(local, claude);
        claudeRecord(entry("user", local, "6.0.0"));
        String before = Files.readString(local.resolve("hooks/hooks.json"), StandardCharsets.UTF_8);

        List<String> report = place(hostFiles(), environment());

        assertEquals(before, Files.readString(local.resolve("hooks/hooks.json"), StandardCharsets.UTF_8));
        assertTrue(report.stream().anyMatch(line -> line.startsWith("left " + local.toAbsolutePath().normalize().resolve("hooks/hooks.json")) && line.contains("in place")), report.toString());
        assertEquals(List.of(), hostFiles().emptyCopies(adapters, environment()), "a folder the host loads in place is not a copy the host made");
    }

    /**
     * AGS3-D-0131: a plugin of a local marketplace is loaded in place, and the version the host records for it need not be one this installation compares with
     * (a commit, "unknown"). The folder is still the one the host reads its hooks from, so the user is told where this installation's definition is, not that the
     * plugin has another version.
     */
    @ParameterizedTest
    @ValueSource(strings = {"unknown", "f459d6d1", "5.1.1", "6.0.0"})
    void aFolderClaudeLoadsInPlaceGetsTheGuidanceWhateverVersionTheHostRecordedForIt(String recorded) throws IOException {
        Path local = temporary.resolve("checkout/claude-plugin");
        writeShipped(local, claude);
        claudeRecord(entry("user", local, recorded));
        String before = Files.readString(local.resolve("hooks/hooks.json"), StandardCharsets.UTF_8);

        List<String> report = place(hostFiles(), environment());

        assertEquals(before, Files.readString(local.resolve("hooks/hooks.json"), StandardCharsets.UTF_8), "the user's folder is not written");
        Path target = local.toAbsolutePath().normalize().resolve("hooks/hooks.json");
        Path mcpTarget = local.toAbsolutePath().normalize().resolve(".mcp.json");
        assertTrue(report.stream().anyMatch(line -> line.startsWith("left " + target) && line.contains("in place")), report.toString());
        // Only Claude's plugin is installed here; the agy host has none, so its own "not installed yet" line is expected and is not about this folder.
        assertFalse(report.stream().anyMatch(line -> line.contains("that plugin is version") || line.startsWith("claude plugin is not installed yet")), report.toString());
        // The user is told what to do by the command's end, which fails: where the installation's definitions are, which files they replace, and the java they run.
        HostFiles.Placement placement = placement(hostFiles(), environment());
        assertEquals(List.of(new HostFiles.InPlaceFolder("claude", target, generated().resolve("claude/hooks/hooks.json"), java().toString()),
                new HostFiles.InPlaceFolder("claude", mcpTarget, generated().resolve("claude/.mcp.json"), java().toString())), placement.unreached());
        UnavailableException failure = assertThrows(UnavailableException.class, placement::requireReached);
        assertEquals(UnavailableCode.HOST_HOOKS_NOT_REACHED, failure.code());
        for (String sentence : List.of(Sentences.english(failure), Sentences.korean(failure))) {
            assertTrue(sentence.contains(target.toString()) && sentence.contains(mcpTarget.toString()) && sentence.contains(generated().resolve("claude/hooks/hooks.json").toString())
                    && sentence.contains(java().toString()), sentence);
        }
        assertNotEquals(Sentences.english(failure), Sentences.korean(failure));
    }

    /**
     * AGS3-D-0131, a documented layout and not a recording of Claude Code's files (no recording exists): a plugin of a marketplace added from a local folder is loaded
     * from the marketplace's own folder, whatever {@code installed_plugins.json} names for it. The cache copy that record names is not where the host reads, so it is not
     * written, and the command ends as failed with the sentence that says what to do instead of reporting a write the host never reads.
     */
    @Test
    void aPluginOfALocalFolderMarketplaceIsNotWrittenInTheCacheAndTheCommandFailsWithWhatToDo_documentedLayoutNotARecording() throws IOException {
        Path marketplace = temporary.resolve("checkout");
        Path source = marketplace.resolve("plugins/sennard");
        writeShipped(source, claude);
        write(marketplace.resolve(".claude-plugin/marketplace.json"),
                "{\"name\":\"local-m\",\"owner\":{\"name\":\"o\"},\"plugins\":[{\"name\":\"other\",\"source\":\"./plugins/other\"},{\"name\":\"sennard\",\"source\":\"./plugins/sennard\"}]}");
        write(home().resolve(".claude/plugins/known_marketplaces.json"),
                "{\"local-m\":{\"source\":{\"source\":\"directory\",\"path\":" + JSON.writeValueAsString(marketplace.toString()) + "},\"installLocation\":" + JSON.writeValueAsString(marketplace.toString()) + "}}");
        Path cacheCopy = home().resolve(".claude/plugins/cache/local-m/sennard/unknown");
        writeShipped(cacheCopy, claude);
        write(home().resolve(".claude/plugins/installed_plugins.json"), "{\"version\":2,\"plugins\":{\"sennard@local-m\":[" + entry("user", cacheCopy, "unknown") + "]}}");
        String cacheBefore = Files.readString(cacheCopy.resolve("hooks/hooks.json"), StandardCharsets.UTF_8);
        String sourceBefore = Files.readString(source.resolve("hooks/hooks.json"), StandardCharsets.UTF_8);

        HostFiles.Placement placement = placement(hostFiles(), environment());

        assertEquals(cacheBefore, Files.readString(cacheCopy.resolve("hooks/hooks.json"), StandardCharsets.UTF_8), "the cache copy the host does not read is not written");
        assertEquals(sourceBefore, Files.readString(source.resolve("hooks/hooks.json"), StandardCharsets.UTF_8), "the user's folder is not written either");
        assertFalse(placement.report().stream().anyMatch(line -> line.startsWith("wrote ")), placement.report().toString());
        Path target = source.toAbsolutePath().normalize().resolve("hooks/hooks.json");
        assertEquals(List.of(target, source.toAbsolutePath().normalize().resolve(".mcp.json")), placement.unreached().stream().map(HostFiles.InPlaceFolder::target).toList());
        UnavailableException failure = assertThrows(UnavailableException.class, placement::requireReached);
        assertTrue(Sentences.english(failure).contains(target.toString()), Sentences.english(failure));
        assertTrue(Sentences.korean(failure).contains(target.toString()), Sentences.korean(failure));
    }

    /** The same record for a marketplace the host fetched (not a local path) names a copy the host reads: it is written, and nothing fails. */
    @Test
    void aPluginOfAFetchedMarketplaceStillGetsItsCacheCopyWritten_documentedLayoutNotARecording() throws IOException {
        Path copy = claudeCopy("6.0.0");
        claudeRecord(entry("user", copy, "6.0.0"));
        write(home().resolve(".claude/plugins/known_marketplaces.json"),
                "{\"sennard-claude\":{\"source\":{\"source\":\"github\",\"repo\":\"o/r\"},\"installLocation\":" + JSON.writeValueAsString(home().resolve(".claude/plugins/marketplaces/sennard-claude").toString()) + "}}");

        HostFiles.Placement placement = placement(hostFiles(), environment());

        assertEquals(claude.hooksContent(installation(java())), read(copy.resolve("hooks/hooks.json")));
        assertEquals(List.of(), placement.unreached());
        placement.requireReached();
    }

    @Test
    void theClaudePluginsRootFollowsTheConfigurationFolderSetting() throws IOException {
        Path config = temporary.resolve("elsewhere");
        Path root = config.resolve("plugins/cache/m/sennard/6.0.0");
        write(root.resolve("hooks/hooks.json"), "{}");
        write(config.resolve("plugins/installed_plugins.json"), "{\"plugins\":{\"sennard@m\":[" + entry("user", root, "6.0.0") + "]}}");
        InstallEnvironment environment = environment(Map.of("CLAUDE_CONFIG_DIR", config.toString()));

        List<HostPackageLocation.InstalledPackage> found = new ClaudePackageLocation(JSON).packages(environment);

        assertEquals(List.of(new HostPackageLocation.InstalledPackage(root.toAbsolutePath().normalize(), "6.0.0", true)), found);
    }

    @Test
    void aMemberOfAFileTheHostOwnsIsSetAndTheRestOfTheFileIsKept() throws IOException {
        HookInstallation installation = installation(java());
        Path extension = geminiExtension("{\"name\":\"sennard\",\"version\":\"6.0.0\",\"contextFileName\":\"GEMINI.md\",\"mcpServers\":{\"theirs\":{\"command\":\"another\"}}}");
        Path manifest = extension.resolve("gemini-extension.json");
        HostFiles files = new HostFiles(List.of(new GeminiPackageLocation(JSON)), "6.0.0", HOOKS_COMMAND, JSON);

        List<String> report = place(files, environment());

        JsonNode written = read(manifest);
        assertEquals("sennard", written.path("name").asString());
        assertEquals("6.0.0", written.path("version").asString());
        assertEquals("GEMINI.md", written.path("contextFileName").asString(), "the rest of the host's file is kept");
        assertEquals(gemini.mcpContent(installation), written.path("mcpServers"), "the member is the registration, whole");
        assertFalse(written.path("mcpServers").has("theirs"), "the member is replaced as a whole, as the host's manifest has one member for the servers");
        assertEquals(gemini.hooksContent(installation), read(extension.resolve("hooks/hooks.json")));
        assertTrue(report.contains("wrote " + manifest + " (/mcpServers)") && report.contains("wrote " + extension.resolve("hooks/hooks.json")), report.toString());
        assertEquals(List.of(), files.emptyCopies(adapters, environment()), "a member is not shipped: the manifest has none to be empty");
    }

    /**
     * The real location names a copy only with a version its manifest gives, so a copy without the manifest is not written at all; a location that names one (a host whose version is
     * recorded elsewhere) is a stand-in here. A member is one of a file the host owns, so a file that is not there is not made, while the whole files of the same copy are.
     */
    @Test
    void aMemberTheHostHasNoFileForIsNotMadeAndTheWholeFilesAreStillWritten() throws IOException {
        Path extension = geminiExtension(null);
        HostPackageLocation recorded = new HostPackageLocation() {
            @Override
            public HostId host() {
                return GeminiHost.ID;
            }

            @Override
            public List<InstalledPackage> packages(InstallEnvironment environment) {
                return List.of(new InstalledPackage(extension, "6.0.0", true));
            }
        };
        HostFiles files = new HostFiles(List.of(recorded), "6.0.0", HOOKS_COMMAND, JSON);

        List<String> report = place(files, environment());

        assertFalse(Files.exists(extension.resolve("gemini-extension.json")), "a manifest is the host's: Sennard does not make one");
        assertTrue(report.contains("left " + extension.resolve("gemini-extension.json") + ": there is no file of the host to put /mcpServers in"), report.toString());
        assertEquals(gemini.hooksContent(installation(java())), read(extension.resolve("hooks/hooks.json")));
    }

    @Test
    void anUninstallPutsAMemberBackToWhatTheHostsFileHeldAndKeepsOneThatChangedSince() throws IOException {
        Path extension = geminiExtension("{\"name\":\"sennard\",\"version\":\"6.0.0\",\"mcpServers\":{\"theirs\":{\"command\":\"another\"}}}");
        Path manifest = extension.resolve("gemini-extension.json");
        HostFiles files = new HostFiles(List.of(new GeminiPackageLocation(JSON)), "6.0.0", HOOKS_COMMAND, JSON);
        place(files, environment());

        List<String> report = files.removePlaced(generated());

        assertEquals(JSON.readTree("{\"name\":\"sennard\",\"version\":\"6.0.0\",\"mcpServers\":{\"theirs\":{\"command\":\"another\"}}}"), read(manifest), "the member is what it held before");
        assertTrue(report.contains("restored /mcpServers in " + manifest) && report.contains("removed " + extension.resolve("hooks/hooks.json")), report.toString());

        // A member that held nothing before is removed, and one the user changed after Sennard wrote it is kept.
        write(manifest, "{\"name\":\"sennard\",\"version\":\"6.0.0\"}");
        place(files, environment());
        assertTrue(read(manifest).has("mcpServers"));
        assertEquals(List.of("removed " + extension.resolve("hooks/hooks.json"), "restored /mcpServers in " + manifest), sorted(files.removePlaced(generated())));
        assertFalse(read(manifest).has("mcpServers"), "a member that was not there before is not left behind");

        place(files, environment());
        write(manifest, "{\"name\":\"sennard\",\"version\":\"6.0.0\",\"mcpServers\":{\"mine\":{\"command\":\"yet another\"}}}");
        List<String> kept = files.removePlaced(generated());
        assertTrue(kept.contains("kept " + manifest + ": /mcpServers changed after Sennard wrote it"), kept.toString());
        assertEquals("yet another", read(manifest).path("mcpServers").path("mine").path("command").asString(), "what the user wrote is not touched");
    }

    private static List<String> sorted(List<String> lines) {
        return lines.stream().sorted().toList();
    }

    /** An upgrade writes the member again, with another java: what the file held before Sennard the first time is what an uninstall puts back, not the first definition. */
    @Test
    void aMemberWrittenAgainByAnUpgradeStillRestoresWhatTheHostsFileHeldBeforeSennard() throws IOException {
        Path extension = geminiExtension("{\"name\":\"sennard\",\"version\":\"6.0.0\",\"mcpServers\":{\"theirs\":{\"command\":\"another\"}}}");
        Path manifest = extension.resolve("gemini-extension.json");
        HostFiles files = new HostFiles(List.of(new GeminiPackageLocation(JSON)), "6.0.0", HOOKS_COMMAND, JSON);
        Path otherJava = temporary.resolve("another jdk").resolve("bin").resolve("java");

        place(files, environment());
        placement(files, environment(), otherJava);

        assertEquals(otherJava.toString(), read(manifest).path("mcpServers").path("sennard").path("command").asString(), "the new definition is the file's");
        files.removePlaced(generated());
        assertEquals("another", read(manifest).path("mcpServers").path("theirs").path("command").asString());
        assertFalse(read(manifest).path("mcpServers").has("sennard"), "nothing of Sennard's is left");
    }

    @Test
    void everyVersionOfEveryMarketplaceOfCodexsPluginCacheWithTheSameMajorVersionIsWrittenAndTheOthersAreLeft() throws IOException {
        Path first = codexCopy("market-a", "6.0.0");
        Path second = codexCopy("market-b", "6.0.1");
        Path older = codexCopy("market-a", "5.9.0");
        write(first.resolve(".mcp.json"), "{\"mcpServers\":{\"theirs\":{}}}");
        Files.delete(second.resolve(".mcp.json"));
        HostFiles files = new HostFiles(everyLocation, "6.0.0", HOOKS_COMMAND, JSON);
        HookInstallation installation = installation(java());

        List<String> report = place(files, environment());

        for (Path root : List.of(first, second)) {
            assertEquals(codex.hooksContent(installation), read(root.resolve("hooks/hooks.json")), root.toString());
            assertEquals(codex.mcpContent(installation), read(root.resolve(".mcp.json")), "a whole file is made when the copy has none: " + root);
        }
        assertEquals(codex.shippedFiles().getFirst().content(), read(older.resolve("hooks/hooks.json")), "another major version is left");
        assertTrue(report.stream().anyMatch(line -> line.startsWith("left " + older.resolve("hooks/hooks.json")) && line.contains("5.9.0")), report.toString());
        assertFalse(report.stream().anyMatch(line -> line.startsWith("codex plugin is not installed yet")), report.toString());
    }

    @Test
    void theHomeOfCodexFollowsTheVariableAndNothingIsWrittenInTheHomeWhenItIsMoved() throws IOException {
        Path inHome = codexCopy("market", "6.0.0");
        Path moved = temporary.resolve("moved-codex").resolve("plugins/cache/market/sennard/6.0.0");
        writeShipped(moved, codex);
        HostFiles files = new HostFiles(everyLocation, "6.0.0", HOOKS_COMMAND, JSON);

        place(files, environment(Map.of("CODEX_HOME", temporary.resolve("moved-codex").toString())));

        assertEquals(codex.hooksContent(installation(java())), read(moved.resolve("hooks/hooks.json")));
        assertEquals(codex.shippedFiles().getFirst().content(), read(inHome.resolve("hooks/hooks.json")), "the home's own .codex is not the host's when the variable says another");
    }

    /** A definition that names this installation's java and jar must not outlive them, or the host runs a program that is gone (agy reads that as a denial). */
    @Test
    void uninstallDeletesWhatSennardWroteAndKeepsWhatChangedSince() throws IOException {
        Path claudeRoot = claudeCopy("6.0.0");
        claudeRecord(entry("user", claudeRoot, "6.0.0"));
        Path agyRoot = agyCopy("6.0.0");
        place(hostFiles(), environment());
        assertTrue(Files.readString(agyRoot.resolve("hooks.json"), StandardCharsets.UTF_8).contains("HookClientMain"), "the agy definition names this installation's hook client");
        assertTrue(Files.readString(agyRoot.resolve("hooks.json"), StandardCharsets.UTF_8).contains("sennard-hook-client-6.0.0.jar"), "and its jar, which goes with the installation");
        assertTrue(Files.readString(agyRoot.resolve("mcp_config.json"), StandardCharsets.UTF_8).contains("McpRelayMain"), "and the MCP registration its relay");
        String shipped = claude.shippedFiles().getFirst().content().toPrettyString();
        write(claudeRoot.resolve("hooks/hooks.json"), shipped); // the host re-copied the plugin after Sennard wrote it

        List<String> report = hostFiles().removePlaced(generated());

        assertFalse(Files.exists(agyRoot.resolve("hooks.json")) || Files.exists(agyRoot.resolve("mcp_config.json")), report.toString());
        assertTrue(report.contains("removed " + agyRoot.resolve("hooks.json")) && report.contains("removed " + agyRoot.resolve("mcp_config.json")), report.toString());
        assertEquals(shipped, Files.readString(claudeRoot.resolve("hooks/hooks.json"), StandardCharsets.UTF_8));
        assertTrue(report.contains("kept " + claudeRoot.resolve("hooks/hooks.json") + ": it changed after Sennard wrote it"), report.toString());
        assertTrue(report.contains("removed " + claudeRoot.resolve(".mcp.json")), "the file nobody changed goes: " + report);
    }

    @Test
    void aSecondRunWritesNothingNewAndTheRecordStillCoversTheFirstWrite() throws IOException {
        Path claudeRoot = claudeCopy("6.0.0");
        claudeRecord(entry("user", claudeRoot, "6.0.0"));
        HostFiles files = hostFiles();
        place(files, environment());

        List<String> again = place(files, environment());

        assertFalse(again.stream().anyMatch(line -> line.startsWith("wrote ")), again.toString());
        assertNotEquals(List.of(), files.removePlaced(generated()));
        assertFalse(Files.exists(claudeRoot.resolve("hooks/hooks.json")) || Files.exists(claudeRoot.resolve(".mcp.json")));
    }

    /** A copy whose file is not JSON (a user or a host edited it) is not this installation's definition: it is a value that says "write again", not a failure. */
    @Test
    void aCopyWhoseFileIsNotJsonIsWrittenAgainWithTheInstallationsDefinition() throws IOException {
        Path claudeRoot = claudeCopy("6.0.0");
        write(claudeRoot.resolve("hooks/hooks.json"), "{ not json");
        claudeRecord(entry("user", claudeRoot, "6.0.0"));

        List<String> report = place(hostFiles(), environment());

        assertEquals(claude.hooksContent(installation(java())), read(claudeRoot.resolve("hooks/hooks.json")), report.toString());
        assertTrue(report.contains("wrote " + claudeRoot.resolve("hooks/hooks.json")), report.toString());
    }

    /** The record of what Sennard wrote that cannot be read is no list to remove by: the uninstall keeps the files and says why, with the cause. */
    @Test
    void aRecordOfPlacedFilesThatIsNotJsonKeepsTheFilesAndSaysWhy() throws IOException {
        Path claudeRoot = claudeCopy("6.0.0");
        claudeRecord(entry("user", claudeRoot, "6.0.0"));
        HostFiles files = hostFiles();
        place(files, environment());
        write(generated().resolve("placed.json"), "{ not json");

        List<String> report = files.removePlaced(generated());

        assertEquals(1, report.size(), report.toString());
        assertTrue(report.getFirst().startsWith("kept the definitions written into host plugins: "), report.toString());
        assertTrue(Files.exists(claudeRoot.resolve("hooks/hooks.json")), "nothing is removed by a list that cannot be read");
    }

    /** The host's own record that cannot be read ends the command with the cause: a definition is not written by a guess at where the host reads it. */
    @Test
    void aHostRecordThatIsNotJsonFailsTheCommandWithTheCause() throws IOException {
        write(home().resolve(".claude/plugins/installed_plugins.json"), "not json");

        UnavailableException failure = assertThrows(UnavailableException.class, () -> place(hostFiles(), environment()));

        assertEquals(UnavailableCode.HOST_FILES_FAILED, failure.code());
        assertTrue(Sentences.english(failure).contains("installed_plugins.json"), Sentences.english(failure));
    }
}
