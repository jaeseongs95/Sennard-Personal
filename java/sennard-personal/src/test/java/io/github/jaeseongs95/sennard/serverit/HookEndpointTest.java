// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict.ContextBlock;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The hook entry point on a real Tomcat, behind the real guard. */
class HookEndpointTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static RunningServer server;
    private static HookTestSupport.ScriptedHostEventHandler handler;
    private static ServerFixtures.Endpoints endpoints;

    @BeforeAll
    static void start() throws Exception {
        server = RunningServer.start(List.of(ServerFixtures.Beans.class, HookTestSupport.HandlerBeans.class, HookTestSupport.FourthHostBeans.class));
        handler = server.bean(HookTestSupport.ScriptedHostEventHandler.class);
        endpoints = server.bean(ServerFixtures.Endpoints.class);
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @BeforeEach
    void reset() {
        handler.next.set(new HookVerdict.Proceed());
        handler.calls.clear();
    }

    private static RawHttp.Response post(int port, String path, byte[] body) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Content-Length", Integer.toString(body.length));
        return RawHttp.exchange(port, "POST", path, headers, body);
    }

    private static JsonNode json(RawHttp.Response response) {
        return MAPPER.readTree(response.body());
    }

    private void assertRejected(RawHttp.Response response, int status, String reason) {
        assertEquals(status, response.status(), response.text());
        assertEquals(reason, json(response).path("reason").stringValue());
        assertEquals(0, handler.calls.size(), "a rejected hook reached the domain");
    }

    @Test
    void aRecordedClaudeHookIsAnsweredInClaudesShape() throws IOException {
        handler.next.set(new HookVerdict.Refuse("no"));

        RawHttp.Response response = post(server.port, "/hooks/v1/claude/PreToolUse/governance", HookTestSupport.fixture("windows", "claude", "PreToolUse"));

        assertEquals(200, response.status(), "a refusal is an answer, not a failure");
        assertEquals("deny", json(response).path("hookSpecificOutput").path("permissionDecision").stringValue());
        assertEquals("no", json(response).path("hookSpecificOutput").path("permissionDecisionReason").stringValue());
        assertEquals("governance", handler.calls.peek().module());
        assertInstanceOf(HostEvent.ToolRequested.class, handler.calls.peek().event());
    }

    @Test
    void aRecordedCodexHookIsAnsweredInCodexsShape() throws IOException {
        RawHttp.Response response = post(server.port, "/hooks/v1/codex/PreToolUse", HookTestSupport.fixture("linux", "codex", "PreToolUse"));

        assertEquals(200, response.status(), response.text());
        assertEquals(MAPPER.readTree("{}"), json(response));
        assertNull(handler.calls.peek().module());
    }

    @Test
    void aRecordedAntigravityHookIsAnsweredWithTheAllowItRequires() throws IOException {
        RawHttp.Response response = post(server.port, "/hooks/v1/agy/PreToolUse", HookTestSupport.fixture("linux", "agy", "PreToolUse"));

        assertEquals(200, response.status(), response.text());
        assertEquals(MAPPER.readTree("{\"decision\":\"allow\"}"), json(response));
    }

    /** No product code knows this host. The controller serves it from the bean alone. */
    @Test
    void aHostThatOnlyExistsAsATestBeanIsServedWithoutTouchingCommonCode() throws IOException {
        RawHttp.Response response = post(server.port, "/hooks/v1/fourth-host-test/AnyEventName", "{\"run\":\"run-1\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals(200, response.status(), response.text());
        assertEquals("Proceed", json(response).path("fourth-host-answer").stringValue());
        assertEquals("run-1", handler.calls.peek().event().context().sessionId());
    }

    @Test
    void aLabelNoAdapterClaimsIsNotFound() throws IOException {
        assertRejected(post(server.port, "/hooks/v1/never-registered/SessionStart", "{}".getBytes(StandardCharsets.UTF_8)), 404, "HOOK_HOST_UNKNOWN");
    }

    /** Silence must never read as permission: the measured defect that let an empty body through as an allow. */
    @Test
    void anEmptyBodyIsRefusedNotAnsweredAsAnAllow() throws IOException {
        assertRejected(RawHttp.exchange(server.port, "POST", "/hooks/v1/agy/PreToolUse", Map.of("Content-Length", "0"), null), 400, "HOOK_BODY_EMPTY");
    }

    @Test
    void aBodyThatIsNotUtf8IsRefused() throws IOException {
        assertRejected(post(server.port, "/hooks/v1/claude/SessionStart", new byte[] {'{', '"', (byte) 0xC3, (byte) 0x28, '"', ':', '1', '}'}), 400, "HOOK_BODY_NOT_UTF8");
    }

    @Test
    void aBodyThatIsNotAJsonObjectIsRefused() throws IOException {
        assertRejected(post(server.port, "/hooks/v1/claude/SessionStart", "[1,2]".getBytes(StandardCharsets.UTF_8)), 400, "HOOK_BODY_NOT_A_JSON_OBJECT");
        assertRejected(post(server.port, "/hooks/v1/claude/SessionStart", "{not json".getBytes(StandardCharsets.UTF_8)), 400, "HOOK_BODY_NOT_A_JSON_OBJECT");
    }

    @Test
    void aMissingSessionIdIsRefusedAtTheBoundary() throws IOException {
        assertRejected(post(server.port, "/hooks/v1/agy/SessionStart", "{}".getBytes(StandardCharsets.UTF_8)), 400, "HOOK_SESSION_ID_MISSING");
    }

    @Test
    void anEventTheHostDoesNotHaveIsRefused() throws IOException {
        assertRejected(post(server.port, "/hooks/v1/agy/UserPromptSubmit", HookTestSupport.fixture("linux", "agy", "SessionStart")), 400, "HOOK_EVENT_UNKNOWN");
    }

    @Test
    void aVerdictTheEventCannotCarryIsAFailureAndNotARenderedAnswer() throws IOException {
        handler.next.set(new HookVerdict.Refuse("no"));

        RawHttp.Response response = post(server.port, "/hooks/v1/claude/SessionStart", HookTestSupport.fixture("linux", "claude", "SessionStart"));

        assertEquals(500, response.status(), response.text());
        assertEquals("HOOK_VERDICT_NOT_APPLICABLE", json(response).path("reason").stringValue());
    }

    @Test
    void nonAsciiTextPassesThroughAsUtf8BothWays() throws IOException {
        handler.next.set(new HookVerdict.AddContext(List.of(new ContextBlock("한글 😀 응답", false))));
        String body = "{\"session_id\":\"s\",\"prompt\":\"한글 😀 질문\"}";

        RawHttp.Response response = post(server.port, "/hooks/v1/claude/UserPromptSubmit", body.getBytes(StandardCharsets.UTF_8));

        assertEquals(200, response.status(), response.text());
        assertTrue(response.headers().get("content-type").contains("application/json"), response.headers().toString());
        assertEquals("한글 😀 응답", json(response).path("hookSpecificOutput").path("additionalContext").stringValue());
        assertEquals("한글 😀 질문", assertInstanceOf(HostEvent.UserPromptSubmitted.class, handler.calls.peek().event()).prompt());
    }

    @Test
    void theGuardStandsInFrontOfTheHookPath() throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        byte[] body = HookTestSupport.fixture("linux", "claude", "SessionStart");
        headers.put("Content-Type", "application/json");
        headers.put("Content-Length", Integer.toString(body.length));
        headers.put("Origin", "http://evil.example");

        RawHttp.Response response = RawHttp.exchange(server.port, "POST", "/hooks/v1/claude/SessionStart", headers, body);

        assertEquals(403, response.status(), response.text());
        assertEquals(0, handler.calls.size());
        assertEquals(0, endpoints.reached.get());
    }

    /** The store is still being prepared. A gate hook (agy PreToolUse) is answered with a 200 deny that carries the refusal code, never an allow. */
    @Test
    void whileTheStoreIsNotReadyTheGateHookIsDeniedNotAnAllow() throws Exception {
        try (RunningServer bare = RunningServer.start(List.of(ServerFixtures.Beans.class))) {
            RawHttp.Response response = post(bare.port, "/hooks/v1/agy/PreToolUse", HookTestSupport.fixture("linux", "agy", "PreToolUse"));
            assertEquals(200, response.status(), response.text());
            assertEquals("deny", json(response).path("decision").stringValue());
            assertTrue(json(response).path("reason").stringValue().contains("STORE_PREPARING"), response.text());
        }
    }

    /** In the same situation a hook that is not a gate still answers the failing status code. */
    @Test
    void whileTheStoreIsNotReadyAHookThatIsNotAGateAnswersUnavailable() throws Exception {
        try (RunningServer bare = RunningServer.start(List.of(ServerFixtures.Beans.class))) {
            RawHttp.Response response = post(bare.port, "/hooks/v1/agy/PostToolUse", HookTestSupport.fixture("linux", "agy", "PostToolUse"));
            assertEquals(503, response.status(), response.text());
            assertEquals("STORE_PREPARING", json(response).path("reason").stringValue());
        }
    }
}
