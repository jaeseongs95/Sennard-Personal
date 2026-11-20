// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;

/**
 * Cases of the classification digest tool ({@code updateClassificationDigests}): the digest of a span is the SHA-256 of the whole file it cites;
 * the tool rewrites a stale digest and nothing else, never moves a span the author placed, and fails on a span that no longer fits its file.
 */
class ClassificationDigestsTest {
    private static final String SKILL = "skills/demo/SKILL.md";
    private static final String METADATA = "skills/demo/classification.json";
    private static final String TEXT = "---\nname: demo\n---\nfirst line\nsecond line\nthird line\n";
    private static final List<String> FILES = List.of(SKILL, METADATA);
    private static final String ZERO = "sha256:" + "0".repeat(64);

    @TempDir
    Path temporary;

    private static String sha256(String text) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String metadata(String path, int startLine, int endLine, String digest, String bytes) {
        return """
                {
                  "schemaVersion": "1.0.0",
                  "applicability": [
                    {
                      "path": "%s",
                      "startLine": %d,
                      "endLine": %d,
                      "digest": "%s"%s
                    }
                  ],
                  "exclusions": []
                }
                """.formatted(path, startLine, endLine, digest, bytes);
    }

    private Repo repo(String metadata) {
        Repo repo = new Repo(temporary);
        repo.write(SKILL, TEXT);
        repo.write(METADATA, metadata);
        return repo;
    }

    @Test
    void aStaleDigestIsReportedAndNothingIsWrittenWithoutTheWriteMode() {
        Repo repo = repo(metadata(SKILL, 4, 5, ZERO, ""));
        byte[] before = repo.bytes(METADATA);
        assertEquals(List.of(SKILL), new ClaudePlugin(repo).classificationDigests(FILES, false));
        assertArrayEquals(before, repo.bytes(METADATA));
    }

    @Test
    void theWriteModeSetsTheDigestOfTheCitedFileAndMovesNoSpan() throws Exception {
        Repo repo = repo(metadata(SKILL, 4, 5, ZERO, ",\n      \"startByte\": 0,\n      \"endByte\": 10"));
        assertEquals(List.of(SKILL), new ClaudePlugin(repo).classificationDigests(FILES, true));
        JsonNode span = repo.json(METADATA).path("applicability").get(0);
        assertEquals(sha256(TEXT), span.path("digest").stringValue(""));
        assertEquals(4, span.path("startLine").asInt());
        assertEquals(5, span.path("endLine").asInt());
        assertEquals(0, span.path("startByte").asInt());
        assertEquals(10, span.path("endByte").asInt());
        assertEquals(List.of(), new ClaudePlugin(repo).classificationDigests(FILES, false));
    }

    @Test
    void aFileWhoseDigestIsCurrentIsLeftByteForByte() throws Exception {
        Repo repo = repo(metadata(SKILL, 4, 5, sha256(TEXT), ""));
        byte[] before = repo.bytes(METADATA);
        assertEquals(List.of(), new ClaudePlugin(repo).classificationDigests(FILES, true));
        assertArrayEquals(before, repo.bytes(METADATA));
    }

    @Test
    void aSpanBeyondTheEndOfTheFileFailsEvenInTheWriteModeAndWritesNothing() {
        Repo repo = repo(metadata(SKILL, 4, 99, ZERO, ""));
        byte[] before = repo.bytes(METADATA);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new ClaudePlugin(repo).classificationDigests(FILES, true));
        assertEquals("Invalid classification span range: " + SKILL, failure.getMessage());
        assertArrayEquals(before, repo.bytes(METADATA));
    }

    @Test
    void aByteRangeBeyondTheCitedLinesFailsInTheWriteModeEvenWhenTheFileIsLonger() {
        // Lines 4-5 are "first line\nsecond line" (22 bytes); the whole file is longer than 30 bytes, but the bytes count within the cited lines.
        Repo repo = repo(metadata(SKILL, 4, 5, ZERO, ",\n      \"startByte\": 0,\n      \"endByte\": 30"));
        byte[] before = repo.bytes(METADATA);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new ClaudePlugin(repo).classificationDigests(FILES, true));
        assertEquals("Invalid classification span range: " + SKILL, failure.getMessage());
        assertArrayEquals(before, repo.bytes(METADATA));
    }

    @Test
    void aSpanOutsideTheSkillFolderFails() throws Exception {
        Repo repo = repo(metadata("skills/other/SKILL.md", 4, 5, sha256(TEXT), ""));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new ClaudePlugin(repo).classificationDigests(FILES, true));
        assertEquals("Invalid classification span path: " + METADATA, failure.getMessage());
    }
}
