// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.context.support.StaticMessageSource;

import io.github.jaeseongs95.sennard.hookclient.HookClientCall;
import io.github.jaeseongs95.sennard.server.hook.HookBody;
import io.github.jaeseongs95.sennard.server.hook.HookInstallation;
import io.github.jaeseongs95.sennard.server.hook.HookPipeline;
import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict.ContextBlock;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostEventHandler;
import io.github.jaeseongs95.sennard.server.hook.HostRegistrationFile;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.serverit.HookAdapters;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gemini CLI's adapter called directly (geminicli.com/docs/hooks/reference). Case table (target: {@link GeminiHookAdapter#read}, {@code respond} and the
 * definition it generates; input: the JSON the host writes on stdin, built from the host's field names; expected: the server's own event and the host's JSON
 * answer; judged by comparing the records and JSON trees):
 * <ol>
 * <li>SessionStart source startup/resume/clear to the start kind, any other or missing source to OTHER; the context carries session, cwd and transcript</li>
 * <li>SessionEnd reason; BeforeAgent prompt to UserPromptSubmitted; AfterAgent stop_hook_active true/false/absent to alreadyContinued</li>
 * <li>BeforeModel: llm_request.model is the context's model, and no other event names one</li>
 * <li>BeforeTool: tool name and input, no call id; an MCP tool of the Sennard server by mcp_context.server_name or by the host's name prefix is an sennardTool,
 * another server's tool and a bare prefix are not</li>
 * <li>AfterTool: the tail of tool_response.llmContent, an error as reportedFailure with the error as the result, a long result kept by its tail</li>
 * <li>PreCompress to CompactionStarting</li>
 * <li>no session id (absent, blank) on each of the eight events: 400 HOOK_SESSION_ID_MISSING</li>
 * <li>AfterModel, BeforeToolSelection, Notification and a misspelt name: 400 HOOK_EVENT_UNKNOWN, on read and on respond</li>
 * <li>respond: Proceed is {@code {}} on every event; AddContext is {@code hookSpecificOutput.additionalContext} on SessionStart, BeforeAgent and AfterTool only and
 * a 500 HOOK_VERDICT_NOT_DELIVERABLE elsewhere; ContinueTurn is {@code decision: deny} with the reason on AfterAgent only; Refuse is the same on BeforeTool and
 * BeforeAgent only</li>
 * <li>the definition: the eight events, each a command of the Sennard hook client (no port in it: the server's is the operating system's choice at each start) with the host's matcher,
 * the session start binding the host and answering with the Gemini guidance when the server is down</li>
 * <li>the definition for a program of several words (the user's java, its options, the client's jar and class, the installation's options): one command string per event, the first word
 * in quotes for its space, each later word in quotes only for a space, then the hook's arguments</li>
 * <li>the MCP registration: the relay as a stdio server, as the {@code /mcpServers} member of the extension's manifest and nothing else of it; the package ships the hook definition only</li>
 * </ol>
 */
class GeminiHookAdapterTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final GeminiHookAdapter ADAPTER = HookAdapters.gemini();
    private static final List<String> EVENTS = List.of("SessionStart", "SessionEnd", "BeforeAgent", "AfterAgent", "BeforeModel", "BeforeTool", "AfterTool", "PreCompress");
    private static final Set<String> TAKE_CONTEXT = Set.of("SessionStart", "BeforeAgent", "AfterTool");

    /** The stdin of a hook: the fields every event has, then the event's own. */
    private static HookBody input(String event, String fields) {
        return body("{\"session_id\":\"gem-1\",\"transcript_path\":\"/tmp/gem-1.json\",\"cwd\":\"/work\",\"hook_event_name\":\"" + event
                + "\",\"timestamp\":\"2026-11-11T00:00:00.000Z\"" + (fields.isEmpty() ? "" : "," + fields) + "}");
    }

    private static HookBody body(String json) {
        return HookBody.posted(json.getBytes(StandardCharsets.UTF_8));
    }

    /** What the adapter answered, as the JSON tree a host reads. */
    private static JsonNode answered(String event, HostEvent read, HookVerdict verdict) {
        return MAPPER.valueToTree(ADAPTER.respond(event, read, verdict));
    }

    private static HostEvent read(String event, String fields) {
        return ADAPTER.read(event, input(event, fields));
    }

    @Test
    void aNativeAgentResultIsDeniedBeforeOrdinaryConsumersSeeIt() {
        String fields = """
                "tool_name":"invoke_agent",
                "tool_input":{"agent_name":"fixture_worker","prompt":"fixture question"},
                "tool_response":{"llmContent":[{"text":"fixture native result"}],
                                 "returnDisplay":{"isSubagentProgress":true,"agentName":"fixture_worker","state":"completed","result":"fixture native result"}}
                """;
        AtomicInteger handled = new AtomicInteger();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("handler", (HostEventHandler) call -> {
            handled.incrementAndGet();
            return new HookVerdict.AddContext(List.of(new ContextBlock("pending peer message", false)));
        });
        StaticMessageSource source = new StaticMessageSource();
        source.addMessage("peer.result.useSennard", Locale.ENGLISH, "Use the Sennard peer inbox.");
        HookPipeline pipeline = new HookPipeline(List.of(ADAPTER), beans.getBeanProvider(HostEventHandler.class), new Messages(new MessageSourceAccessor(source)));

        JsonNode result = MAPPER.valueToTree(pipeline.answer("gemini", "AfterTool", null, input("AfterTool", fields), List.of()));

        assertEquals(MAPPER.readTree("{\"decision\":\"deny\",\"reason\":\"Use the Sennard peer inbox.\"}"), result);
        assertEquals(0, handled.get(), "the ordinary path may consume a pending peer message");
    }

    @Test
    void similarlyNamedAndMcpToolsKeepTheirOrdinaryResult() {
        String outcome = ",\"tool_response\":{\"llmContent\":\"ordinary result\"}";
        for (String fields : List.of("\"tool_name\":\"invoke_agent_report\"", """
                "tool_name":"invoke_agent",
                "mcp_context":{"server_name":"foreign","tool_name":"invoke_agent"}
                """)) {
            HostEvent.ToolFinished finished = assertInstanceOf(HostEvent.ToolFinished.class, read("AfterTool", fields + outcome));
            assertEquals("ordinary result", finished.resultTail());
            assertTrue(finished.stepBoundary());
        }
    }

    private static HookRejected rejected(Runnable call) {
        return assertThrows(HookRejected.class, call::run);
    }

    @ParameterizedTest
    @CsvSource({"startup,STARTUP", "resume,RESUME", "clear,CLEAR", "compact,OTHER", "fork,OTHER", "somethingNew,OTHER"})
    void aSessionStartReadsItsSourceAsTheStartKind(String source, HostEvent.StartKind kind) {
        HostEvent.SessionStarted started = assertInstanceOf(HostEvent.SessionStarted.class, read("SessionStart", "\"source\":\"" + source + "\""));

        assertEquals(kind, started.kind());
    }

    @Test
    void aSessionStartWithoutASourceIsOtherAndCarriesTheContext() {
        HostEvent.SessionStarted started = assertInstanceOf(HostEvent.SessionStarted.class, read("SessionStart", ""));

        assertEquals(HostEvent.StartKind.OTHER, started.kind());
        HostContext context = started.context();
        assertEquals("gemini", context.hostLabel().label());
        assertEquals("gem-1", context.sessionId());
        assertEquals("/work", context.workspace());
        assertEquals("/tmp/gem-1.json", context.transcriptPath());
        assertNull(context.model());
        assertNull(context.effort());
        assertNull(context.turnId());
        assertEquals(HostContext.Role.ROOT, context.actor().role());
        assertEquals(HostContext.Basis.INFERRED, context.actor().basis());
    }

    @Test
    void aSessionEndReadsItsReason() {
        assertEquals("exit", assertInstanceOf(HostEvent.SessionEnded.class, read("SessionEnd", "\"reason\":\"exit\"")).reason());
    }

    @Test
    void aBeforeAgentIsTheUserPrompt() {
        assertEquals("한글 prompt \"quoted\"", assertInstanceOf(HostEvent.UserPromptSubmitted.class, read("BeforeAgent", "\"prompt\":\"한글 prompt \\\"quoted\\\"\"")).prompt());
    }

    @ParameterizedTest
    @CsvSource({"'\"stop_hook_active\":true',true", "'\"stop_hook_active\":false',false", "'',false", "'\"stop_hook_active\":\"yes\"',false"})
    void anAfterAgentReadsStopHookActiveAsAlreadyContinued(String fields, boolean continued) {
        assertEquals(continued, assertInstanceOf(HostEvent.TurnEnded.class, read("AfterAgent", fields)).alreadyContinued());
    }

    @Test
    void aBeforeModelNamesItsModelAndNoOtherEventDoes() {
        HostEvent.ModelCallStarting starting = assertInstanceOf(HostEvent.ModelCallStarting.class,
                read("BeforeModel", "\"llm_request\":{\"model\":\"gemini-3-pro\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"));
        assertEquals("gemini-3-pro", starting.context().model());

        assertNull(read("BeforeModel", "").context().model(), "no llm_request");
        assertNull(read("BeforeModel", "\"llm_request\":{\"model\":\"  \"}").context().model(), "a blank model is none");
        assertNull(read("BeforeTool", "\"tool_name\":\"x\",\"llm_request\":{\"model\":\"gemini-3-pro\"}").context().model(), "only a model request names its model");
    }

    @Test
    void aBeforeToolReadsTheToolNameInputAndNoCallId() {
        HostEvent.ToolRequested requested = assertInstanceOf(HostEvent.ToolRequested.class,
                read("BeforeTool", "\"tool_name\":\"run_shell_command\",\"tool_input\":{\"command\":\"ls -la\"}"));

        assertEquals("run_shell_command", requested.tool().name());
        assertEquals(MAPPER.readTree("{\"command\":\"ls -la\"}"), requested.tool().input());
        assertNull(requested.tool().callId());
        assertNull(requested.tool().sennardTool());
    }

    static Stream<Arguments> sennardToolCases() {
        return Stream.of(
                // by the server the host names in mcp_context
                Arguments.of("\"tool_name\":\"mcp_sennard_send_session_message\",\"mcp_context\":{\"server_name\":\"sennard\",\"tool_name\":\"send_session_message\"}", "send_session_message"),
                Arguments.of("\"tool_name\":\"renamed\",\"mcp_context\":{\"server_name\":\"sennard\",\"tool_name\":\"list_session_status\"}", "list_session_status"),
                Arguments.of("\"tool_name\":\"renamed\",\"mcp_context\":{\"server_name\":\"sennard\",\"tool_name\":\"list_session_status\"}", "list_session_status"),
                // by the host's own naming of the tool
                Arguments.of("\"tool_name\":\"mcp_sennard_update_session_status\"", "update_session_status"),
                Arguments.of("\"tool_name\":\"mcp_sennard_update_session_status\"", "update_session_status"),
                // the server is named but the context has no tool: the host's name decides
                Arguments.of("\"tool_name\":\"mcp_sennard_load_context\",\"mcp_context\":{\"server_name\":\"sennard\"}", "load_context"),
                // not a Sennard tool
                Arguments.of("\"tool_name\":\"mcp_github_create_issue\",\"mcp_context\":{\"server_name\":\"github\",\"tool_name\":\"create_issue\"}", null),
                Arguments.of("\"tool_name\":\"read_file\"", null),
                Arguments.of("\"tool_name\":\"mcp_sennard_\"", null),
                Arguments.of("\"tool_name\":\"mcp_other-server_list_session_status\"", null));
    }

    @ParameterizedTest
    @MethodSource("sennardToolCases")
    void anMcpToolOfTheSennardServerIsRecognizedByTheServerOrByTheHostsNaming(String fields, String expectedSennardTool) {
        HostEvent.ToolRequested requested = assertInstanceOf(HostEvent.ToolRequested.class, read("BeforeTool", fields));

        assertEquals(expectedSennardTool, requested.tool().sennardTool(), fields);
    }

    @Test
    void anAfterToolReadsTheTailOfTheLlmContent() {
        HostEvent.ToolFinished finished = assertInstanceOf(HostEvent.ToolFinished.class,
                read("AfterTool", "\"tool_name\":\"run_shell_command\",\"tool_input\":{\"command\":\"ls\"},\"tool_response\":{\"llmContent\":\"file-a\\nfile-b\",\"returnDisplay\":\"2 files\"}"));

        assertEquals("file-a\nfile-b", finished.resultTail());
        assertFalse(finished.reportedFailure());
        assertTrue(finished.stepBoundary(), "text after a tool reaches the model's next call");
        assertEquals("run_shell_command", finished.tool().name());
    }

    @Test
    void anAfterToolWithAnErrorIsAReportedFailureAndTheErrorIsTheResult() {
        HostEvent.ToolFinished failed = assertInstanceOf(HostEvent.ToolFinished.class,
                read("AfterTool", "\"tool_name\":\"run_shell_command\",\"tool_response\":{\"llmContent\":\"partial\",\"error\":\"exit code 2\"}"));
        assertTrue(failed.reportedFailure());
        assertEquals("exit code 2", failed.resultTail());

        HostEvent.ToolFinished objectError = assertInstanceOf(HostEvent.ToolFinished.class,
                read("AfterTool", "\"tool_name\":\"x\",\"tool_response\":{\"error\":{\"message\":\"denied\"}}"));
        assertTrue(objectError.reportedFailure());
        assertEquals("{\"message\":\"denied\"}", objectError.resultTail());

        HostEvent.ToolFinished nullError = assertInstanceOf(HostEvent.ToolFinished.class,
                read("AfterTool", "\"tool_name\":\"x\",\"tool_response\":{\"llmContent\":\"fine\",\"error\":null}"));
        assertFalse(nullError.reportedFailure(), "a null error is no error");
        assertEquals("fine", nullError.resultTail());
    }

    @Test
    void anAfterToolWithNoContentHasNoResultAndALongOneIsKeptByItsTail() {
        HostEvent.ToolFinished none = assertInstanceOf(HostEvent.ToolFinished.class, read("AfterTool", "\"tool_name\":\"x\",\"tool_response\":{}"));
        assertNull(none.resultTail());
        assertFalse(none.reportedFailure());

        String longText = "a".repeat(70_000) + "THE-END";
        HostEvent.ToolFinished kept = assertInstanceOf(HostEvent.ToolFinished.class,
                read("AfterTool", "\"tool_name\":\"x\",\"tool_response\":{\"llmContent\":\"" + longText + "\"}"));
        String tail = kept.resultTail();
        assertEquals(65_536, tail.length());
        assertTrue(tail.endsWith("THE-END"));
    }

    @Test
    void aPreCompressIsACompactionStarting() {
        assertInstanceOf(HostEvent.CompactionStarting.class, read("PreCompress", "\"trigger\":\"auto\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SessionStart", "SessionEnd", "BeforeAgent", "AfterAgent", "BeforeModel", "BeforeTool", "AfterTool", "PreCompress"})
    void anEventWithoutASessionIdIsRefused(String event) {
        HookRejected absent = rejected(() -> ADAPTER.read(event, body("{\"cwd\":\"/work\",\"hook_event_name\":\"" + event + "\"}")));
        assertEquals(400, absent.status(), event);
        assertEquals("HOOK_SESSION_ID_MISSING", absent.reason(), event);

        HookRejected blank = rejected(() -> ADAPTER.read(event, body("{\"session_id\":\"  \",\"hook_event_name\":\"" + event + "\"}")));
        assertEquals("HOOK_SESSION_ID_MISSING", blank.reason(), event);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AfterModel", "BeforeToolSelection", "Notification", "sessionstart", "SessionStart ", "PreToolUse", "Stop", ""})
    void anEventTheAdapterDoesNotTakeIsUnknownOnReadAndOnRespond(String event) {
        HookRejected onRead = rejected(() -> ADAPTER.read(event, input(event, "")));
        assertEquals(400, onRead.status(), event);
        assertEquals("HOOK_EVENT_UNKNOWN", onRead.reason(), event);

        HostEvent anyEvent = read("SessionStart", "");
        HookRejected respond = rejected(() -> ADAPTER.respond(event, anyEvent, new HookVerdict.Proceed()));
        assertEquals(400, respond.status(), event);
        assertEquals("HOOK_EVENT_UNKNOWN", respond.reason(), event);
    }

    @Test
    void proceedIsAnEmptyObjectOnEveryEvent() {
        for (String event : EVENTS) {
            assertEquals(MAPPER.readTree("{}"), answered(event, read(event, ""), new HookVerdict.Proceed()), event);
        }
    }

    @Test
    void contextIsAdditionalContextWhereTheHostTakesTextAndRefusedElsewhere() {
        HookVerdict.AddContext one = new HookVerdict.AddContext(List.of(new ContextBlock("remember this", false)));
        HookVerdict.AddContext two = new HookVerdict.AddContext(List.of(new ContextBlock("first", false), new ContextBlock("second", true)));
        for (String event : EVENTS) {
            HostEvent hostEvent = read(event, "");
            if (TAKE_CONTEXT.contains(event)) {
                assertEquals(MAPPER.readTree("{\"hookSpecificOutput\":{\"additionalContext\":\"remember this\"}}"), answered(event, hostEvent, one), event);
                assertEquals(MAPPER.readTree("{\"hookSpecificOutput\":{\"additionalContext\":\"first\\n\\nsecond\"}}"), answered(event, hostEvent, two), event);
            } else {
                HookRejected refused = rejected(() -> ADAPTER.respond(event, hostEvent, one));
                assertEquals(500, refused.status(), event);
                assertEquals("HOOK_VERDICT_NOT_DELIVERABLE", refused.reason(), event);
            }
        }
    }

    @Test
    void continuingATurnIsADenyOfTheAgentLoopsEndAndOnlyAfterAgentHasOne() {
        HookVerdict.ContinueTurn continuation = new HookVerdict.ContinueTurn("Check the open item first.");
        for (String event : EVENTS) {
            HostEvent hostEvent = read(event, "");
            if (event.equals("AfterAgent")) {
                JsonNode output = answered(event, hostEvent, continuation);
                assertEquals("deny", output.path("decision").asString(), event);
                assertTrue(output.path("reason").asString().matches("Check the open item first\\.\\n\\n\\[Sennard retry: [0-9a-f-]{36}\\]"), output.toString());
            } else {
                HookRejected refused = rejected(() -> ADAPTER.respond(event, hostEvent, continuation));
                assertEquals(500, refused.status(), event);
                assertEquals("HOOK_VERDICT_NOT_DELIVERABLE", refused.reason(), event);
            }
        }
    }

    @Test
    void anExhaustedAfterAgentStopsWithAnExplicitReason() {
        JsonNode output = answered("AfterAgent", read("AfterAgent", ""), new HookVerdict.TerminateTurn("No procedure was selected."));
        assertEquals(MAPPER.readTree("{\"continue\":false,\"reason\":\"No procedure was selected.\"}"), output);
    }

    @Test
    void anOrdinaryEventAcceptsRefusalAtBeforeToolOrBeforeAgent() {
        HookVerdict.Refuse refusal = new HookVerdict.Refuse("not allowed");
        for (String event : EVENTS) {
            HostEvent hostEvent = read(event, "");
            if (event.equals("BeforeTool") || event.equals("BeforeAgent")) {
                assertEquals(MAPPER.readTree("{\"decision\":\"deny\",\"reason\":\"not allowed\"}"), answered(event, hostEvent, refusal), event);
            } else {
                HookRejected refused = rejected(() -> ADAPTER.respond(event, hostEvent, refusal));
                assertEquals(500, refused.status(), event);
                assertEquals("HOOK_VERDICT_NOT_DELIVERABLE", refused.reason(), event);
            }
        }
    }

    /** The extension's hook definition of an installation: its first file. */
    private static JsonNode hooksOf(HookInstallation installation) {
        HostRegistrationFile definition = ADAPTER.registrationFiles(installation).get(0);
        assertEquals(Path.of("hooks", "hooks.json"), definition.relativePath());
        return definition.content().path("hooks");
    }

    @Test
    void theDefinitionUsesHookClientCommandsAndOnlyTheSessionStartBindsTheHost() {
        List<String> program = List.of("C:/Program Files/Java/bin/java.exe", "-cp", "client.jar", "HookClientMain");
        List<String> relay = List.of("relay");
        JsonNode hooks = hooksOf(new HookInstallation(program, relay, HookInstallation.Platform.POSIX));

        assertEquals(EVENTS.stream().sorted().toList(), hooks.propertyNames().stream().sorted().toList());
        for (String event : EVENTS) {
            JsonNode entry = hooks.path(event).get(0);
            assertEquals(event.equals("AfterTool") ? "^(?!invoke_agent$).*$" : "*", entry.path("matcher").asString(), event);
            JsonNode handler = entry.path("hooks").get(0);
            assertEquals("command", handler.path("type").asString(), event);
            assertEquals("sennard-" + event, handler.path("name").asString(), event);
            assertEquals(event.equals("SessionStart") ? HookClientCall.SESSION_START_LIMIT_SECONDS * 1000 : 10_000, handler.path("timeout").asInt(),
                    "the host's timeout is in milliseconds: " + event);
            String command = handler.path("command").asString();
            assertTrue(command.startsWith("\"C:/Program Files/Java/bin/java.exe\" -cp client.jar HookClientMain hook gemini " + event), command);
            assertFalse(command.contains("--port"), "no port: the server's is the operating system's choice at each start: " + command);
            assertEquals(event.equals("SessionStart"), command.contains("--bind-host"), command);
            assertEquals(event.equals("SessionStart"), command.contains("--start-server="), "only the session start may start the server: " + command);
            assertEquals(event.equals("SessionStart"), command.contains("--on-failure=guidance-system-message"), command);
            assertFalse(command.contains("--wake"), command);
        }
        JsonNode nativeResult = hooks.path("AfterTool").get(1);
        assertEquals("^invoke_agent$", nativeResult.path("matcher").asString());
        assertEquals("\"C:/Program Files/Java/bin/java.exe\" -cp client.jar HookClientMain hook gemini AfterTool --on-failure=deny-result",
                nativeResult.path("hooks").get(0).path("command").asString());

        JsonNode windows = hooksOf(new HookInstallation(program, relay, HookInstallation.Platform.WINDOWS));
        for (String event : EVENTS) {
            assertEquals(hooks.path(event).get(0), windows.path(event).get(0), event);
        }
        JsonNode windowsResult = windows.path("AfterTool").get(1);
        assertEquals(nativeResult.path("matcher"), windowsResult.path("matcher"));
        String command = windowsResult.path("hooks").get(0).path("command").asString();
        Base64.Encoder base64 = Base64.getEncoder();
        String arguments = "\"-cp\" \"client.jar\" \"HookClientMain\" \"hook\" \"gemini\" \"AfterTool\" \"--on-failure=deny-result\"";
        assertTrue(command.contains("[Diagnostics.Process]::Start($start)"), command);
        assertTrue(command.contains(base64.encodeToString(program.getFirst().getBytes(StandardCharsets.UTF_8))), command);
        assertTrue(command.contains(base64.encodeToString(arguments.getBytes(StandardCharsets.UTF_8))), command);
    }

    @Test
    void aProgramOfSeveralWordsIsOneCommandStringWithTheFirstWordAndOnlyTheWordsThatNeedItQuoted() {
        HookInstallation installation = new HookInstallation(List.of("C:/Program Files/Java/bin/java.exe", "-XX:+UseSerialGC", "-XX:AOTCache=C:/a b/sennard-hook.aot", "-cp",
                "C:/Program Files/Sennard/lib/sennard-hook-client-6.0.0.jar", "io.github.jaeseongs95.sennard.hookclient.HookClientMain", "--server-file=C:/Users/Some One/.sennard/server.properties",
                "--jar=C:/Program Files/Sennard/sennard-6.0.0.jar"), List.of("relay"));
        JsonNode hooks = hooksOf(installation);

        for (String event : EVENTS) {
            String command = hooks.path(event).get(0).path("hooks").get(0).path("command").asString();
            assertTrue(command.startsWith("\"C:/Program Files/Java/bin/java.exe\" -XX:+UseSerialGC \"-XX:AOTCache=C:/a b/sennard-hook.aot\" -cp \"C:/Program Files/Sennard/lib/sennard-hook-client-6.0.0.jar\" "
                    + "io.github.jaeseongs95.sennard.hookclient.HookClientMain \"--server-file=C:/Users/Some One/.sennard/server.properties\" \"--jar=C:/Program Files/Sennard/sennard-6.0.0.jar\" hook gemini "
                    + event), command);
        }
    }

    /** The manifest holds the extension's name and version as well, so Sennard writes the {@code mcpServers} member only: the relay as a stdio server, under the label of this host. */
    @Test
    void theMcpRegistrationIsTheMcpServersMemberOfTheManifestOnly() {
        HostRegistrationFile registration = ADAPTER.registrationFiles(new HookInstallation(List.of("hook"), List.of("/jdk/bin/java", "-cp", "client.jar", "McpRelayMain"))).get(1);

        HostRegistrationFile.Member member = assertInstanceOf(HostRegistrationFile.Member.class, registration);
        assertEquals(Path.of("gemini-extension.json"), member.relativePath());
        assertEquals("/mcpServers", member.jsonPointer());
        assertEquals(MAPPER.readTree("{\"sennard\":{\"command\":\"/jdk/bin/java\",\"args\":[\"-cp\",\"client.jar\",\"McpRelayMain\",\"--host=gemini\",\"--start-server="
                + HookClientCall.START_SERVER_SECONDS + "\"]}}"), member.content());
        assertEquals(List.of(Path.of("hooks", "hooks.json")), ADAPTER.shippedFiles().stream().map(HostRegistrationFile::relativePath).toList(), "the manifest has no such member in the package");
    }
}
