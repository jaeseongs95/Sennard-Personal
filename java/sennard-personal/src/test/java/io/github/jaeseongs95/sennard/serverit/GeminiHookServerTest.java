// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

import io.github.jaeseongs95.sennard.hookclient.HookHeaders;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict.ContextBlock;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gemini CLI's hooks on a real Tomcat: the Sennard launcher posts the hook's stdin JSON to {@code /hooks/v1/gemini/<Event>} and the body of the answer is what the
 * host reads on stdout. Case table (target: the assembled application; stand-in: only the domain's verdict, as in {@link HookEndpointTest}; input: the fields
 * of Gemini CLI's hook input, geminicli.com/docs/hooks/reference):
 * <ol>
 * <li>SessionStart, Proceed: {@code {}}; SessionStart with context: {@code hookSpecificOutput.additionalContext}; the start kind and host label reach the handler</li>
 * <li>BeforeTool with a refusal: {@code {"decision":"deny","reason":..}}; AfterAgent with a continuation: the same shape</li>
 * <li>SessionStart without a session id: 400 HOOK_SESSION_ID_MISSING; AfterModel and Notification: 400 HOOK_EVENT_UNKNOWN; none reaches the domain</li>
 * <li>a context verdict on BeforeModel: 500 HOOK_VERDICT_NOT_DELIVERABLE; a refusal at SessionStart: 500 HOOK_VERDICT_NOT_APPLICABLE</li>
 * <li>the host process id the launcher sends reaches the handler</li>
 * </ol>
 */
class GeminiHookServerTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static RunningServer server;
    private static HookTestSupport.ScriptedHostEventHandler handler;

    @BeforeAll
    static void start() throws Exception {
        server = RunningServer.start(List.of(ServerFixtures.Beans.class, HookTestSupport.HandlerBeans.class));
        handler = server.bean(HookTestSupport.ScriptedHostEventHandler.class);
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

    private static String input(String event, String fields) {
        return "{\"session_id\":\"" + HookTestSupport.SESSION + "\",\"transcript_path\":\"/tmp/chat.json\",\"cwd\":\"/work\",\"hook_event_name\":\"" + event
                + "\",\"timestamp\":\"2026-11-11T00:00:00.000Z\"" + (fields.isEmpty() ? "" : "," + fields) + "}";
    }

    private static RawHttp.Response post(String event, String body, Map<String, String> extraHeaders) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Content-Length", Integer.toString(bytes.length));
        headers.putAll(extraHeaders);
        return RawHttp.exchange(server.port, "POST", "/hooks/v1/gemini/" + event, headers, bytes);
    }

    private static RawHttp.Response post(String event, String body) throws IOException {
        return post(event, body, Map.of());
    }

    private static JsonNode json(RawHttp.Response response) {
        return MAPPER.readTree(response.body());
    }

    private void assertRejected(RawHttp.Response response, int status, String reason) {
        assertEquals(status, response.status(), response.text());
        assertEquals(reason, json(response).path("reason").asString());
        assertEquals(0, handler.calls.size(), "a rejected hook reached the domain");
    }

    @Test
    void aSessionStartIsAnsweredWithAnEmptyObjectOrTheContext() throws IOException {
        RawHttp.Response proceed = post("SessionStart", input("SessionStart", "\"source\":\"resume\""));

        assertEquals(200, proceed.status(), proceed.text());
        assertTrue(proceed.headers().get("content-type").contains("application/json"), proceed.headers().toString());
        assertEquals(MAPPER.readTree("{}"), json(proceed));
        HostEvent.SessionStarted started = assertInstanceOf(HostEvent.SessionStarted.class, handler.calls.peek().event());
        assertEquals(HostEvent.StartKind.RESUME, started.kind());
        assertEquals("gemini", started.context().hostLabel().label());
        assertEquals(HookTestSupport.SESSION, started.context().sessionId());
        assertEquals("/work", started.context().workspace());

        handler.next.set(new HookVerdict.AddContext(List.of(new ContextBlock("한글 context 😀", false))));
        RawHttp.Response context = post("SessionStart", input("SessionStart", "\"source\":\"startup\""));
        assertEquals(200, context.status(), context.text());
        assertEquals(MAPPER.readTree("{\"hookSpecificOutput\":{\"additionalContext\":\"한글 context 😀\"}}"), json(context));
    }

    @Test
    void aRefusedToolAndAContinuedTurnAreDenyObjects() throws IOException {
        handler.next.set(new HookVerdict.Refuse("not allowed"));
        RawHttp.Response refused = post("BeforeTool", input("BeforeTool", "\"tool_name\":\"run_shell_command\",\"tool_input\":{\"command\":\"rm -rf x\"}"));
        assertEquals(200, refused.status(), refused.text());
        assertEquals(MAPPER.readTree("{\"decision\":\"deny\",\"reason\":\"not allowed\"}"), json(refused));
        assertInstanceOf(HostEvent.ToolRequested.class, handler.calls.peek().event());

        handler.next.set(new HookVerdict.ContinueTurn("Check the open item first."));
        RawHttp.Response continued = post("AfterAgent", input("AfterAgent", "\"prompt\":\"hi\",\"prompt_response\":\"done\",\"stop_hook_active\":false"));
        assertEquals(200, continued.status(), continued.text());
        assertEquals(MAPPER.readTree("{\"decision\":\"deny\",\"reason\":\"Check the open item first.\"}"), json(continued));
        assertEquals(false, assertInstanceOf(HostEvent.TurnEnded.class, List.copyOf(handler.calls).getLast().event()).alreadyContinued());
    }

    @Test
    void aCallWithoutASessionIdIsRefused() throws IOException {
        assertRejected(post("SessionStart", "{\"hook_event_name\":\"SessionStart\",\"source\":\"startup\"}"), 400, "HOOK_SESSION_ID_MISSING");
        assertRejected(post("SessionStart", "{}"), 400, "HOOK_SESSION_ID_MISSING");
    }

    @Test
    void anEventTheAdapterDoesNotTakeIsRefused() throws IOException {
        assertRejected(post("AfterModel", input("AfterModel", "\"llm_response\":{}")), 400, "HOOK_EVENT_UNKNOWN");
        assertRejected(post("Notification", input("Notification", "\"notification_type\":\"ToolPermission\"")), 400, "HOOK_EVENT_UNKNOWN");
    }

    @Test
    void aVerdictTheHostHasNoPlaceForIsAFailureAndNotARenderedAnswer() throws IOException {
        handler.next.set(new HookVerdict.AddContext(List.of(new ContextBlock("no place for this", false))));
        RawHttp.Response notDeliverable = post("BeforeModel", input("BeforeModel", "\"llm_request\":{\"model\":\"gemini-3-pro\"}"));
        assertEquals(500, notDeliverable.status(), notDeliverable.text());
        assertEquals("HOOK_VERDICT_NOT_DELIVERABLE", json(notDeliverable).path("reason").asString());
        assertEquals("gemini-3-pro", handler.calls.peek().event().context().model());

        handler.next.set(new HookVerdict.Refuse("no"));
        RawHttp.Response notApplicable = post("SessionStart", input("SessionStart", "\"source\":\"startup\""));
        assertEquals(500, notApplicable.status(), notApplicable.text());
        assertEquals("HOOK_VERDICT_NOT_APPLICABLE", json(notApplicable).path("reason").asString());
    }

    @Test
    void theProcessesTheLauncherNamesReachTheHandlerNearestFirst() throws IOException {
        post("SessionStart", input("SessionStart", "\"source\":\"startup\""), Map.of(HookHeaders.HOST_PIDS,"4242,4243,4244"));

        assertEquals(List.of(4242L, 4243L, 4244L), handler.calls.peek().hostCandidates());
    }
}
