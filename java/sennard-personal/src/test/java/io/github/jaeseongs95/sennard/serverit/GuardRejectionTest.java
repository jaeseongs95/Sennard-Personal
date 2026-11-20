// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The guard's rejection table, including the direct-write rows, on a real Tomcat. Each rejection is sent as bytes on a
 * socket and is checked twice: by its status, and by the handler behind the guard, which must not have been reached.
 */
class GuardRejectionTest {
    private static final byte[] JSON_BODY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8);
    private static final int LIMIT = 16 << 20;

    private static RunningServer server;
    private static ServerFixtures.Endpoints endpoints;

    @BeforeAll
    static void start() throws Exception {
        server = RunningServer.start(List.of(ServerFixtures.Beans.class));
        endpoints = server.bean(ServerFixtures.Endpoints.class);
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    record Row(String name, int status, String method, String path, Map<String, String> headers, byte[] body) {
        @Override
        public String toString() {
            return name + " -> " + status;
        }
    }

    private static Map<String, String> jsonHeaders() {
        return Map.of("Content-Type", "application/json", "Content-Length", Integer.toString(JSON_BODY.length));
    }

    private static Map<String, String> with(Map<String, String> base, String name, String value) {
        Map<String, String> copy = new java.util.LinkedHashMap<>(base);
        copy.put(name, value);
        return copy;
    }

    static Stream<Row> rejections() {
        // {port} in a Host value is the server's real port.
        return Stream.of(
                new Row("foreign origin on /mcp", 403, "POST", "/mcp", with(jsonHeaders(), "Origin", "http://evil.example"), JSON_BODY),
                new Row("opaque origin", 403, "POST", "/mcp", with(jsonHeaders(), "Origin", "null"), JSON_BODY),
                new Row("foreign origin on a hook", 403, "POST", "/hooks/v1/probe", with(jsonHeaders(), "Origin", "http://evil.example"), JSON_BODY),
                new Row("foreign origin on actuator", 403, "GET", "/actuator/health", Map.of("Origin", "http://evil.example"), null),
                new Row("dns rebinding host", 421, "POST", "/mcp", with(jsonHeaders(), "Host", "evil.example:{port}"), JSON_BODY),
                new Row("wrong port in host", 421, "POST", "/mcp", with(jsonHeaders(), "Host", "127.0.0.1:1"), JSON_BODY),
                new Row("host without a port", 421, "POST", "/mcp", with(jsonHeaders(), "Host", "127.0.0.1"), JSON_BODY),
                new Row("ipv6 literal host", 421, "POST", "/mcp", with(jsonHeaders(), "Host", "[::1]:{port}"), JSON_BODY),
                new Row("foreign host on actuator", 421, "GET", "/actuator/health", Map.of("Host", "evil.example:{port}"), null),
                new Row("text body", 415, "POST", "/mcp", Map.of("Content-Type", "text/plain", "Content-Length", Integer.toString(JSON_BODY.length)), JSON_BODY),
                new Row("no content type with a body", 415, "POST", "/mcp", Map.of("Content-Length", Integer.toString(JSON_BODY.length)), JSON_BODY),
                new Row("json in another charset", 415, "POST", "/mcp", Map.of("Content-Type", "application/json; charset=EUC-KR", "Content-Length", Integer.toString(JSON_BODY.length)), JSON_BODY),
                new Row("form on a hook", 415, "POST", "/hooks/v1/probe", Map.of("Content-Type", "application/x-www-form-urlencoded", "Content-Length", Integer.toString(JSON_BODY.length)), JSON_BODY),
                new Row("unknown protocol version", 400, "POST", "/mcp", with(jsonHeaders(), "MCP-Protocol-Version", "1999-01-01"), JSON_BODY),
                new Row("protocol version on a path parameter", 400, "POST", "/mcp;x=1", with(jsonHeaders(), "MCP-Protocol-Version", "1999-01-01"), JSON_BODY),
                // The stages answer in the order a request meets them: origin and host, then the protocol version, then the body.
                new Row("protocol version is judged before the body", 400, "POST", "/mcp",
                        Map.of("Content-Type", "text/plain", "Content-Length", Integer.toString(JSON_BODY.length), "MCP-Protocol-Version", "1999-01-01"), JSON_BODY),
                new Row("origin is judged before the protocol version", 403, "POST", "/mcp", with(with(jsonHeaders(), "Origin", "http://evil.example"), "MCP-Protocol-Version", "1999-01-01"), JSON_BODY),
                new Row("declared body over the limit", 413, "POST", "/mcp", Map.of("Content-Type", "application/json", "Content-Length", Long.toString(LIMIT + 1L)), null),
                new Row("declared body over the limit on a hook", 413, "POST", "/hooks/v1/probe", Map.of("Content-Type", "application/json", "Content-Length", Long.toString(LIMIT + 1L)), null),
                new Row("chunked body over the limit", 413, "POST", "/mcp", Map.of("Content-Type", "application/json"), new byte[LIMIT + 1]));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejections")
    void aRejectedRequestGetsItsStatusAndReachesNothing(Row row) throws Exception {
        int reachedBefore = endpoints.reached.get();
        int toolsBefore = server.bean(ServerFixtures.Tools.class).calls.get();
        Map<String, String> headers = new java.util.LinkedHashMap<>(row.headers());
        headers.replaceAll((name, value) -> value.replace("{port}", Integer.toString(server.port)));
        RawHttp.Response response = RawHttp.exchange(server.port, row.method(), row.path(), headers, row.body());
        assertEquals(row.status(), response.status(), response.text());
        tools.jackson.databind.JsonNode problem = ToolServerSupport.MAPPER.readTree(response.text());
        assertEquals(row.status(), problem.path("status").asInt(), response.text());
        assertTrue(problem.path("code").asString().matches("[A-Z_]+"), response.text());
        assertEquals("urn:sennard:problem:" + problem.path("code").asString(), problem.path("type").asString(), response.text());
        assertTrue(response.headers().get("content-type").startsWith("application/problem+json"), response.headers().toString());
        assertEquals(reachedBefore, endpoints.reached.get(), "the handler behind the guard ran");
        assertEquals(toolsBefore, server.bean(ServerFixtures.Tools.class).calls.get(), "a tool ran");
    }

    // Controls: what the guard must not refuse.

    @Test
    void aWellFormedRequestPassesAndBothLoopbackNamesAreAccepted() throws Exception {
        int before = endpoints.reached.get();
        for (String host : List.of("127.0.0.1:" + server.port, "localhost:" + server.port)) {
            RawHttp.Response response = RawHttp.exchange(server.port, "POST", "/hooks/v1/probe", with(jsonHeaders(), "Host", host), JSON_BODY);
            assertEquals(200, response.status(), response.text());
        }
        assertEquals(before + 2, endpoints.reached.get());
    }

    @Test
    void aSupportedProtocolVersionPassesOnTheEndpointAndTheHeaderIsIgnoredElsewhere() throws Exception {
        byte[] initialize = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}").getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new java.util.LinkedHashMap<>(Map.of("Accept", "application/json, text/event-stream", "Content-Type", "application/json",
                "Content-Length", Integer.toString(initialize.length), "MCP-Protocol-Version", "2025-11-25"));
        assertEquals(200, RawHttp.exchange(server.port, "POST", "/mcp", headers, initialize).status());
        assertEquals(200, RawHttp.exchange(server.port, "POST", "/hooks/v1/probe", with(jsonHeaders(), "MCP-Protocol-Version", "1999-01-01"), JSON_BODY).status());
    }

    @Test
    void aBodyExactlyAtTheLimitPassesWholeBothDeclaredAndChunked() throws Exception {
        byte[] body = new byte[LIMIT];
        RawHttp.Response declared = RawHttp.exchange(server.port, "POST", "/hooks/v1/probe",
                Map.of("Content-Type", "application/json", "Content-Length", Integer.toString(LIMIT)), body);
        assertEquals(200, declared.status(), declared.text());
        assertEquals("length=" + LIMIT, declared.text());
        RawHttp.Response chunked = RawHttp.exchange(server.port, "POST", "/hooks/v1/probe", Map.of("Content-Type", "application/json"), body);
        assertEquals(200, chunked.status(), chunked.text());
        assertEquals("length=" + LIMIT, chunked.text());
    }

    @Test
    void aBodylessPostNeedsNoContentType() throws Exception {
        RawHttp.Response response = RawHttp.exchange(server.port, "POST", "/hooks/v1/probe", Map.of("Content-Length", "0"), null);
        assertEquals(200, response.status(), response.text());
    }

    /** The origin list was a setting of an edition that a browser could reach; here no setting makes an Origin acceptable, whatever key a start is given. */
    @Test
    void aStartThatNamesAnAllowedOriginStillRefusesEveryRequestWithAnOrigin() throws Exception {
        try (RunningServer named = RunningServer.start(List.of(ServerFixtures.Beans.class), "--sennard.guard.allowed-origins=http://evil.example,http://localhost:8080")) {
            for (String origin : List.of("http://evil.example", "http://localhost:8080")) {
                RawHttp.Response response = RawHttp.exchange(named.port, "POST", "/mcp", with(jsonHeaders(), "Origin", origin), JSON_BODY);
                assertEquals(403, response.status(), origin + ": " + response.text());
                assertEquals("REQUEST_ORIGIN_OR_HOST_NOT_ALLOWED", ToolServerSupport.MAPPER.readTree(response.text()).path("code").asString(), response.text());
            }
        }
    }

    @Test
    void aRejectionNamesAFixedReasonAndEchoesNoRequestValue() throws Exception {
        RawHttp.Response response = RawHttp.exchange(server.port, "POST", "/mcp", with(jsonHeaders(), "Origin", "http://evil.example"), JSON_BODY);
        tools.jackson.databind.JsonNode problem = ToolServerSupport.MAPPER.readTree(response.text());
        assertEquals("REQUEST_ORIGIN_OR_HOST_NOT_ALLOWED", problem.path("code").asString());
        assertEquals("The request was refused by the server's guard.", problem.path("detail").asString());
        assertTrue(problem.path("details").isObject() && problem.path("details").isEmpty(), "no request value is echoed: " + response.text());
        assertFalse(response.text().contains("evil.example"), response.text());
    }
}
