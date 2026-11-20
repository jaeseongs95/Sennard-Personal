// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseMetadataTest {
    @TempDir
    Path temporary;

    private Repo repo(String version) throws IOException {
        Repo repo = new Repo(temporary);
        repo.write("release/version.json", "{\"version\": \"" + version + "\"}\n");
        for (String manifest : List.of("plugin.json", ".codex-plugin/plugin.json", ".gemini-plugin/plugin.json", "gemini-extension/gemini-extension.json")) {
            repo.write(manifest, "{\n  \"name\": \"sennard\",\n  \"version\": \"1.0.0\"\n}\n");
        }
        repo.write(".agents/plugins/marketplace.json", """
                {
                  "plugins": [
                    {
                      "name": "sennard",
                      "source": {
                        "ref": "v1.0.0"
                      }
                    }
                  ]
                }
                """);
        repo.write(".claude-plugin/marketplace.json", """
                {
                  "plugins": [
                    {
                      "name": "sennard",
                      "version": "1.0.0",
                      "ref": "v1.0.0"
                    }
                  ],
                  "version": "1.0.0"
                }
                """);
        String blocks = """
                <!-- release-install:start -->
                x --ref v1.0.0 y
                <!-- release-install:end -->
                <!-- release-agy:start -->
                https://example.invalid/releases/download/v1.0.0/sennard-v1.0.0.zip
                <!-- release-agy:end -->
                """;
        repo.write("README.ko.md", "<!-- release-version:start -->\n현재 공개 릴리스는 `v1.0.0`입니다.\n<!-- release-version:end -->\n" + blocks);
        repo.write("README.md", "<!-- release-version:start -->\nThe current public release is `v1.0.0`.\n<!-- release-version:end -->\n" + blocks);
        repo.write("docs/contributing/roadmap.ko.md", "<!-- release-version:start -->\n현재 공개 릴리스는 `v1.0.0`\n<!-- release-version:end -->\n");
        repo.write("docs/contributing/roadmap.md", "<!-- release-version:start -->\nThe current public release is `v1.0.0`.\n<!-- release-version:end -->\n");
        Files.createDirectories(temporary.resolve("java"));
        repo.write("java/gradle.properties", "version=1.0.0\norg.gradle.daemon=false\n");
        return repo;
    }

    @Test
    void writeSetsEveryRecordOfTheVersionAndCheckThenPasses() throws IOException {
        Repo repo = repo("2.3.4");
        assertEquals(11, new ReleaseMetadata(repo).sync(true).size());
        assertEquals(List.of(), new ReleaseMetadata(repo).sync(false));
        assertTrue(repo.text("plugin.json").contains("\"version\": \"2.3.4\""));
        assertTrue(repo.text(".agents/plugins/marketplace.json").contains("\"ref\": \"v2.3.4\""));
        assertTrue(repo.text(".claude-plugin/marketplace.json").contains("\"ref\": \"v2.3.4\""));
        assertTrue(repo.text("README.ko.md").contains("현재 공개 릴리스는 `v2.3.4`"));
        assertTrue(repo.text("README.md").contains("--ref v2.3.4"));
        assertTrue(repo.text("README.md").contains("download/v2.3.4/sennard-v2.3.4.zip"));
        assertTrue(repo.text("docs/contributing/roadmap.md").contains("`v2.3.4`"));
        assertTrue(repo.text("docs/contributing/roadmap.ko.md").contains("`v2.3.4`"));
        assertTrue(repo.text("java/gradle.properties").contains("version=2.3.4\n"));
    }

    @Test
    void aStaleManifestIsReported() throws IOException {
        Repo repo = repo("2.3.4");
        new ReleaseMetadata(repo).sync(true);
        repo.write("plugin.json", "{\n  \"name\": \"sennard\",\n  \"version\": \"9.9.9\"\n}\n");
        assertEquals(List.of("plugin.json"), new ReleaseMetadata(repo).sync(false));
    }

    @Test
    void aDevBuildVersionIsLeftAloneUntilTheRelease() throws IOException {
        Repo repo = repo("2.3.4");
        new ReleaseMetadata(repo).sync(true);
        repo.write("java/gradle.properties", "version=3.0.0-dev\norg.gradle.daemon=false\n");
        assertEquals(List.of(), new ReleaseMetadata(repo).sync(false));
    }
}
