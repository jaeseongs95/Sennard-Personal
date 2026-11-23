// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.module.ModuleDescriptor.Version;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.hookclient.HookClientCall;
import io.github.jaeseongs95.sennard.hookclient.HookClientMain;
import io.github.jaeseongs95.sennard.hookclient.McpRelayMain;
import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.domain.jvm.JvmPresets;
import io.github.jaeseongs95.sennard.personal.ReleasedFolder;
import io.github.jaeseongs95.sennard.serverit.SharedDatabase;
import io.github.jaeseongs95.sennard.service.personal.OwnerOnlyAccess;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The commands as a user runs them: the release jar the build made, started with {@code java -jar} and a real server (a real Tomcat started by the install itself; there is no
 * registration with the operating system), on a PC's own {@code java}. Everything the product writes is under the test's folders: the child is given {@code -Duser.home}, the
 * installation's folder ({@code SENNARD_INSTALL_ROOT}) and the places of the host plugins' copies ({@link ReleaseJarFixture#isolated}), and the state folder is the test's. The server's
 * port is the operating system's choice at each start, so every port here is read from the address file the server wrote ({@link ServerAddress}) and never chosen.
 *
 * <p>The install trains two AOT caches and starts a server, so it takes a while: the cases that follow from one installation are one ordered flow and not one install each, and the
 * cases that need no installation (the refusals before it) run before it. The flow, in order:
 * <pre>
 * status, upgrade     nothing installed -> the status says so, the upgrade is refused (NOT_INSTALLED)
 * install --state-dir the version's folder (jar, lib, skills, contracts, settings, both AOT caches, no recording), the server running at the announced port, the host copies of
 *                     Claude Code (whole files) and Gemini CLI (one member of a manifest) hold definitions that run this PC's java, the hook client's jar and the address file
 * install again       refused (ALREADY_INSTALLED), the same server still runs
 * status              the command is {@code java -jar <installed jar>}, the versions, the port the file announces, the saved preset
 * stop, start         the process ends and the file is deleted; the server runs again at the port the file now announces
 * upgrade refused     an older jar (DOWNGRADE_REFUSED) and the same version (SAME_VERSION): nothing changes
 * upgrade             the newer version replaces the older folder, both settings files are carried, the server runs the new version, the definitions name the new jars
 * uninstall refused   run from the installed jar (UNINSTALL_FROM_INSTALLED_COPY): nothing changes
 * uninstall           the server stops, the definitions it wrote are put back, the folder is gone, the state folder stays
 * </pre>
 * No case asserts a time: the commands are waited for with a deadline that only stops a hung process.
 */
class ReleaseJarInstallFlowTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HOST_COPY_VERSION = ReleaseJarFixture.version();
    /** A version older than the build's, which an upgrade refuses. */
    private static final String OLDER_VERSION = "0.9.0";

    @TempDir
    Path temporary;

    private Path home;
    private Path state;
    private Map<String, String> environment;

    /** A server a case left running (it failed midway) must not outlive it, and its folders must be let go of before JUnit deletes the temporary folder. */
    @AfterEach
    void endTheServerAndReleaseTheFolders() throws Exception {
        if (state != null) {
            ReleaseJarFixture.stopServer(state.resolve(ServerAddress.FILE_NAME));
        }
        ReleasedFolder.await(temporary);
    }

    private ReleaseJarFixture.Run run(Path jar, String... arguments) throws Exception {
        List<String> words = new ArrayList<>(List.of("-jar", jar.toString()));
        words.addAll(List.of(arguments));
        return ReleaseJarFixture.run(ReleaseJarFixture.java(home, words.toArray(String[]::new)), null, environment);
    }

    private JsonNode status(Path jar) throws Exception {
        return ReleaseJarFixture.statusJson(run(jar, "status"));
    }

    private static InstalledVersion installedVersion(Path root, String version) {
        return new InstalledVersion(root.resolve(InstalledVersion.VERSIONS).resolve(version));
    }

    /** What {@code sennard status} reports as the command to run Sennard's commands with: the user's {@code java} on the installed jar (a path with a space is quoted, which this test's has not). */
    private static String commandOf(InstalledVersion installed) {
        return "java -jar " + installed.appJar();
    }

    private static List<String> versionsOf(JsonNode status) {
        List<String> versions = new ArrayList<>();
        status.path("versions").forEach(version -> versions.add(version.asString()));
        return versions;
    }

    private static List<String> words(JsonNode array) {
        List<String> words = new ArrayList<>();
        array.forEach(word -> words.add(word.asString()));
        return words;
    }

    /** The copy of the Claude Code plugin the host made for itself (its cache folder, recorded in {@code installed_plugins.json}), holding the definitions the package ships: empty ones. */
    private Path claudePluginCopy() throws IOException {
        Path plugins = home.resolve(".claude").resolve("plugins");
        Path copy = plugins.resolve("cache").resolve("sennard-claude").resolve("sennard").resolve(HOST_COPY_VERSION);
        Files.createDirectories(copy.resolve("hooks"));
        writeClaudeBody(copy, HOST_COPY_VERSION);
        Files.writeString(copy.resolve("hooks").resolve("hooks.json"), "{\"hooks\":{}}", StandardCharsets.UTF_8);
        Files.writeString(copy.resolve(".mcp.json"), "{\"mcpServers\":{}}", StandardCharsets.UTF_8);
        Files.writeString(plugins.resolve("installed_plugins.json"), "{\"version\":2,\"plugins\":{\"sennard@sennard-claude\":[{\"scope\":\"user\",\"installPath\":"
                + JSON.writeValueAsString(copy.toString()) + ",\"version\":\"" + HOST_COPY_VERSION + "\"}]}}", StandardCharsets.UTF_8);
        return copy;
    }

    private static void writeClaudeBody(Path copy, String version) throws IOException {
        Path manifest = copy.resolve(".claude-plugin/plugin.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, JSON.createObjectNode().put("version", version).toString(), StandardCharsets.UTF_8);
    }

    /**
     * The extension Gemini CLI copied for itself: its manifest holds other MCP servers of the user's, and the installation sets one member of it ({@code mcpServers}) and puts it back when it
     * is removed.
     */
    private Path geminiManifest() throws IOException {
        Path extension = home.resolve(".gemini").resolve("extensions").resolve("sennard");
        Files.createDirectories(extension);
        Path manifest = extension.resolve("gemini-extension.json");
        Files.writeString(manifest, "{\"name\":\"sennard\",\"version\":\"" + HOST_COPY_VERSION + "\",\"mcpServers\":{\"other\":{\"command\":\"other-server\"}}}", StandardCharsets.UTF_8);
        return manifest;
    }

    /**
     * What a definition that runs one of the installation's programs says: this PC's {@code java} (by absolute path: a host starts a command without the user's shell), the options of the
     * program, the hook client's jar as the whole class path, the program's main class, and the two places the installation tells the client (the address file the server writes and
     * the installed jar that starts it).
     */
    private static void assertProgram(String command, List<String> arguments, InstalledVersion installed, Path addressFile, String mainClass) throws IOException {
        Path java = Path.of(ProcessHandle.current().info().command().orElseThrow());
        assertTrue(Files.isSameFile(java, Path.of(command)), "the definition runs the java of this PC (" + java + "): " + command);
        int classPath = arguments.indexOf("-cp");
        assertTrue(classPath >= 0, arguments.toString());
        assertEquals(installed.hookClientJar().toString(), arguments.get(classPath + 1), "the client's jar is the whole class path: " + arguments);
        assertEquals(mainClass, arguments.get(classPath + 2), arguments.toString());
        assertTrue(arguments.contains("-XX:AOTCache=" + installed.folder().resolve(JvmOptions.HOOK_AOT_CACHE_FILE)), "the client starts from the AOT cache made in the installed folder: " + arguments);
        assertTrue(arguments.contains(HookClientCall.SERVER_FILE + "=" + addressFile), "the client finds the server through the file the server writes: " + arguments);
        assertTrue(arguments.contains(HookClientCall.JAR + "=" + installed.appJar()), "the client starts the server from the installed jar: " + arguments);
    }

    /** The definitions the installation wrote into the host copies, which run the programs of {@code installed}. */
    private void assertDefinitions(Path claudeCopy, Path geminiManifest, InstalledVersion installed) throws IOException {
        Path addressFile = state.resolve(ServerAddress.FILE_NAME);
        JsonNode claudeStart = JSON.readTree(Files.readString(claudeCopy.resolve("hooks").resolve("hooks.json"), StandardCharsets.UTF_8)).path("hooks").path("SessionStart").get(0).path("hooks").get(0);
        assertProgram(claudeStart.path("command").asString(), words(claudeStart.path("args")), installed, addressFile, HookClientMain.class.getName());
        JsonNode claudeServer = JSON.readTree(Files.readString(claudeCopy.resolve(".mcp.json"), StandardCharsets.UTF_8)).path("mcpServers").path("sennard");
        assertProgram(claudeServer.path("command").asString(), words(claudeServer.path("args")), installed, addressFile, McpRelayMain.class.getName());
        JsonNode geminiTree = JSON.readTree(Files.readString(geminiManifest, StandardCharsets.UTF_8));
        JsonNode geminiServer = geminiTree.path("mcpServers").path("sennard");
        assertProgram(geminiServer.path("command").asString(), words(geminiServer.path("args")), installed, addressFile, McpRelayMain.class.getName());
        assertEquals(installed.version(), geminiTree.path("version").asString(), "the rest of the host's manifest is kept");
        assertTrue(Files.isRegularFile(geminiManifest.resolveSibling("hooks").resolve("hooks.json")), "the Gemini CLI hook definition was written");
    }

    private Map<String, String> environmentOf(Path root, Map<String, String> more) {
        Map<String, String> overrides = new HashMap<>(more);
        overrides.put("SENNARD_INSTALL_ROOT", root.toString());
        return ReleaseJarFixture.isolated(home, overrides);
    }

    @Test
    void setupHasHelpAndRefusesToRunOnAHeadlessDesktopWithoutInstalling() throws Exception {
        home = temporary.resolve("home");
        state = temporary.resolve("state");
        Path root = temporary.resolve("install-root");
        environment = environmentOf(root, Map.of("SENNARD_STATE_DIR", state.toString()));
        String jar = ReleaseJarFixture.releaseJar().toString();
        ReleaseJarFixture.Run help = ReleaseJarFixture.run(
                ReleaseJarFixture.java(home, "-Djava.awt.headless=true", "-jar", jar, "setup", "--help"), null, environment);
        assertEquals(0, help.exitCode(), help.stdout());
        assertTrue(help.stdout().contains("Opens the desktop installer and updater."), help.stdout());
        ReleaseJarFixture.Run setup = ReleaseJarFixture.run(
                ReleaseJarFixture.java(home, "-Djava.awt.headless=true", "-jar", jar, "setup"), null, environment);
        assertEquals(1, setup.exitCode(), setup.stdout());
        assertTrue(setup.stdout().contains("Use 'install', 'upgrade', or 'status'"), setup.stdout());
        assertFalse(Files.exists(root));
        assertFalse(Files.exists(state));
    }

    @Test
    void installAndHooksReturnFailureForMismatchedBodyAndStatusIsUnhealthy() throws Exception {
        home = temporary.resolve("home");
        state = temporary.resolve("state");
        Path root = temporary.resolve("install-root");
        environment = environmentOf(root, Map.of("SENNARD_STATE_DIR", state.toString(), "SENNARD_STORE_PREPARE", "false"));
        Path copy = claudePluginCopy();
        Path jar = ReleaseJarFixture.releaseJar();
        writeClaudeBody(copy, "0.0.0");

        ReleaseJarFixture.Run refused = run(jar, "install");
        assertEquals(1, refused.exitCode(), refused.stdout());
        assertTrue(refused.stdout().contains("HOST_PACKAGE_VERSION_MISMATCH"), refused.stdout());
        assertTrue(refused.stdout().contains("sennard@sennard-claude (scope=user)"), refused.stdout());
        assertFalse(Files.exists(root));
        assertFalse(Files.exists(state));

        writeClaudeBody(copy, HOST_COPY_VERSION);
        ReleaseJarFixture.Run installed = run(jar, "install");
        assertEquals(0, installed.exitCode(), installed.stdout());
        writeClaudeBody(copy, "0.0.0");
        byte[] existing = Files.readAllBytes(copy.resolve("hooks/hooks.json"));
        ReleaseJarFixture.Run hooks = run(jar, "hooks");
        assertEquals(1, hooks.exitCode(), hooks.stdout());
        assertTrue(hooks.stdout().contains("HOST_PACKAGE_VERSION_MISMATCH"), hooks.stdout());
        assertArrayEquals(existing, Files.readAllBytes(copy.resolve("hooks/hooks.json")));

        ReleaseJarFixture.Run status = run(jar, "status");
        assertEquals(1, status.exitCode(), status.stdout());
        JsonNode diagnosis = JSON.readTree(status.stdout().substring(status.stdout().indexOf('{')));
        assertFalse(diagnosis.path("healthy").asBoolean(true));
        JsonNode mismatch = diagnosis.path("hostPackageMismatches").path(0);
        assertEquals(copy.toString(), mismatch.path("path").asString());
        assertEquals("0.0.0", mismatch.path("actualVersion").asString());
        assertEquals(HOST_COPY_VERSION, mismatch.path("expectedVersion").asString());
        assertEquals("sennard@sennard-claude (scope=user)", mismatch.path("installation").asString());
    }

    private record UpgradeFixture(Path root, Path hostCopy, Path release, InstalledVersion old, InstalledVersion next) {
    }

    private UpgradeFixture prepareUpgrade(String nextVersion) throws Exception {
        home = temporary.resolve("home");
        state = temporary.resolve("state");
        Path root = temporary.resolve("install-root");
        environment = environmentOf(root, Map.of("SENNARD_STATE_DIR", state.toString(), "SENNARD_STORE_PREPARE", "false"));
        Path copy = claudePluginCopy();
        ReleaseJarFixture.Run install = run(ReleaseJarFixture.releaseJar(), "install");
        assertEquals(0, install.exitCode(), install.stdout());
        writeClaudeBody(copy, nextVersion);
        Path release = ReleaseJarFixture.releaseJarWithVersion(temporary.resolve("newer"), nextVersion);
        return new UpgradeFixture(root, copy, release, installedVersion(root, HOST_COPY_VERSION), installedVersion(root, nextVersion));
    }

    @Test
    void majorUpgradeKeepsJarsReferencedByOldHookOrMcp() throws Exception {
        int major = Integer.parseInt(HOST_COPY_VERSION.substring(0, HOST_COPY_VERSION.indexOf('.')));
        UpgradeFixture fixture = prepareUpgrade((major + 1) + ".0.0");
        Path savedDefinition = temporary.resolve("unreloaded-mcp.json");
        Files.copy(fixture.hostCopy().resolve(".mcp.json"), savedDefinition);
        String cached = Files.readString(savedDefinition);
        assertTrue(cached.contains(fixture.old().hookClientJar().getFileName().toString()), cached);

        ReleaseJarFixture.Run upgraded = run(fixture.release(), "upgrade");

        assertEquals(0, upgraded.exitCode(), upgraded.stdout());
        assertTrue(Files.isRegularFile(fixture.old().appJar()));
        assertTrue(Files.isRegularFile(fixture.old().hookClientJar()));
        assertTrue(Files.isRegularFile(fixture.next().appJar()));
        assertEquals(cached, Files.readString(savedDefinition));
    }

    /** Fail the atomic publish of one generated definition with a nonempty directory in its place. */
    private Path interruptPlacement(UpgradeFixture fixture) throws Exception {
        Path blocked = fixture.root().resolve("host-packages/claude/.mcp.json");
        Files.delete(blocked);
        Files.createDirectory(blocked);
        Path sentinel = Files.writeString(blocked.resolve("sentinel"), "interrupted write");
        ReleaseJarFixture.Run failure = run(fixture.release(), "upgrade");
        assertEquals(1, failure.exitCode(), failure.stdout());
        assertTrue(failure.stdout().contains("HOST_FILES_FAILED"), failure.stdout());
        return sentinel;
    }

    @Test
    void interruptedPlacementKeepsBothJarPaths() throws Exception {
        UpgradeFixture fixture = prepareUpgrade(ReleaseJarFixture.newerVersion());
        byte[] oldMcp = Files.readAllBytes(fixture.hostCopy().resolve(".mcp.json"));

        interruptPlacement(fixture);

        for (InstalledVersion version : List.of(fixture.old(), fixture.next())) {
            assertTrue(Files.isRegularFile(version.appJar()), version.folder().toString());
            assertTrue(Files.isRegularFile(version.hookClientJar()), version.folder().toString());
        }
        assertArrayEquals(oldMcp, Files.readAllBytes(fixture.hostCopy().resolve(".mcp.json")));
    }

    @Test
    void sameVersionResumesOnlyWhenUpgradeWasIncomplete() throws Exception {
        UpgradeFixture fixture = prepareUpgrade(ReleaseJarFixture.newerVersion());
        Path sentinel = interruptPlacement(fixture);
        Files.delete(sentinel);
        Files.delete(sentinel.getParent());
        Path published = Files.writeString(fixture.next().folder().resolve("already-published"), "carried forward");

        ReleaseJarFixture.Run resumed = run(fixture.release(), "upgrade");
        assertEquals(0, resumed.exitCode(), resumed.stdout());
        assertEquals("carried forward", Files.readString(published), "the runtime was not republished");
        ReleaseJarFixture.Run completed = run(fixture.release(), "upgrade");
        assertEquals(1, completed.exitCode(), completed.stdout());
        assertTrue(completed.stdout().contains("SAME_VERSION"), completed.stdout());
    }

    @Test
    void codexWakeConfirmationUnavailableStopsUpgrade() throws Exception {
        home = temporary.resolve("home");
        state = temporary.resolve("state");
        Path root = temporary.resolve("install-root");
        Map<String, String> overrides = new HashMap<>();
        overrides.put("SENNARD_INSTALL_ROOT", root.toString());
        overrides.put("SENNARD_STATE_DIR", state.toString());
        overrides.put("SENNARD_STORE_PREPARE", "false");
        overrides.put("AGENT_GOVERNANCE_CODEX_QUEUE_WAKE", null);
        overrides.put("SENNARD_CODEX_QUEUE_WAKE", null);
        environment = ReleaseJarFixture.isolated(home, overrides);
        InstalledVersion old = ReleaseJarFixture.installedCopy(root, "1.3.0");
        ReleaseJarFixture.writeSettings(old, state, false);
        byte[] oldSettings = Files.readAllBytes(old.settingsFile());
        Path jar = ReleaseJarFixture.releaseJarWithVersion(temporary, "1.4.0");
        Path newFolder = installedVersion(root, "1.4.0").folder();
        assertFalse(Files.exists(newFolder));
        Path pluginData = home.resolve(".codex/plugins/data/sennard-sennard");
        Path wakeSetting = Files.writeString(Files.createDirectories(pluginData).resolve("session-messaging.json"),
                "{\"schemaVersion\":\"1.0.0\",\"codex\":{\"queueWake\":false,\"extra\":1}}");
        byte[] oldWake = Files.readAllBytes(wakeSetting);

        ReleaseJarFixture.Run result = run(jar, "upgrade");

        assertEquals(1, result.exitCode(), result.stdout());
        assertTrue(result.stdout().contains("Codex queue wake"), result.stdout());
        assertFalse(Files.exists(newFolder), "the new runtime must not be published");
        assertFalse(Files.exists(state.resolve(ServerAddress.FILE_NAME)), "no new server is started");
        assertArrayEquals(oldSettings, Files.readAllBytes(old.settingsFile()));
        assertArrayEquals(oldWake, Files.readAllBytes(wakeSetting));
    }

    @Test
    void codexWakeAlreadyOnAllowsANoninteractiveUpgrade() throws Exception {
        home = temporary.resolve("home");
        state = temporary.resolve("state");
        Path root = temporary.resolve("install-root");
        Map<String, String> overrides = new HashMap<>();
        overrides.put("SENNARD_INSTALL_ROOT", root.toString());
        overrides.put("SENNARD_STATE_DIR", state.toString());
        overrides.put("SENNARD_STORE_PREPARE", "false");
        overrides.put("AGENT_GOVERNANCE_CODEX_QUEUE_WAKE", "1");
        overrides.put("SENNARD_CODEX_QUEUE_WAKE", null);
        environment = ReleaseJarFixture.isolated(home, overrides);
        InstalledVersion old = ReleaseJarFixture.installedCopy(root, "1.3.0");
        ReleaseJarFixture.writeSettings(old, state, false);
        Path jar = ReleaseJarFixture.releaseJarWithVersion(temporary, "1.4.0");

        ReleaseJarFixture.Run result = run(jar, "upgrade");

        assertEquals(0, result.exitCode(), result.stdout());
        assertTrue(Files.isRegularFile(installedVersion(root, "1.4.0").appJar()));
    }

    @Test
    void installStatusStopStartUpgradeAndUninstallOfTheReleaseJar() throws Exception {
        home = temporary.resolve("home");
        Path claudeCopy = claudePluginCopy();
        Path geminiManifest = geminiManifest();
        String originalGeminiServers = JSON.readTree(Files.readString(geminiManifest, StandardCharsets.UTF_8)).path("mcpServers").toString();
        Path root = temporary.resolve("install-root");
        // The state folder is named outside ASCII with letters this PC's native.encoding can write, and given to the command as its argument (what the Windows java launcher reads in that
        // encoding). It does not exist before the install.
        state = temporary.resolve("state-" + ReleaseJarFixture.nonAsciiLetters());
        Path addressFile = state.resolve(ServerAddress.FILE_NAME);
        // The state folder comes from --state-dir, written into the installed version's sennard.yaml. Spring Boot reads an environment variable before a settings file, so the
        // SENNARD_STATE_DIR the test run itself has (the build isolates it) is removed from the child: a null value removes a variable (ToolProcess.Request).
        Map<String, String> noStateVariable = new HashMap<>();
        noStateVariable.put("SENNARD_STATE_DIR", null);
        environment = environmentOf(root, noStateVariable);
        String version = ReleaseJarFixture.version();
        String newer = ReleaseJarFixture.newerVersion();
        assertTrue(Version.parse(OLDER_VERSION).compareTo(Version.parse(version)) < 0, "the downgrade fixture " + OLDER_VERSION + " must be older than the release " + version);
        assertTrue(Version.parse(newer).compareTo(Version.parse(version)) > 0, "the upgrade fixture " + newer + " must be newer than the release " + version);
        assertEquals(version.substring(0, version.indexOf('.')), newer.substring(0, newer.indexOf('.')), "the upgrade fixture must keep the host plugins' major version");
        Path jar = ReleaseJarFixture.releaseJar();

        JsonNode notInstalled = status(jar);
        assertEquals(root.toString(), notInstalled.path("installRoot").asString());
        assertEquals(List.of(), versionsOf(notInstalled), "nothing is installed");
        assertTrue(notInstalled.path("command").isMissingNode(), "there is no installed command to run: " + notInstalled);
        ReleaseJarFixture.Run upgradeOfNothing = run(jar, "upgrade");
        assertEquals(1, upgradeOfNothing.exitCode(), upgradeOfNothing.stdout());
        assertTrue(upgradeOfNothing.stdout().contains("NOT_INSTALLED"), upgradeOfNothing.stdout());
        assertFalse(Files.exists(root), "a refused command makes nothing");

        // ---- install
        ReleaseJarFixture.Run install = run(jar, "install", "--state-dir", state.toString());
        assertEquals(0, install.exitCode(), install.stdout());
        Path claudeSettings = home.resolve(".claude/settings.json");
        assertEquals("0", JSON.readTree(Files.readString(claudeSettings)).path("env").path(ClaudeStopSettings.KEY).asString());
        // The state folder did not exist before the install: it is made through the port of the storage of secrets, with an access list that has no inheritance (D-0268).
        OwnerOnlyAccess.assertOwnerOnlyFolder(state);
        InstalledVersion installed = installedVersion(root, version);
        assertTrue(Files.isRegularFile(installed.appJar()), "the application jar, named for the version: " + installed.appJar());
        assertTrue(Files.isDirectory(installed.folder().resolve("lib")), "the libraries the jar was extracted with");
        assertTrue(Files.isRegularFile(installed.hookClientJar()), "the hook client's jar, which a definition runs");
        assertTrue(Files.isDirectory(installed.folder().resolve("skills")), "the skills the jar holds, copied out for the server and the hosts to read as files");
        assertTrue(Files.isDirectory(installed.folder().resolve("contracts")), "the contract schemas the jar holds");
        JvmSettingsFile.Content jvm = JvmSettingsFile.read(installed.jvmSettingsFile()).orElseThrow();
        assertTrue(jvm.observedBytes() != null && jvm.observedBytes() > 0, "the installer saved the one memory reading it chose the preset from");
        assertEquals(JvmPresets.estimate(jvm.observedBytes()).preset(), jvm.preset(), "the saved preset is the default of that reading");
        assertTrue(install.stdout().contains("JVM preset " + jvm.preset() + ": estimated memory "), install.stdout());
        assertFalse(install.stdout().contains("warning: AOT"), "the AOT training did not fail: " + install.stdout());
        assertTrue(Files.size(installed.aotCache(jvm.preset().collector())) > 0, "the server's AOT cache of the preset's collector was trained in the installed folder: " + install.stdout());
        assertTrue(Files.size(installed.folder().resolve(JvmOptions.HOOK_AOT_CACHE_FILE)) > 0, "the hook client's AOT cache was made in the installed folder");
        assertFalse(Files.exists(installed.aotConfiguration()), "the server's training recording is gone once the cache exists");
        assertFalse(Files.exists(installed.folder().resolve(JvmOptions.HOOK_AOT_CONFIGURATION_FILE)), "the hook client's training recording is gone once the cache exists");
        assertTrue(install.stdout().contains("Run Sennard commands with: " + commandOf(installed)), install.stdout());
        assertTrue(Files.readString(installed.settingsFile(), StandardCharsets.UTF_8).contains("state-dir"), "the state folder the command was given is in the installed version's settings");
        // The server the install started runs, and the port the file announces is the one it listens on.
        ServerAddress first = ReleaseJarFixture.liveServer(addressFile);
        assertEquals(200, ReleaseJarFixture.serverHealth(first), install.stdout());
        assertDefinitions(claudeCopy, geminiManifest, installed);

        // ---- the second install
        ReleaseJarFixture.Run again = run(jar, "install", "--state-dir", state.toString());
        assertEquals(1, again.exitCode(), again.stdout());
        assertTrue(again.stdout().contains("ALREADY_INSTALLED"), again.stdout());
        assertEquals(first, ReleaseJarFixture.liveServer(addressFile), "nothing was changed: the same server runs");

        // ---- status
        JsonNode running = status(jar);
        assertTrue(running.path("healthy").booleanValue(), running.toString());
        assertEquals(first.port(), running.path("port").intValue(), "the status names the port the file announces");
        assertEquals(commandOf(installed), running.path("command").asString(), running.toString());
        assertEquals(List.of(version), versionsOf(running));
        assertEquals(jvm.preset().name(), running.path("jvmPreset").asString());
        assertTrue(running.path("hostCopiesWithoutDefinitions").isMissingNode(), "every host copy holds the installation's definition: " + running);

        // ---- stop and start
        ReleaseJarFixture.Run stop = run(jar, "stop");
        assertEquals(0, stop.exitCode(), stop.stdout());
        assertFalse(first.live(), "the server's process is gone");
        assertFalse(Files.exists(addressFile), "the server deleted the file that announced it");
        JsonNode stopped = status(jar);
        assertFalse(stopped.path("healthy").booleanValue(), stopped.toString());
        assertTrue(stopped.path("port").isMissingNode(), "no server, no port: " + stopped);
        ReleaseJarFixture.Run start = run(jar, "start");
        assertEquals(0, start.exitCode(), start.stdout());
        ServerAddress second = ReleaseJarFixture.liveServer(addressFile);
        assertNotEquals(first.pid(), second.pid(), "a new process");
        assertEquals(200, ReleaseJarFixture.serverHealth(second));
        JsonNode restarted = status(jar);
        assertTrue(restarted.path("healthy").booleanValue(), restarted.toString());
        assertEquals(second.port(), restarted.path("port").intValue(), "the status names the port the file now announces");

        // ---- the upgrades that are refused
        ReleaseJarFixture.Run downgrade = run(ReleaseJarFixture.releaseJarWithVersion(temporary.resolve("older"), OLDER_VERSION), "upgrade");
        assertEquals(1, downgrade.exitCode(), downgrade.stdout());
        assertTrue(downgrade.stdout().contains("DOWNGRADE_REFUSED"), downgrade.stdout());
        ReleaseJarFixture.Run same = run(jar, "upgrade");
        assertEquals(1, same.exitCode(), same.stdout());
        assertTrue(same.stdout().contains("SAME_VERSION"), same.stdout());
        assertEquals(List.of(version), versionsOf(status(jar)), "nothing was changed");
        assertEquals(second, ReleaseJarFixture.liveServer(addressFile), "the same server runs");

        // ---- upgrade
        Path newerJar = ReleaseJarFixture.releaseJarWithVersion(temporary.resolve("newer"), newer);
        byte[] settingsBefore = Files.readAllBytes(installed.settingsFile());
        byte[] jvmSettingsBefore = Files.readAllBytes(installed.jvmSettingsFile());
        ReleaseJarFixture.Run mismatch = run(newerJar, "upgrade");
        assertEquals(1, mismatch.exitCode(), mismatch.stdout());
        assertTrue(mismatch.stdout().contains("HOST_PACKAGE_VERSION_MISMATCH"), mismatch.stdout());
        assertEquals(second, ReleaseJarFixture.liveServer(addressFile));
        assertFalse(Files.exists(installedVersion(root, newer).folder()));

        writeClaudeBody(claudeCopy, newer);
        ObjectNode geminiBody = (ObjectNode) JSON.readTree(Files.readString(geminiManifest, StandardCharsets.UTF_8));
        Files.writeString(geminiManifest, geminiBody.put("version", newer).toString(), StandardCharsets.UTF_8);
        Files.writeString(claudeSettings, "{\"other\":true}");
        ReleaseJarFixture.Run upgrade = run(newerJar, "upgrade");
        assertEquals(0, upgrade.exitCode(), upgrade.stdout());
        JsonNode upgradedClaudeSettings = JSON.readTree(Files.readString(claudeSettings));
        assertEquals("0", upgradedClaudeSettings.path("env").path(ClaudeStopSettings.KEY).asString());
        assertTrue(upgradedClaudeSettings.path("other").asBoolean());
        InstalledVersion upgraded = installedVersion(root, newer);
        assertTrue(Files.isRegularFile(upgraded.appJar()), upgrade.stdout());
        assertTrue(Files.isRegularFile(installed.appJar()), "a host may still run an older definition");
        assertArrayEquals(settingsBefore, Files.readAllBytes(upgraded.settingsFile()), "the settings came along");
        assertArrayEquals(jvmSettingsBefore, Files.readAllBytes(upgraded.jvmSettingsFile()), "the upgrade keeps the saved preset and does not read the memory again");
        assertEquals(jvm, JvmSettingsFile.read(upgraded.jvmSettingsFile()).orElseThrow());
        assertFalse(upgrade.stdout().contains("warning: AOT"), "the AOT training did not fail: " + upgrade.stdout());
        assertTrue(Files.size(upgraded.aotCache(jvm.preset().collector())) > 0, "the upgraded version has the server's cache for its own folder and jar");
        assertTrue(Files.size(upgraded.folder().resolve(JvmOptions.HOOK_AOT_CACHE_FILE)) > 0, "the upgraded version has the hook client's cache for its own folder and jar");
        assertTrue(upgrade.stdout().contains("Run Sennard commands with: " + commandOf(upgraded)), upgrade.stdout());
        ServerAddress third = ReleaseJarFixture.liveServer(addressFile);
        assertFalse(second.live(), "the old version's server ended");
        assertEquals(200, ReleaseJarFixture.serverHealth(third));
        assertDefinitions(claudeCopy, geminiManifest, upgraded);
        // The installed jar belongs to its installation wherever the environment's root points; run from it, the status says the same.
        JsonNode upgradedStatus = status(upgraded.appJar());
        assertEquals(commandOf(upgraded), upgradedStatus.path("command").asString(), "the command is the newest version's jar");
        assertEquals(List.of(newer, version), versionsOf(upgradedStatus));
        assertEquals(third.port(), upgradedStatus.path("port").intValue());

        // ---- uninstall
        ReleaseJarFixture.Run fromInside = run(upgraded.appJar(), "uninstall");
        assertEquals(1, fromInside.exitCode(), fromInside.stdout());
        assertTrue(fromInside.stdout().contains("UNINSTALL_FROM_INSTALLED_COPY"), fromInside.stdout());
        assertEquals(third, ReleaseJarFixture.liveServer(addressFile), "nothing was changed: the server still runs");
        assertEquals(200, ReleaseJarFixture.serverHealth(third));
        ReleaseJarFixture.Run uninstall = run(newerJar, "uninstall");
        assertEquals(0, uninstall.exitCode(), uninstall.stdout());
        assertFalse(Files.exists(root), "the program is gone: " + uninstall.stdout());
        assertFalse(third.live(), "the server's process is gone");
        assertFalse(Files.exists(addressFile), "the server deleted the file that announced it");
        assertTrue(Files.isDirectory(state), "the state stays");
        assertFalse(Files.exists(claudeCopy.resolve("hooks").resolve("hooks.json")), "the Claude Code definition Sennard wrote is gone: " + uninstall.stdout());
        assertFalse(Files.exists(claudeCopy.resolve(".mcp.json")), "the Claude Code MCP registration Sennard wrote is gone: " + uninstall.stdout());
        assertFalse(Files.exists(geminiManifest.resolveSibling("hooks").resolve("hooks.json")), "the Gemini CLI definition Sennard wrote is gone: " + uninstall.stdout());
        assertEquals(originalGeminiServers, JSON.readTree(Files.readString(geminiManifest, StandardCharsets.UTF_8)).path("mcpServers").toString(),
                "the member of the manifest that Sennard set is put back to what it held: " + uninstall.stdout());
        assertTrue(uninstall.stdout().contains("Remove it there too"), uninstall.stdout());
    }

    /**
     * The first-install path from the user's seat: a real store (the business database the test run names, linked in the settings database of the state folder, which every command and
     * the server read) prepared by the install itself, then {@code sennard admin-reset} against it. The link is in the state folder, so it survives {@code sennard uninstall}.
     */
    @Test
    @Tag("business-db")
    void installPreparesTheStoreAndAdminResetReachesIt() throws Exception {
        home = temporary.resolve("home");
        Path root = temporary.resolve("install-root");
        state = temporary.resolve("state");
        Files.createDirectories(state);
        ReleaseJarFixture.linkBusinessDatabase(state);
        Map<String, String> settings = new HashMap<>();
        settings.put("SENNARD_STATE_DIR", state.toString());
        environment = environmentOf(root, settings);
        Path addressFile = state.resolve(ServerAddress.FILE_NAME);
        Path jar = ReleaseJarFixture.releaseJar();

        ReleaseJarFixture.Run install = run(jar, "install");
        assertEquals(0, install.exitCode(), install.stdout());
        InstalledVersion installed = installedVersion(root, ReleaseJarFixture.version());
        ServerAddress first = ReleaseJarFixture.liveServer(addressFile);
        assertEquals(200, ReleaseJarFixture.serverHealth(first), install.stdout());
        // The server the install started holds the settings database's file, so the command stops it first (and says so); the reset then reaches the store.
        ReleaseJarFixture.Run reset = run(installed.appJar(), "admin-reset", "--yes");
        assertEquals(0, reset.exitCode(), reset.stdout());
        assertTrue(reset.stdout().contains("\"reset\":{\"id\":"), reset.stdout());
        assertTrue(reset.stdout().contains("\"serverStopped\":true"), reset.stdout());
        assertTrue(reset.stdout().contains("\"notice\":\"The Sennard server of this installation was running"), reset.stdout());
        assertFalse(first.live(), "the server was stopped (the reset opened the file the server held)");

        // The preview opens the settings database too, so it stops a server that is up as well; a hook would have started it again, here `start` does.
        ReleaseJarFixture.Run restart = run(installed.appJar(), "start");
        assertEquals(0, restart.exitCode(), restart.stdout());
        ServerAddress second = ReleaseJarFixture.liveServer(addressFile);
        assertEquals(200, ReleaseJarFixture.serverHealth(second));
        ReleaseJarFixture.Run dryReset = run(installed.appJar(), "admin-reset");
        assertEquals(0, dryReset.exitCode(), dryReset.stdout());
        assertTrue(dryReset.stdout().contains("\"dryRun\":true"), dryReset.stdout());
        assertTrue(dryReset.stdout().contains("\"serverStopped\":true"), dryReset.stdout());
        assertFalse(second.live(), "the preview stopped the server");

        // No server is up: nothing is stopped, and the output does not say it was.
        ReleaseJarFixture.Run noServer = run(installed.appJar(), "admin-reset");
        assertEquals(0, noServer.exitCode(), noServer.stdout());
        assertTrue(noServer.stdout().contains("\"dryRun\":true"), noServer.stdout());
        assertFalse(noServer.stdout().contains("serverStopped"), noServer.stdout());

        ReleaseJarFixture.Run uninstall = run(jar, "uninstall");
        assertEquals(0, uninstall.exitCode(), uninstall.stdout());
        assertFalse(Files.exists(root), "the program is gone: " + uninstall.stdout());
        // The link of the business database is in the settings database of the state folder, which uninstall leaves: a reinstall on the folder finds the database linked.
        assertEquals(SharedDatabase.link(), SharedDatabase.linkedIn(state.resolve("settings")).orElseThrow(), "the link survives the uninstall in the state folder");
    }
}
