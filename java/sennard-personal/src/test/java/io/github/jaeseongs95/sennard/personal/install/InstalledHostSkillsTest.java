// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.host.AntigravityHost;
import io.github.jaeseongs95.sennard.server.hook.host.CodexHost;
import tools.jackson.databind.json.JsonMapper;

/**
 * The skill folders a host reports are those of the plugin copies its own package location finds, and nothing else.
 *
 * <pre>
 * s1  codex    every version in every marketplace of the plugin cache, each one's skills/ folder
 * s2  agy      the one copy under the gemini config's plugins folder
 * s3  unknown  a host with no location, and a host with no copy installed, report no folder
 * s4  unread   a location that cannot be read reports no folder (the read is not counted, no other place stands in)
 * </pre>
 */
class InstalledHostSkillsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temporary;

    private InstallEnvironment environment() {
        return new InstallEnvironment(Map.of(), temporary, () -> temporary.resolve("jdk").resolve("bin").resolve("java"), (argv, environment, timeout) -> {
            throw new AssertionError("no command runs in these cases: " + argv);
        }, StandardCharsets.UTF_8);
    }

    private InstalledHostSkills skills(HostPackageLocation... locations) {
        return new InstalledHostSkills(List.of(locations), this::environment);
    }

    @Test
    void codexReportsTheSkillsFolderOfEveryInstalledVersion() throws IOException {
        Path older = Files.createDirectories(temporary.resolve(".codex/plugins/cache/market-a/sennard/5.1.0"));
        Path current = Files.createDirectories(temporary.resolve(".codex/plugins/cache/market-b/sennard/6.0.0"));
        Files.createDirectories(temporary.resolve(".codex/plugins/cache/market-a/another-plugin/9.9.9"));
        assertEquals(List.of(older.resolve("skills").toAbsolutePath().normalize(), current.resolve("skills").toAbsolutePath().normalize()),
                skills(new CodexPackageLocation()).skillFolders(CodexHost.ID));
    }

    @Test
    void antigravityReportsTheSkillsFolderOfItsPluginCopy() throws IOException {
        Path root = Files.createDirectories(temporary.resolve(".gemini/config/plugins/sennard"));
        assertEquals(List.of(root.resolve("skills").toAbsolutePath().normalize()), skills(new AntigravityPackageLocation(JSON)).skillFolders(AntigravityHost.ID));
    }

    @Test
    void aHostWithNoLocationOrNoCopyReportsNothing() {
        InstalledHostSkills skills = skills(new CodexPackageLocation(), new AntigravityPackageLocation(JSON));
        assertEquals(List.of(), skills.skillFolders(CodexHost.ID), "nothing installed");
        assertEquals(List.of(), skills.skillFolders(AntigravityHost.ID), "nothing installed");
        assertEquals(List.of(), skills.skillFolders(new HostId("no-location")), "a host whose location is not known");
        assertEquals(List.of(), skills().skillFolders(CodexHost.ID), "no location at all");
    }

    /** A host's folders are found once: the location is read at the first question, and a later question is answered from what was found. */
    @Test
    void aFoundLocationIsReadOnceAndAnEmptyOneIsAskedAgain() throws IOException {
        int[] reads = {0};
        Path root = temporary.resolve("copy");
        List<HostPackageLocation.InstalledPackage> installed = new java.util.ArrayList<>();
        HostPackageLocation counting = new HostPackageLocation() {
            @Override
            public HostId host() {
                return CodexHost.ID;
            }

            @Override
            public List<InstalledPackage> packages(InstallEnvironment environment) {
                reads[0]++;
                return List.copyOf(installed);
            }
        };
        InstalledHostSkills skills = skills(counting);
        assertEquals(List.of(), skills.skillFolders(CodexHost.ID));
        assertEquals(List.of(), skills.skillFolders(CodexHost.ID));
        assertEquals(2, reads[0], "nothing was kept for a host with no copy");
        installed.add(new HostPackageLocation.InstalledPackage(root, "6.0.0", true));
        List<Path> first = skills.skillFolders(CodexHost.ID);
        assertEquals(List.of(root.resolve("skills").toAbsolutePath().normalize()), first);
        int after = reads[0];
        assertEquals(first, skills.skillFolders(CodexHost.ID));
        assertEquals(first, skills.skillFolders(CodexHost.ID));
        assertEquals(after, reads[0], "the found folders are kept, the location is not read again");
    }

    @Test
    void aLocationThatCannotBeReadReportsNothing() throws IOException {
        Path root = Files.createDirectories(temporary.resolve(".gemini/config/plugins/sennard"));
        Files.writeString(root.resolve("plugin.json"), "not json", StandardCharsets.UTF_8);
        assertEquals(List.of(), skills(new AntigravityPackageLocation(JSON)).skillFolders(AntigravityHost.ID));
    }
}
