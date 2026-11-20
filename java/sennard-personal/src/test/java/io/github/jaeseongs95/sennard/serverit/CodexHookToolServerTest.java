// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.server.hook.HookRejection;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict.ContextBlock;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.host.McpHookRegistration;
import io.github.jaeseongs95.sennard.server.messaging.NativeWakeRelay;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Codex's {@code mcp_tool} hook on a real Tomcat through the SDK's own MCP client: the host calls the tool {@code codex_hook} with the fields its hook
 * definition names, and reads the tool's one text content as the hook's output. A host that finds a JSON string (or an object that holds the answer) there
 * ignores it, so what every case below checks first is that the text is the answer's JSON object itself.
 *
 * <p>Case table (target: the tool {@code codex_hook} of the assembled application; stand-in: only the domain's verdict, as in {@link HookEndpointTest}; input: the
 * fields of the recorded Codex hook bodies that the tool's published schema declares, as the host fills them from the event):
 * <ol>
 * <li>UserPromptSubmit, verdict AddContext: a JSON object with {@code hookSpecificOutput.additionalContext}; the handler read a UserPromptSubmitted</li>
 * <li>UserPromptSubmit, verdict Proceed: the JSON object {@code {}}</li>
 * <li>PreToolUse module continuity, Proceed: {@code {}}, and the handler got the module; Refuse("no"): {@code permissionDecision: deny} with the reason</li>
 * <li>PostToolUse (tool_response an object, the placeholders of the absent fields null): {@code {}}, a ToolFinished is read</li>
 * <li>Stop, verdict ContinueTurn: {@code {"decision":"block","reason":..}}; Stop, Proceed: {@code {}}</li>
 * <li>PreModelRequest and PostModelRequest, Proceed: {@code {}}; a context verdict there: an error result (HOOK_VERDICT_NOT_DELIVERABLE)</li>
 * <li>session_id absent (schema) and session_id blank (adapter): error result, the handler is not reached (HOOK_SESSION_ID_MISSING for the blank one)</li>
 * <li>an event Codex does not have: error result HOOK_EVENT_UNKNOWN; a verdict the event cannot carry: error result HOOK_VERDICT_NOT_APPLICABLE</li>
 * <li>an argument the schema does not declare: error result and the handler is not reached</li>
 * <li>tools/list: {@code codex_hook} once, {@code additionalProperties: false}, required event and session_id, a description that says it is not for a model</li>
 * </ol>
 */
class CodexHookToolServerTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String TOOL = "codex_hook";
    private static final String FOURTH_TOOL = "fourth_host_hook";

    private static RunningServer server;
    private static HookTestSupport.ScriptedHostEventHandler handler;
    /** The class's one initialized client (the host sends no label here): a client made for each case leaves its connections open on the server. */
    private static McpSyncClient client;

    @BeforeAll
    static void start() throws Exception {
        server = RunningServer.start(List.of(ServerFixtures.Beans.class, HookTestSupport.HandlerBeans.class, HookTestSupport.FourthHostBeans.class, FourthMcpRegistration.class));
        handler = server.bean(HookTestSupport.ScriptedHostEventHandler.class);
        client = DomainServerSupport.client(server.port, null);
    }

    @AfterAll
    static void stop() {
        try {
            client.close();
        } finally {
            server.close();
        }
    }

    @BeforeEach
    void reset() {
        handler.next.set(new HookVerdict.Proceed());
        handler.failure.set(null);
        handler.calls.clear();
    }

    @Configuration(proxyBeanMethods = false)
    static final class FourthMcpRegistration implements McpHookRegistration {
        @Override
        public HostId host() {
            return new HostId(HookTestSupport.FourthHostTestAdapter.LABEL);
        }

        @Override
        public McpSchema.Tool tool() {
            Map<String, Object> text = Map.of("type", "string");
            return McpSchema.Tool.builder(FOURTH_TOOL, Map.of("type", "object", "additionalProperties", false,
                    "properties", Map.of("event", text, "module", text, "run", text), "required", List.of("event", "run"))).build();
        }

        @Bean
        WakeTransport fourthWakeRelay(NativeWakeRelay relay) {
            return relay.transport(host());
        }
    }

    @Test
    void anotherRegisteredHookRunsThroughTheSameMcpEndpoint() {
        assertEquals(1, client.listTools().tools().stream().filter(tool -> tool.name().equals(FOURTH_TOOL)).count());
        Map<String, Object> input = Map.of("event", "SessionStart", "module", "continuity", "run", "fourth-mcp-session");
        JsonNode output = answer(client.callTool(McpSchema.CallToolRequest.builder(FOURTH_TOOL).arguments(input).build()));
        assertEquals("Proceed", output.path("fourth-host-answer").asString());
        var handled = handler.calls.getFirst();
        assertEquals("fourth-mcp-session", handled.event().context().sessionId());
        assertEquals(HookTestSupport.FourthHostTestAdapter.LABEL, handled.event().context().hostLabel().label());
        assertEquals("continuity", handled.module());
    }

    @Test
    void argumentsCannotRouteARegisteredHookToAnotherHost() {
        var result = client.callTool(McpSchema.CallToolRequest.builder(FOURTH_TOOL)
                .arguments(Map.of("event", "SessionStart", "run", "fourth-mcp-session", "host", "codex")).build());
        assertTrue(result.isError());
        assertTrue(handler.calls.isEmpty());
    }

    @Test
    void anotherHostRegistersItsOwnServerWakeTransport() {
        var relays = server.context.getBeansOfType(WakeTransport.class);
        var transport = relays.get("fourthWakeRelay");
        assertEquals(HookTestSupport.FourthHostTestAdapter.LABEL, transport.host().label());
        var ready = assertInstanceOf(WakeTransport.Availability.Ready.class,
                transport.connect(UUID.randomUUID().toString(), new WakeTransport.Target("fourth-server-session", null)));
        ready.connection().close();
    }

    /**
     * The gate of this host (the continuity PreToolUse): a call the server refused (here a lost first creation, 409 on the other transports) is answered with the host's
     * deny and the code, not with the error result a host goes on from. The hook that only waits (peer-wait) is no gate and keeps the error result.
     */
    @Test
    void aContinuityPreToolUseTheServerRefusedIsTheHostsDenyWithTheCode() {
        Map<String, Object> input = hostInput(published(client), "PreToolUse", "continuity", "PreToolUse");
        handler.failure.set(new HookRejected(HookRejection.HOOK_HANDLER_CONFLICT, "STORE_UNIQUE_VIOLATION", new IllegalStateException("lost")));

        JsonNode denied = answer(call(client, input));

        assertEquals("deny", denied.path("hookSpecificOutput").path("permissionDecision").asString());
        assertTrue(denied.path("hookSpecificOutput").path("permissionDecisionReason").asString()
                .startsWith("[Sennard] The Sennard server refused to check this tool call (reason HOOK_HANDLER_CONFLICT:STORE_UNIQUE_VIOLATION)"), denied.toString());
    }

    @Test
    void aPeerWaitPreToolUseTheServerRefusedStaysTheErrorResultSoTheHostGoesOn() {
        handler.failure.set(new HookRejected(HookRejection.HOOK_HANDLER_FAILED, "STORE_UNAVAILABLE", new IllegalStateException("down")));

        refusal(call(client, hostInput(published(client), "PreToolUse", "peer-wait", "PreToolUse")), "STORE_UNAVAILABLE");
    }

    private static McpSchema.Tool published(McpSyncClient client) {
        return client.listTools().tools().stream().filter(tool -> tool.name().equals(TOOL)).findFirst().orElseThrow(() -> new AssertionError(TOOL + " is not published"));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> declared(McpSchema.Tool tool) {
        return ((Map<String, Object>) tool.inputSchema().get("properties")).keySet();
    }

    /**
     * What Codex sends for an {@code mcp_tool} hook: the event (and module) its definition names plus the fields of the hook's event that the definition's
     * {@code input} lists. The recorded body of the event stands for the host's event; only the fields the tool declares are taken from it.
     */
    private static Map<String, Object> hostInput(McpSchema.Tool tool, String event, String module, String recordedEvent) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("event", event);
        if (module != null) {
            input.put("module", module);
        }
        Map<String, Object> recorded = MAPPER.readValue(HookTestSupport.fixture("linux", "codex", recordedEvent), new TypeReference<Map<String, Object>>() {
        });
        Set<String> names = declared(tool);
        recorded.forEach((name, value) -> {
            if (names.contains(name)) {
                input.put(name, value);
            }
        });
        return input;
    }

    private static Map<String, Object> bare(String event, String... fields) {
        Map<String, Object> input = new HashMap<>();
        input.put("event", event);
        input.put("session_id", HookTestSupport.SESSION);
        for (int i = 0; i < fields.length; i += 2) {
            input.put(fields[i], fields[i + 1]);
        }
        return input;
    }

    private static McpSchema.CallToolResult call(McpSyncClient client, Map<String, Object> arguments) {
        return client.callTool(McpSchema.CallToolRequest.builder(TOOL).arguments(arguments).build());
    }

    private static String text(McpSchema.CallToolResult result) {
        assertEquals(1, result.content().size(), "the answer is one content: " + result.content());
        return assertInstanceOf(McpSchema.TextContent.class, result.content().get(0)).text();
    }

    /** The hook's output: the text must be the answer's JSON object itself, never a JSON string that holds it. */
    private static JsonNode answer(McpSchema.CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()), () -> "an error result: " + result.content());
        String text = text(result);
        assertTrue(text.startsWith("{"), "the text is the object, not a quoted string: " + text);
        JsonNode node = MAPPER.readTree(text);
        assertTrue(node.isObject(), "one parse gives the object, not a string that holds it: " + text);
        return node;
    }

    private static JsonNode refusal(McpSchema.CallToolResult result, String reason) {
        assertTrue(Boolean.TRUE.equals(result.isError()), () -> "an answer where an error was expected: " + result.content());
        String text = text(result);
        if (reason != null) {
            assertEquals(reason, MAPPER.readTree(text).path("reason").asString(), text);
        }
        return MAPPER.readTree(text);
    }

    @Test
    void aContextVerdictIsTheJsonObjectInTheTextAndNotAStringHoldingIt() {
        handler.next.set(new HookVerdict.AddContext(List.of(new ContextBlock("Remember the rule.", false), new ContextBlock("Second block.", true))));
        JsonNode answer = answer(call(client, hostInput(published(client), "UserPromptSubmit", null, "UserPromptSubmit")));

        assertEquals("Remember the rule.\n\nSecond block.", answer.path("hookSpecificOutput").path("additionalContext").asString());
        assertEquals("UserPromptSubmit", answer.path("hookSpecificOutput").path("hookEventName").asString());
        assertInstanceOf(HostEvent.UserPromptSubmitted.class, handler.calls.peek().event());
        assertEquals(HookTestSupport.SESSION, handler.calls.peek().event().context().sessionId());
        assertEquals("codex", handler.calls.peek().event().context().hostLabel().label());
    }

    @Test
    void aProceedVerdictIsTheEmptyJsonObject() {
        JsonNode answer = answer(call(client, hostInput(published(client), "UserPromptSubmit", null, "UserPromptSubmit")));

        assertEquals(MAPPER.readTree("{}"), answer);
        assertEquals("{}", text(call(client, hostInput(published(client), "UserPromptSubmit", null, "UserPromptSubmit"))), "the text is exactly {}");
    }

    @Test
    void aContinuityPreToolUseIsAnsweredWithTheModuleAndADenyIsTheHostsDecisionObject() {
        Map<String, Object> input = hostInput(published(client), "PreToolUse", "continuity", "PreToolUse");

        assertEquals(MAPPER.readTree("{}"), answer(call(client, input)));
        assertEquals("continuity", handler.calls.peek().module());
        assertInstanceOf(HostEvent.ToolRequested.class, handler.calls.peek().event());

        handler.next.set(new HookVerdict.Refuse("no"));
        JsonNode denied = answer(call(client, input));
        assertEquals("deny", denied.path("hookSpecificOutput").path("permissionDecision").asString());
        assertEquals("no", denied.path("hookSpecificOutput").path("permissionDecisionReason").asString());
    }

    @Test
    void aPostToolUseWithAnObjectResponseAndNullPlaceholdersIsRead() {
        Map<String, Object> input = hostInput(published(client), "PostToolUse", null, "PostToolUse");
        input.put("agent_id", null); // the tool still takes a null field (the host's event may carry null, as transcript_path); a PostToolUse hook is a command now, not a tool call
        input.put("model", null);

        assertEquals(MAPPER.readTree("{}"), answer(call(client, input)));
        HostEvent.ToolFinished finished = assertInstanceOf(HostEvent.ToolFinished.class, handler.calls.peek().event());
        assertEquals("mcp__probe__probe_echo", finished.tool().name());
        assertTrue(finished.resultTail().contains("PROBE-OK"), finished.resultTail());
    }

    @Test
    void aStopThatContinuesTheTurnIsBlockWithTheReason() {
        Map<String, Object> input = hostInput(published(client), "Stop", null, "Stop");

        assertEquals(MAPPER.readTree("{}"), answer(call(client, input)));
        assertInstanceOf(HostEvent.TurnEnded.class, handler.calls.peek().event());

        handler.next.set(new HookVerdict.ContinueTurn("Check the open item first."));
        JsonNode answer = answer(call(client, input));
        assertEquals(MAPPER.readTree("{\"decision\":\"block\",\"reason\":\"Check the open item first.\"}"), answer);
    }

    @Test
    void theModelRequestEventsAreEmptyObjectsAndTakeNoContext() {
        assertEquals(MAPPER.readTree("{}"), answer(call(client, bare("PreModelRequest", "turn_id", "turn-1"))));
        assertInstanceOf(HostEvent.ModelCallStarting.class, handler.calls.peek().event());
        assertEquals(MAPPER.readTree("{}"), answer(call(client, bare("PostModelRequest", "turn_id", "turn-1", "status", "completed"))));
        assertInstanceOf(HostEvent.ModelCallFinished.class, List.copyOf(handler.calls).getLast().event());

        handler.next.set(new HookVerdict.AddContext(List.of(new ContextBlock("no place for this", false))));
        refusal(call(client, bare("PreModelRequest", "turn_id", "turn-1")), "HOOK_VERDICT_NOT_DELIVERABLE");
    }

    @Test
    void aCallWithoutASessionIsAnErrorResultSoTheHostGoesOn() {
        Map<String, Object> absent = bare("UserPromptSubmit");
        absent.remove("session_id");
        assertTrue(Boolean.TRUE.equals(call(client, absent).isError()), "the schema requires the session");

        Map<String, Object> nullSession = bare("UserPromptSubmit");
        nullSession.put("session_id", null);
        assertTrue(Boolean.TRUE.equals(call(client, nullSession).isError()), "a placeholder the host could not fill");

        refusal(call(client, bare("UserPromptSubmit", "session_id", "  ")), "HOOK_SESSION_ID_MISSING");
        assertEquals(0, handler.calls.size(), "a call with no session reached the domain");
    }

    @Test
    void anEventCodexDoesNotHaveIsAnErrorResult() {
        refusal(call(client, bare("NoSuchEvent")), "HOOK_EVENT_UNKNOWN");
        refusal(call(client, bare("BeforeAgent")), "HOOK_EVENT_UNKNOWN"); // another host's event
        assertEquals(0, handler.calls.size());
    }

    @Test
    void aVerdictTheEventCannotCarryIsAnErrorResultAndNotARenderedAnswer() {
        handler.next.set(new HookVerdict.Refuse("no"));
        refusal(call(client, bare("Stop")), "HOOK_VERDICT_NOT_APPLICABLE"); // a refusal ends no turn
    }

    @Test
    void anArgumentTheSchemaDoesNotDeclareIsRefusedAndTheHandlerIsNotReached() {
        Map<String, Object> input = bare("Stop");
        input.put("hook_event_name", "Stop");
        input.put("last_assistant_message", "not a field of the tool");

        McpSchema.CallToolResult result = call(client, input);

        assertTrue(Boolean.TRUE.equals(result.isError()), "an undeclared argument is an error");
        assertTrue(text(result).contains("hook_event_name") || text(result).contains("last_assistant_message"), text(result));
        assertEquals(0, handler.calls.size());
    }

    @Test
    void theToolIsListedWithAStrictSchemaAndADescriptionThatSaysItIsNotForAModel() {
        assertEquals(1, client.listTools().tools().stream().filter(tool -> tool.name().equals(TOOL)).count());
        McpSchema.Tool tool = published(client);

        assertEquals(Boolean.FALSE, tool.inputSchema().get("additionalProperties"), tool.inputSchema().toString());
        assertEquals(List.of("event", "session_id"), tool.inputSchema().get("required"));
        assertTrue(declared(tool).containsAll(List.of("event", "module", "session_id", "turn_id", "prompt", "tool_name", "tool_input", "tool_response", "stop_hook_active")), declared(tool).toString());
        assertTrue(tool.description().contains("not for a model to call"), tool.description());
        assertEquals(Boolean.FALSE, tool.annotations().readOnlyHint());
    }
}
