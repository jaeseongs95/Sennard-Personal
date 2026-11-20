// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * What a line of a Claude Code transcript says about itself: whether it opens a turn, and whether it was written before an instant.
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * e1  the user's message  a user line with text -> startsTurn; the same as a subagent's line (isSidechain), as a line the host injected (isMeta), as a tool result (a tool_result block)
 *                         and an assistant line -> not
 * e2  written before      a line written before the instant -> true; exactly at it -> false (the instant itself is not before); after it -> false; no timestamp, or one that is not an
 *                         instant -> true (never taken for a recent line)
 * </pre>
 */
class ClaudeEntryTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant T = Instant.parse("2026-11-12T10:00:00Z");

    private static ClaudeEntry entry(String json, Instant at) {
        return new ClaudeEntry(MAPPER.readTree(json), at);
    }

    @Test
    void onlyTheUsersOwnMessageOnTheMainThreadStartsATurn() {
        String user = "{\"type\":\"user\",\"uuid\":\"u\",\"message\":{\"role\":\"user\",\"content\":\"hello\"}";
        assertTrue(entry(user + "}", T).startsTurn());
        assertFalse(entry(user.replace("\"uuid\"", "\"isSidechain\":true,\"uuid\"") + "}", T).startsTurn(), "a subagent's line");
        assertFalse(entry(user.replace("\"uuid\"", "\"isMeta\":true,\"uuid\"") + "}", T).startsTurn(), "a line the host injected");
        assertFalse(entry("{\"type\":\"user\",\"uuid\":\"u\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t\",\"content\":\"out\"}]}}", T).startsTurn(),
                "a tool's result");
        assertFalse(entry("{\"type\":\"assistant\",\"uuid\":\"a\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}", T).startsTurn(), "the agent's line");
    }

    @Test
    void aLineIsWrittenBeforeAnInstantOnlyWhenItsTimeIsEarlier() {
        String line = "{\"type\":\"user\",\"uuid\":\"u\"}";
        assertTrue(entry(line, T.minusMillis(1)).writtenBefore(T));
        assertFalse(entry(line, T).writtenBefore(T), "the instant itself is not before it");
        assertFalse(entry(line, T.plusMillis(1)).writtenBefore(T));
        assertTrue(entry(line, null).writtenBefore(T), "a line with no usable time is never taken for a recent one");
    }
}
