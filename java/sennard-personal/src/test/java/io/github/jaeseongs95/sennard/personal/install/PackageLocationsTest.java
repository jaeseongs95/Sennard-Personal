// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.server.hook.host.CodexHost;
import tools.jackson.databind.json.JsonMapper;

/**
 * The host files the package locations read are bound to records (the host's other fields are ignored).
 *
 * <pre>
 * p1  list       Claude's record with an install list, an install written as the install itself, another plugin and a missing folder: only this plugin's existing folders, with their versions
 * p2  cache      a copy under the plugins' cache is the host's own, one elsewhere is loaded in place
 * p3  unreadable a record that is not JSON is an IOException that names the file
 * p4  agy        the package's plugin.json gives the version, a package without one has an empty version
 * p5  gemini     the extension's gemini-extension.json gives the version, a plain folder is the host's own copy, no folder is no package, a manifest that is not JSON names the file
 * p6  codex      the plugin cache of the codex home (CODEX_HOME, else the home's .codex): plugins/cache/&lt;marketplace&gt;/sennard/&lt;version&gt;/, one package per version of every marketplace,
 *                all of them the host's own copies; another plugin, a plugin file and no cache are no package
 * </pre>
 */
class PackageLocationsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temporary;

    /** The home folder is the one given (the JVM's user.home in the product), so every location below the home is under the test's folder. */
    private InstallEnvironment environment() {
        return environment(Map.of("CLAUDE_CONFIG_DIR", temporary.resolve("claude").toString()));
    }

    private InstallEnvironment environment(Map<String, String> variables) {
        return new InstallEnvironment(variables, temporary, () -> temporary.resolve("jdk").resolve("bin").resolve("java"), (argv, environment, timeout) -> {
            throw new AssertionError("no command runs in these cases: " + argv);
        }, StandardCharsets.UTF_8);
    }

    private Path directory(String relative) throws IOException {
        return Files.createDirectories(temporary.resolve(relative));
    }

    @Test
    void claudesRecordYieldsThisPluginsExistingFoldersWithTheirVersions() throws IOException {
        Path cached = directory("claude/plugins/cache/m/sennard/6.0.0");
        Path inPlace = directory("local-plugin");
        String record = "{\"version\":2,\"plugins\":{"
                + "\"sennard@m\":[{\"scope\":\"user\",\"installPath\":" + JSON.writeValueAsString(cached.toString()) + ",\"version\":\"6.0.0\",\"extra\":1}],"
                + "\"sennard@local\":{\"installPath\":" + JSON.writeValueAsString(inPlace.toString()) + ",\"version\":\"6.0.1\"},"
                + "\"other@m\":[{\"installPath\":" + JSON.writeValueAsString(inPlace.toString()) + ",\"version\":\"9\"}],"
                + "\"sennard@gone\":[{\"installPath\":" + JSON.writeValueAsString(temporary.resolve("gone").toString()) + ",\"version\":\"1\"}]}}";
        Files.writeString(directory("claude/plugins").resolve("installed_plugins.json"), record, StandardCharsets.UTF_8);

        List<HostPackageLocation.InstalledPackage> found = new ClaudePackageLocation(JSON).packages(environment());

        assertEquals(List.of(new HostPackageLocation.InstalledPackage(cached.toAbsolutePath().normalize(), "6.0.0", true),
                new HostPackageLocation.InstalledPackage(inPlace.toAbsolutePath().normalize(), "6.0.1", false)), found);
    }

    @Test
    void aRecordThatIsNotJsonIsAnIoFailureThatNamesTheFile() throws IOException {
        Path record = directory("claude/plugins").resolve("installed_plugins.json");
        Files.writeString(record, "not json", StandardCharsets.UTF_8);
        IOException failure = assertThrows(IOException.class, () -> new ClaudePackageLocation(JSON).packages(environment()));
        assertEquals(true, failure.getMessage().contains(record.toString()), failure.getMessage());
    }

    @Test
    void antigravitysPackageGivesItsVersionFromItsManifest() throws IOException {
        Path root = directory(".gemini/config/plugins/sennard");
        assertEquals(List.of(new HostPackageLocation.InstalledPackage(root, "", true)), new AntigravityPackageLocation(JSON).packages(environment()));
        Files.writeString(root.resolve("plugin.json"), "{\"name\":\"sennard\",\"version\":\"6.0.0\"}", StandardCharsets.UTF_8);
        assertEquals(List.of(new HostPackageLocation.InstalledPackage(root, "6.0.0", true)), new AntigravityPackageLocation(JSON).packages(environment()));
    }

    @Test
    void geminisExtensionGivesItsVersionFromItsManifestAndNoExtensionIsNoPackage() throws IOException {
        assertEquals(List.of(), new GeminiPackageLocation(JSON).packages(environment()));
        Path root = directory(".gemini/extensions/sennard");
        assertEquals(List.of(new HostPackageLocation.InstalledPackage(root, "", true)), new GeminiPackageLocation(JSON).packages(environment()));
        Files.writeString(root.resolve("gemini-extension.json"), "{\"name\":\"sennard\",\"version\":\"6.0.0\",\"mcpServers\":{}}", StandardCharsets.UTF_8);
        assertEquals(List.of(new HostPackageLocation.InstalledPackage(root, "6.0.0", true)), new GeminiPackageLocation(JSON).packages(environment()));
    }

    @Test
    void aGeminiManifestThatIsNotJsonIsAnIoFailureThatNamesTheFile() throws IOException {
        Path manifest = directory(".gemini/extensions/sennard").resolve("gemini-extension.json");
        Files.writeString(manifest, "not json", StandardCharsets.UTF_8);
        IOException failure = assertThrows(IOException.class, () -> new GeminiPackageLocation(JSON).packages(environment()));
        assertEquals(true, failure.getMessage().contains(manifest.toString()), failure.getMessage());
    }

    @Test
    void codexsPluginCacheYieldsEveryVersionOfThisPluginInEveryMarketplaceAsTheHostsOwnCopy() throws IOException {
        Path older = directory(".codex/plugins/cache/market-a/sennard/5.1.0");
        Path current = directory(".codex/plugins/cache/market-a/sennard/6.0.0");
        Path other = directory(".codex/plugins/cache/market-b/sennard/6.0.0");
        directory(".codex/plugins/cache/market-a/another-plugin/9.9.9");
        Files.writeString(directory(".codex/plugins/cache/market-b/sennard").resolve("notes.txt"), "a file of the plugin's folder is no version", StandardCharsets.UTF_8);

        List<HostPackageLocation.InstalledPackage> found = new CodexPackageLocation().packages(environment(Map.of()));

        assertEquals(List.of(new HostPackageLocation.InstalledPackage(older, "5.1.0", true), new HostPackageLocation.InstalledPackage(current, "6.0.0", true),
                new HostPackageLocation.InstalledPackage(other, "6.0.0", true)), found, "sorted by marketplace and version; the folder's name is the version");
        assertEquals(CodexHost.ID, new CodexPackageLocation().host());
    }

    @Test
    void theCodexHomeVariableMovesTheCacheAndABlankOneDoesNot() throws IOException {
        Path inHome = directory(".codex/plugins/cache/market/sennard/6.0.0");
        Path moved = directory("elsewhere/plugins/cache/market/sennard/6.1.0");

        assertEquals(List.of(new HostPackageLocation.InstalledPackage(moved, "6.1.0", true)),
                new CodexPackageLocation().packages(environment(Map.of("CODEX_HOME", temporary.resolve("elsewhere").toString()))), "the variable wins over the home's .codex");
        assertEquals(List.of(new HostPackageLocation.InstalledPackage(inHome, "6.0.0", true)), new CodexPackageLocation().packages(environment(Map.of("CODEX_HOME", " "))), "a blank variable is none");
        assertEquals(List.of(new HostPackageLocation.InstalledPackage(inHome, "6.0.0", true)), new CodexPackageLocation().packages(environment(Map.of())));
    }

    @Test
    void withoutACodexPluginCacheThereIsNoPackage() throws IOException {
        assertEquals(List.of(), new CodexPackageLocation().packages(environment(Map.of())), "no codex home");
        directory(".codex/plugins/cache/market/another-plugin/1.0.0");
        assertEquals(List.of(), new CodexPackageLocation().packages(environment(Map.of())), "a cache without this plugin");
    }
}
