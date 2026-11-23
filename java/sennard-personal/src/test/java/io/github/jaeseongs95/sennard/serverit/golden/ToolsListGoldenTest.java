// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.serverit.GoldenHarness;

/**
 * What a host reads before any call: the {@code initialize} answer and the exact bytes of {@code tools/list}, from the application with no
 * tool a test adds. Cases L-* of {@code golden/CASES.md}.
 */
class ToolsListGoldenTest {
    private static final String GROUP = "list";
    private static Locale before;
    private static GoldenHarness server;

    @BeforeAll
    static void start() throws Exception {
        before = Locale.getDefault();
        Locale.setDefault(Locale.US); // the case table's language; restored after the class
        server = GoldenHarness.plain();
    }

    @AfterAll
    static void stop() {
        try {
            server.close();
        } finally {
            Locale.setDefault(before);
        }
    }

    private static GoldenHarness.Reply post(String json) throws Exception {
        GoldenHarness.Reply reply = server.mcp(json.getBytes(StandardCharsets.UTF_8), null);
        assertEquals(200, reply.status(), () -> new String(reply.body(), StandardCharsets.UTF_8));
        return reply;
    }

    @Test
    void toolsListIsTheSameBytes() throws Exception {
        Golden.checkBytes(GROUP, "L-tools_list", post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}").body());
    }

    @Test
    void initializeIsTheSameAnswer() throws Exception {
        GoldenHarness.Reply reply = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"golden\",\"version\":\"1\"}}}");
        var answer = Golden.answer(reply);
        var releaseVersion = Golden.MAPPER.readTree(Files.readString(Golden.REPOSITORY.resolve("release/version.json"))).path("version");
        assertTrue(releaseVersion.isString() && !releaseVersion.asString().isBlank(), "release/version.json must declare a version string");
        assertEquals(releaseVersion, answer.at("/body/result/serverInfo/version"), "serverInfo.version must match release/version.json");
        Golden.check(GROUP, "L-initialize", answer, List.of(new Golden.Token("{RELEASE_VERSION}", releaseVersion.asString(), false)));
    }

    /** The refusals of the protocol itself, before any tool runs: what a host sees for a tool or a method this server does not have. */
    @Test
    void protocolRefusalsAreTheSameAnswers() throws Exception {
        Golden.check(GROUP, "L-ping", Golden.answer(post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")), List.of());
        Golden.check(GROUP, "L-unknown_tool", Golden.answer(server.mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"no_such_tool\",\"arguments\":{}}}"
                .getBytes(StandardCharsets.UTF_8), null)), List.of());
        Golden.check(GROUP, "L-unknown_method", Golden.answer(server.mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"no/such/method\"}".getBytes(StandardCharsets.UTF_8), null)), List.of());
    }

    @Test
    void theTableListsExactlyTheseCases() {
        Golden.assertTableCovers("L-", List.of("L-tools_list", "L-initialize", "L-ping", "L-unknown_tool", "L-unknown_method"));
    }
}
