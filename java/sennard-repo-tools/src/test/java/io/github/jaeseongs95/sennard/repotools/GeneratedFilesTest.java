// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.server.hook.HookBody;
import io.github.jaeseongs95.sennard.server.hook.HookInstallation;
import io.github.jaeseongs95.sennard.server.hook.HookOutput;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostDefinition;
import io.github.jaeseongs95.sennard.server.hook.HostDefinitions;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostRegistrationFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What the release ships from the repository: the plugin packages match what the tools generate and carry no Node. */
class GeneratedFilesTest {
    private static final Repo REPO = new Repo(Path.of(System.getProperty("sennard.repo.root")));
    private static final List<String> LICENSE_NOTICES = List.of("LICENSE", "ADDITIONAL-TERMS.md", "ADDITIONAL-TERMS.ko.md");

    @Test
    void theHookDefinitionsAreTheAdaptersOutput() {
        assertEquals(List.of(), new HostHookFiles(REPO).check());
    }

    /** A host that exists only in this test: its definition names an adapter whose definition file is one fixed object. */
    private static final class FakeHost implements HostDefinition {
        static final HostId ID = new HostId("fake-host-test");

        @Override
        public HostId host() {
            return ID;
        }

        @Override
        public Class<?> inputType() {
            return Object.class;
        }

        @Override
        public HostHookAdapter hookAdapter(JsonMapper mapper) {
            return new HostHookAdapter() {
                @Override
                public HostId host() {
                    return ID;
                }

                @Override
                public Class<?> inputType() {
                    return Object.class;
                }

                @Override
                public HostEvent read(String event, HookBody body) {
                    throw new UnsupportedOperationException("This test host answers no hook.");
                }

                @Override
                public HookOutput respond(String event, HostEvent read, HookVerdict verdict) {
                    throw new UnsupportedOperationException("This test host answers no hook.");
                }

                @Override
                public List<HostRegistrationFile> registrationFiles(HookInstallation installation) {
                    return List.copyOf(shippedFiles());
                }

                @Override
                public List<HostRegistrationFile.WholeFile> shippedFiles() {
                    return List.of(new HostRegistrationFile.WholeFile(Path.of("hooks", "hooks.json"), mapper.readTree("{\"fake\":true}")));
                }
            };
        }

        @Override
        public String packageDirectory() {
            return "fake-host";
        }
    }

    @Test
    void aHostAddedByItsDefinitionAloneHasItsHookDefinitionGeneratedWithoutAnEditHere(@TempDir Path directory) {
        List<HostDefinition> hosts = new ArrayList<>(HostDefinitions.load().all());
        hosts.add(new FakeHost());
        HostHookFiles files = new HostHookFiles(new Repo(directory), HostDefinitions.of(hosts));

        Set<String> expected = new TreeSet<>(new HostHookFiles(REPO).render().keySet());
        expected.add("fake-host/hooks/hooks.json");
        assertEquals(expected, files.render().keySet(), "the product's four hosts keep their files and the added one has its own");
        assertEquals(8, files.render().size(), "Claude Code, Codex and Antigravity ship a hook definition and an MCP registration, Gemini CLI a hook definition (its registration is a member of its manifest), and the added host one file");
        files.write();
        assertEquals(List.of(), files.check());
        assertEquals("{\n  \"fake\": true\n}\n", new Repo(directory).text("fake-host/hooks/hooks.json").replace("\r\n", "\n"));
    }

    @Test
    void claudePluginIsFresh() {
        assertEquals(List.of(), new ClaudePlugin(REPO).check());
    }

    @Test
    void antigravityPackageKeepsLicenseNotices(@TempDir Path temporaryDirectory) throws IOException {
        Path archive = temporaryDirectory.resolve("antigravity.zip");
        new ClaudePlugin(REPO).antigravityPackage("HEAD", archive.toString());
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (String notice : LICENSE_NOTICES) {
                String path = "sennard/" + notice;
                var entry = zip.getEntry(path);
                assertNotNull(entry, path);
                try (var content = zip.getInputStream(entry)) {
                    assertArrayEquals(REPO.bytes(notice), content.readAllBytes(), path);
                }
            }
        }
    }

    @Test
    void claudePackageKeepsLicenseNotices() {
        assertPackageLicenseNotices("claude-plugin");
    }

    @Test
    void geminiPackageKeepsLicenseNotices() {
        assertPackageLicenseNotices("gemini-extension");
    }

    private static void assertPackageLicenseNotices(String directory) {
        for (String notice : LICENSE_NOTICES) {
            String path = directory + "/" + notice;
            assertTrue(REPO.exists(path), path);
            assertArrayEquals(REPO.bytes(notice), REPO.bytes(path), path);
        }
    }

    /**
     * A package ships no hook and no MCP server: the server's port is the operating system's choice at each start, so what a definition names is the installation's own Java, jar and
     * address file, and {@code install}, {@code upgrade} and {@code hooks} write the definition into the host's copy. What the package has instead is the empty definition, which a host
     * reads as nothing registered, and a manifest with no {@code mcpServers}.
     */
    @Test
    void thePluginPackagesShipNoDefinitionAndNeedNoRuntimeOfTheirOwn() {
        for (String file : List.of(".mcp.json", "mcp_config.json", "hooks.json", "hooks/hooks.json", "claude-plugin/hooks/hooks.json", "claude-plugin/.mcp.json", "claude-plugin/.claude-plugin/plugin.json",
                "gemini-extension/gemini-extension.json", "gemini-extension/hooks/hooks.json")) {
            String text = REPO.text(file);
            assertTrue(!text.contains("node") && !text.contains(".mjs") && !text.contains("mcp-server"), file + ": still names a Node entry point");
            assertTrue(!text.contains("http://127.0.0.1") && !text.contains("\"command\""), file + ": names an address or a program, which are the installation's");
        }
        for (String file : List.of(".mcp.json", "mcp_config.json", "claude-plugin/.mcp.json", "claude-overlay/.mcp.json")) {
            assertEquals(0, REPO.json(file).path("mcpServers").size(), file + ": no server");
            assertTrue(REPO.json(file).path("mcpServers").isObject(), file + ": an empty object, not a missing member");
        }
        for (String file : List.of("hooks/hooks.json", "claude-plugin/hooks/hooks.json", "claude-overlay/hooks/hooks.json", "gemini-extension/hooks/hooks.json")) {
            assertEquals(0, REPO.json(file).path("hooks").size(), file + ": no hook");
            assertTrue(REPO.json(file).path("hooks").isObject(), file + ": an empty object, not a missing member");
        }
        assertEquals(0, REPO.json("hooks.json").size(), "the Antigravity file has no member that registers a hook");
        for (String manifest : List.of("claude-plugin/.claude-plugin/plugin.json", "claude-overlay/.claude-plugin/plugin.json", "gemini-extension/gemini-extension.json")) {
            assertTrue(!REPO.json(manifest).has("mcpServers"), manifest + ": the manifest registers no server; an installation writes it (the Gemini CLI extension's as a member)");
        }
    }

    @Test
    void noNodeEntryPointIsShipped() {
        for (String directory : List.of("claude-plugin", "skills", "contracts", "runtime", "antigravity-connector", "claude-overlay", "hooks")) {
            for (String file : REPO.walk(directory)) {
                assertTrue(!file.endsWith(".mjs") && !file.endsWith(".mts") && !file.endsWith(".cjs"), file);
            }
        }
        JsonNode agy = REPO.json("release/antigravity-package.json");
        agy.path("paths").forEach(path -> assertTrue(REPO.exists(path.stringValue("")), "the Antigravity package lists a missing path: " + path));
    }

    private static Map<String, byte[]> skill(String description) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("skills/x/SKILL.md", ("---\nname: x\ndescription: " + description + "\n---\n본문은 한 번만 나온다.\n").getBytes(StandardCharsets.UTF_8));
        return files;
    }

    private static JsonNode json(String text) {
        return Repo.JSON.readTree(text);
    }

    @Test
    void anAdaptationReplacesTheWholeDescriptionAndMatchesExactlyOnce() {
        Map<String, byte[]> files = skill("원래 설명");
        ClaudePlugin.applySkillAdaptation(files, "x", json("{\"description\": \"새 설명\", \"replacements\": [{\"file\": \"SKILL.md\", \"find\": \"본문은\", \"replace\": \"내용은\"}]}"), "x.json");
        String text = new String(files.get("skills/x/SKILL.md"), StandardCharsets.UTF_8);
        assertTrue(text.contains("description: 새 설명\n"));
        assertTrue(text.contains("내용은 한 번만"));
    }

    @Test
    void anAdaptationCannotRemoveCommonPolicyOrTheIntakeBlockOrUseUnknownKeys() {
        Map<String, byte[]> policy = new LinkedHashMap<>();
        policy.put("skills/x/SKILL.md", "---\nname: x\ndescription: 설명\n---\n역할 이름만 바꾼 자기감사는 독립 감사가 아니다.\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> ClaudePlugin.applySkillAdaptation(policy, "x",
                json("{\"replacements\": [{\"file\": \"SKILL.md\", \"find\": \"역할 이름만 바꾼 자기감사는\", \"replace\": \"자기감사는\"}]}"), "x.json"));
        Map<String, byte[]> intake = new LinkedHashMap<>();
        intake.put("skills/x/SKILL.md", "---\nname: x\ndescription: 설명\n---\n<!-- skill-intake:start -->\n원문\n<!-- skill-intake:end -->\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> ClaudePlugin.applySkillAdaptation(intake, "x",
                json("{\"replacements\": [{\"file\": \"SKILL.md\", \"find\": \"원문\", \"replace\": \"수정\"}]}"), "x.json"));
        assertThrows(IllegalStateException.class, () -> ClaudePlugin.applySkillAdaptation(skill("설명"), "x", json("{\"other\": 1}"), "x.json"));
        assertThrows(IllegalStateException.class, () -> ClaudePlugin.applySkillAdaptation(skill("설명"), "x",
                json("{\"replacements\": [{\"file\": \"SKILL.md\", \"find\": \"없는 문장\", \"replace\": \"y\"}]}"), "x.json"));
    }
}
