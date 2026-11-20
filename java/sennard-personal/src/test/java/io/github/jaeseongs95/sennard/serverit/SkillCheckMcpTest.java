// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code run_skill_check} through the SDK's client on a real Tomcat: a judgment comes back, a refused input is an MCP error, and a schema is on the wire. */
class SkillCheckMcpTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static RunningServer server;

    @BeforeAll
    static void start() throws Exception {
        server = RunningServer.start(List.of(ServerFixtures.Beans.class));
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static McpSyncClient client() {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + server.port).endpoint("/mcp").build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(60)).build();
    }

    private static JsonNode text(McpSchema.CallToolResult result) {
        return MAPPER.readTree(((McpSchema.TextContent) result.content().get(0)).text());
    }

    private static JsonNode fixture(String relative) throws Exception {
        return MAPPER.readTree(Files.readString(Path.of(System.getProperty("sennard.repo.root"), "tests", relative), StandardCharsets.UTF_8));
    }

    @Test
    void aSkillCheckIsCallableAndItsVerdictArrives() throws Exception {
        try (McpSyncClient client = client()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("run_skill_check")
                    .arguments(OperationKeys.withKey("run_skill_check", Map.of("skill", "task-contract", "command", "validate", "arguments", Map.of("input", MAPPER.convertValue(fixture("task-contract/fixtures/normal/simple-read.json"), Map.class))))).build());
            assertFalse(Boolean.TRUE.equals(result.isError()), () -> text(result).toString());
            JsonNode body = text(result);
            assertTrue(body.get("ok").booleanValue(), body.toString());
            assertEquals(0, body.get("data").get("exitCode").intValue());
            assertEquals("PASS", body.get("data").get("value").get("output").get("verdict").stringValue());
        }
    }

    @Test
    void aRefusedInputIsAToolErrorWithItsCode() {
        try (McpSyncClient client = client()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("run_skill_check").arguments(OperationKeys.withKey("run_skill_check", Map.of("skill", "no-such-skill", "command", "x"))).build());
            assertTrue(Boolean.TRUE.equals(result.isError()));
            JsonNode refused = text(result);
            assertFalse(refused.get("ok").booleanValue(), refused.toString());
            assertEquals("INVALID_INPUT", refused.get("error").get("code").stringValue());
        }
    }
}
