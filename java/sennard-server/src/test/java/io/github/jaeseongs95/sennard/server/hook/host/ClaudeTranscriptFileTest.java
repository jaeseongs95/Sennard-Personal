// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.server.hook.host.ClaudeTranscriptFile.Tail;
import io.github.jaeseongs95.sennard.server.hook.host.ClaudeTranscriptFile.TailLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The end of a transcript file as {@link ClaudeTranscriptFile#tail} gives it. The window is the last 2 MiB and 4000 lines; the one line that begins before it is read once, for the small
 * fields of it that a reader of requests relies on, by a streaming parser ({@link ClaudeLineDigest}), and is never held whole.
 *
 * <pre>
 * t1  small file        every line whole, nothing older unread
 * t2  window in a line  the line that begins before the window is a digest: its marks, params.model, params.output_config.effort and the kinds of the blocks of message.content; none of its body
 * t3  window at a line  a window that begins exactly at the beginning of a line keeps that line whole
 * t4  too long          a line longer than ClaudeTranscriptFile.OVERSIZED_LINE_BYTES is told as too large
 * t5  being written     the last line, cut, whose beginning is before the window, has no digest (unreadable)
 * t6  lines limit       more than 4000 lines: the last 4000, and older lines unread
 * t7  digest            what a digest keeps of a line of every shape the reader is asked of
 * </pre>
 */
class ClaudeTranscriptFileTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int WINDOW = 2 * 1024 * 1024;

    @TempDir
    Path dir;

    private final ClaudeTranscriptFile files = new ClaudeTranscriptFile(MAPPER);

    private static String request(String model, int bodyBytes) {
        return "{\"type\":\"api-request\",\"querySource\":\"repl_main_thread\",\"sessionId\":\"s1\",\"promptId\":\"p1\",\"params\":{\"model\":\"" + model
                + "\",\"output_config\":{\"effort\":\"high\"},\"messages\":[{\"role\":\"user\",\"content\":\"" + "x".repeat(bodyBytes) + "\"}]}}";
    }

    private Path write(String... lines) throws IOException {
        Path file = dir.resolve("t.jsonl");
        Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }

    @Test
    void aSmallFileIsEveryLineWholeAndNothingOlderIsUnread() throws IOException {
        Tail tail = files.tail(write("{\"type\":\"user\"}", request("m", 10)));
        assertEquals(2, tail.lines().size());
        assertFalse(tail.olderUnread());
        assertEquals("{\"type\":\"user\"}", tail.lines().get(0).text());
    }

    @Test
    void theLineThatBeginsBeforeTheWindowIsADigestWithoutItsBody() throws IOException {
        Tail tail = files.tail(write(request("big-model", 3 * 1024 * 1024), "{\"type\":\"user\"}"));
        assertEquals(2, tail.lines().size());
        TailLine digest = tail.lines().get(0);
        assertNull(digest.text());
        JsonNode node = digest.digest();
        assertEquals("api-request", node.path("type").asString());
        assertEquals("repl_main_thread", node.path("querySource").asString());
        assertEquals("s1", node.path("sessionId").asString());
        assertEquals("p1", node.path("promptId").asString());
        assertEquals("big-model", node.path("params").path("model").asString());
        assertEquals("high", node.path("params").path("output_config").path("effort").asString());
        assertTrue(node.path("params").path("messages").isMissingNode(), "the body of the request is not kept");
        assertTrue(node.toString().length() < 400, node.toString());
        assertFalse(tail.olderUnread(), "the line begins at the beginning of the file");
        assertEquals("{\"type\":\"user\"}", tail.lines().get(1).text());
    }

    @Test
    void aWindowThatBeginsAtTheBeginningOfALineKeepsThatLineWhole() throws IOException {
        String prefix = "{\"type\":\"user\",\"pad\":\"";
        String last = prefix + "y".repeat(WINDOW - 1 - prefix.length() - 2) + "\"}";
        Path file = write(request("m", 100), last);
        // The last line and its newline are exactly the window: it begins where the line begins, so that line is whole and the one before is unread.
        Tail tail = files.tail(file);
        assertEquals(1, tail.lines().size());
        assertEquals(last, tail.lines().get(0).text());
        assertTrue(tail.olderUnread());
    }

    @Test
    void aLineLongerThanTheLimitIsToldAsTooLarge() throws IOException {
        Path file = dir.resolve("huge.jsonl");
        try (java.io.BufferedWriter out = Files.newBufferedWriter(file)) {
            out.write("{\"type\":\"api-request\",\"params\":{\"messages\":\"");
            String chunk = "x".repeat(1024 * 1024);
            for (long written = 0; written <= ClaudeTranscriptFile.OVERSIZED_LINE_BYTES; written += chunk.length()) {
                out.write(chunk);
            }
            out.write("\"}}\n");
        }
        Tail tail = files.tail(file);
        assertEquals(1, tail.lines().size());
        assertTrue(tail.lines().get(0).tooLarge());
    }

    @Test
    void aLastLineThatIsCutAndBeginsBeforeTheWindowHasNoDigest() throws IOException {
        Path file = dir.resolve("cut.jsonl");
        String cut = "{\"type\":\"api-request\",\"querySource\":\"repl_main_thread\",\"params\":{\"model\":\"m\",\"messages\":\"" + "x".repeat(3 * 1024 * 1024);
        Files.writeString(file, "{\"type\":\"user\"}\n" + cut);
        Tail tail = files.tail(file);
        assertEquals(1, tail.lines().size());
        assertTrue(tail.lines().get(0).unreadableEntry());
        assertTrue(files.entry(tail.lines().get(0), file).isEmpty());
    }

    @Test
    void moreThanTheLimitOfLinesIsTheLastOnesAndOlderLinesAreUnread() throws IOException {
        String[] lines = new String[4100];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = "{\"type\":\"user\",\"n\":" + i + "}";
        }
        Tail tail = files.tail(write(lines));
        assertEquals(4000, tail.lines().size());
        assertTrue(tail.olderUnread());
        assertEquals("{\"type\":\"user\",\"n\":4099}", tail.lines().get(3999).text());
    }

    @Test
    void aDigestKeepsTheMarksAndTheKindsOfTheBlocksAndNothingElse() throws IOException {
        String line = "{\"type\":\"assistant\",\"uuid\":\"u1\",\"parentUuid\":null,\"logicalParentUuid\":\"u0\",\"sessionId\":\"s1\",\"agentId\":\"A\",\"isSidechain\":true,\"isMeta\":false,"
                + "\"effort\":\"high\",\"timestamp\":\"2026-01-01T00:00:00.000Z\",\"cwd\":\"/some/where\",\"number\":7,\"nested\":{\"a\":[1,2,{\"b\":\"c\"}]},"
                + "\"message\":{\"model\":\"m\",\"usage\":{\"input_tokens\":3},\"content\":[{\"type\":\"text\",\"text\":\"secret words\"},{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"n\",\"input\":{\"x\":1}},"
                + "\"a string block\",{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_0\",\"content\":[{\"type\":\"text\",\"text\":\"more\"}]}]}}";
        JsonNode digest = ClaudeLineDigest.of(MAPPER, new java.io.ByteArrayInputStream(line.getBytes(java.nio.charset.StandardCharsets.UTF_8))).orElseThrow();
        assertEquals("{\"type\":\"assistant\",\"uuid\":\"u1\",\"logicalParentUuid\":\"u0\",\"sessionId\":\"s1\",\"agentId\":\"A\",\"isSidechain\":true,\"isMeta\":false,\"effort\":\"high\","
                + "\"timestamp\":\"2026-01-01T00:00:00.000Z\",\"message\":{\"model\":\"m\",\"content\":[{\"type\":\"text\"},{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"n\"},"
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_0\"}]}}", digest.toString());
        ClaudeEntry entry = new ClaudeEntry(digest, null);
        assertEquals(ClaudeEntry.Kind.ASSISTANT, entry.kind());
        assertTrue(entry.issuesToolUse("toolu_1"));
        assertTrue(entry.compactBoundary());
        assertNotNull(entry.model().orElse(null));
        // A line that is not a whole object has no digest.
        assertTrue(ClaudeLineDigest.of(MAPPER, new java.io.ByteArrayInputStream("{\"type\":\"user\",\"message\":{\"content\":\"cut".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEmpty());
        assertTrue(ClaudeLineDigest.of(MAPPER, new java.io.ByteArrayInputStream("[1]".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEmpty());
    }

    @Test
    void theSubfoldersAreTheFolderOnesInNameOrder() throws IOException {
        Files.createDirectories(dir.resolve("b"));
        Files.createDirectories(dir.resolve("a"));
        Files.writeString(dir.resolve("c.txt"), "not a folder");
        assertEquals(List.of(dir.resolve("a"), dir.resolve("b")), files.subfolders(dir));
    }
}
