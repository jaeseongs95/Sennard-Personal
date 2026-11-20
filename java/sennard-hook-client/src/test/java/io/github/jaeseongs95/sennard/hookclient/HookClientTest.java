// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.hookclient.HookClientCall.FailureAnswer;
import io.github.jaeseongs95.sennard.hookclient.HookNoAnswer.Reason;
import io.github.jaeseongs95.sennard.hookclient.HookOptionException.Problem;
import io.github.jaeseongs95.sennard.hookclient.HookServerStub.Received;
import io.github.jaeseongs95.sennard.hookclient.WhatTheHostSees.Result;

/**
 * The hook client ({@link HookClient}), the real class, against a server stand-in on a real loopback socket (the JDK's own {@code com.sun.net.httpserver.HttpServer},
 * {@link HookServerStub}) and, for the host process, against real Java processes ({@link HookClientChainProbe}, {@link HookClientChainRelay}). Nothing here is timed: a case that
 * waits for the server is a stand-in that does not answer (a read timeout of one second ends it) and the result says whether the timeout applied. The client's reasons for no answer are
 * its codes: stderr of a failed hook is {@code Sennard hook failed: <Reason name>[ <detail>]}. Case table (input: a {@link HookClientCall}, the environment, stdin; expected: what the host
 * sees, the exit code, stdout and stderr, and what the server received):
 * <ol>
 * <li>a 2xx answer: its bytes (UTF-8 text included) on stdout unchanged, exit 0, nothing on stderr</li>
 * <li>the request: POST to {@code /hooks/v1/<label>/<event>[/<module>]}, the body is stdin byte for byte when stdin is only what the server reads (300 KB too),
 * {@code Content-Type: application/json}, no {@code X-Sennard-Host-Pids} unless the call binds the host</li>
 * <li>what is sent of a host's input, the exact text of the body ({@link InputReducer}: the fields the server reads, as the host wrote them, in the order of the input, and nothing
 * else): Gemini {@code BeforeModel} with 4 MB of {@code llm_request.messages} and a {@code config} (the model and the common fields only; stdin and sent size reported); Gemini
 * {@code AfterTool} (arguments, MCP context, 100 KB result kept, {@code returnDisplay} dropped); Claude {@code PreToolUse} (300 KB {@code tool_input} and agent marker kept, unknown
 * fields dropped) and {@code SessionStart} ({@code effort.level} only); Codex {@code PostToolUse} (200 KB {@code tool_response} kept); Antigravity {@code PreToolUse}
 * ({@code toolCall.args} kept, the first of {@code workspacePaths} as a list); a label without fields: byte for byte, whatever it holds</li>
 * <li>{@code --warm} ({@link HookClient#warm}): a file read as a host's input sends nothing; an input the server would refuse is refused as a hook's is; a label without fields is
 * {@link HookOptionException} UNKNOWN_VALUE; a value that is not {@code <label>=<file>} is OTHER</li>
 * <li>an input the server would refuse (empty: HOOK_BODY_EMPTY; {@code []}, {@code "text"}, {@code null}, a cut object: HOOK_BODY_NOT_A_JSON_OBJECT; a 0xFF byte: HOOK_BODY_NOT_UTF8)
 * is no request and the host gets the failure answer of the call (all six), with FAIL's stderr the reason's code alone</li>
 * <li>{@code X-Sennard-Time-Zone}: sent for {@code TZ=Asia/Seoul}, {@code :Asia/Seoul}, {@code Europe/Paris}, {@code UTC}; not sent when TZ is absent, empty, a POSIX rule
 * ({@code KST-9}), an offset or no region name</li>
 * <li>each failure answer (6) for each way the server does not answer: status 302/400/404/500/503 (SERVER_REFUSED and the status; a redirect is not followed), nothing listening
 * (SERVER_NOT_REACHABLE ConnectException), no answer within {@code --timeout 1} (SERVER_NOT_REACHABLE SocketTimeoutException); the three JSON texts are valid JSON of the host's shape</li>
 * <li>{@code --wake}: a 2xx body on stderr and exit 2 with nothing on stdout; a refusal, nothing listening and no answer in time print nothing and exit 0</li>
 * <li>{@code --only-if-env=NAME=VALUE}: no match (absent, another value, a malformed condition) is no request and exit 0, whatever the failure answer; a match goes on</li>
 * <li>the address: the port is read from the address file ({@link ServerAddress}) for every call, so a server started again at another port is found by the next call; no file, a file whose
 * port refuses the connection, a file of a process that has ended and one of another start of that id are all no server (SERVER_NOT_REACHABLE ConnectException) and the port of the file
 * is not asked when its process is not live</li>
 * <li>{@code --start-server}: no live server starts the server once (with the wait asked for) and then sends the event once, at the port the start announced; a server that answers (any
 * status) or is slow is not started; a start that fails is SERVER_NOT_STARTED with the start's message (its class name when it has none) and the call's failure answer; a call that does not ask, and an input
 * the server would refuse, never start one</li>
 * <li>{@code --bind-host}: no {@code X-Sennard-Host-Pids} without it; with it the parent chain of the client, nearest first, at most 8, each once</li>
 * <li>the candidates by real processes: the probe's parent comes first; with a program between the probe and the host (whether its command line holds {@code hook <host> <event>},
 * as a shell running the hook has, or not) nothing is skipped: the program and then the host above it are both sent, in the order of the parent chain (the server decides which is
 * the host)</li>
 * </ol>
 */
class HookClientTest {
    private static final Map<String, String> NO_ENVIRONMENT = Map.of();
    /** The calls of most cases do not ask for a server start; one that is made fails the case (an Error is not a start that failed). */
    private static final HookClient.ServerStart NO_START = within -> {
        throw new AssertionError("a server start was asked for within " + within);
    };

    @TempDir
    Path temporary;

    private HookServerStub stub;
    /** The address file the stub is announced in, as the real server announces itself in the state folder. */
    private Path serverFile;
    /** An address file of a server that is announced and whose port refuses the connection, and one that does not exist: no server is there. */
    private Path announcedButGone;
    private Path absent;

    @BeforeEach
    void startStub() throws IOException {
        stub = new HookServerStub();
        serverFile = temporary.resolve(ServerAddress.FILE_NAME);
        stub.announceIn(serverFile);
        announcedButGone = temporary.resolve("gone.properties");
        HookServerStub.announce(HookServerStub.unusedPort(), announcedButGone);
        absent = temporary.resolve("absent.properties");
    }

    @AfterEach
    void stopStub() {
        stub.close();
    }

    private Result run(HookClientCall call, Map<String, String> environment, byte[] stdin, HookClient.ServerStart serverStart) throws IOException {
        return runAt(serverFile, call, environment, stdin, serverStart);
    }

    private static Result runAt(Path file, HookClientCall call, Map<String, String> environment, byte[] stdin, HookClient.ServerStart serverStart) throws IOException {
        return WhatTheHostSees.run(new HookClient(environment, ProcessHandle.current(), file, serverStart), call, stdin);
    }

    private Result run(HookClientCall call, Map<String, String> environment, byte[] stdin) throws IOException {
        return run(call, environment, stdin, NO_START);
    }

    private Result run(HookClientCall call) throws IOException {
        return run(call, NO_ENVIRONMENT, bytes("{}"));
    }

    private Result runAt(Path file, HookClientCall call) throws IOException {
        return runAt(file, call, NO_ENVIRONMENT, bytes("{}"), NO_START);
    }

    private HookClient clientThatStartsNothing() {
        return new HookClient(NO_ENVIRONMENT, ProcessHandle.current(), serverFile, NO_START);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static HookClientCall call(FailureAnswer failure) {
        return HookClientCall.of("claude", "PreToolUse", failure).withTimeout(1);
    }

    // 1, 2: the answer and the request

    @Test
    void aTwoHundredAnswerIsPassedToStdoutByteForByteWithExitZero() throws IOException {
        Result result = run(call(FailureAnswer.FAIL));

        assertEquals(0, result.exit());
        assertEquals(HookServerStub.ANSWER, result.out());
        assertEquals("", result.err());
    }

    @Test
    void theRequestIsAPostOfStdinsBytesToTheHooksEndpointAsJson() throws IOException {
        byte[] stdin = bytes("{\"session_id\":\"s-1\",\"prompt\":\"한글 😀 질문\"}");

        run(HookClientCall.of("claude", "UserPromptSubmit", FailureAnswer.FAIL), NO_ENVIRONMENT, stdin);

        assertEquals(1, stub.received.size());
        Received request = stub.received.get(0);
        assertEquals("POST", request.method());
        assertEquals("/hooks/v1/claude/UserPromptSubmit", request.path());
        assertArrayEquals(stdin, request.body());
        assertEquals("application/json", request.headers().getFirst("Content-Type"));
        assertNull(request.headers().getFirst(HookHeaders.HOST_PIDS), "no host processes unless the call binds them");
    }

    @Test
    void aModuleIsTheEndpointsLastSegmentAndALargeBodyArrivesWhole() throws IOException {
        byte[] stdin = bytes("{\"tool_input\":\"" + "x".repeat(300_000) + "\"}");
        HookClientCall call = new HookClientCall("claude", "PreToolUse", "continuity", FailureAnswer.FAIL, 5, false, false, null, 0, HookClientCall.RefusalAnswer.AS_FAILURE);

        Result result = run(call, NO_ENVIRONMENT, stdin);

        assertEquals(0, result.exit());
        assertEquals("/hooks/v1/claude/PreToolUse/continuity", stub.received.get(0).path());
        assertArrayEquals(stdin, stub.received.get(0).body());
    }

    // 3: what is sent of the host's input

    /** The body the server stand-in received for a hook of the host. */
    private String sent(String label, String event, String stdin) throws IOException {
        stub.received.clear();
        Result result = run(HookClientCall.of(label, event, FailureAnswer.FAIL).withTimeout(20), NO_ENVIRONMENT, bytes(stdin));
        assertEquals(0, result.exit(), result.err());
        assertEquals(1, stub.received.size());
        return stub.received.get(0).bodyText();
    }

    @Test
    void aGeminiModelRequestSendsItsModelAndNotItsConversationOrConfig(TestReporter reporter) throws IOException {
        String turn = "y".repeat(1_048_576);
        byte[] stdin = bytes("{\"session_id\":\"g1\",\"transcript_path\":\"/t.json\",\"cwd\":\"/w\",\"hook_event_name\":\"BeforeModel\",\"timestamp\":\"2026-11-11T00:00:00Z\","
                + "\"llm_request\":{\"model\":\"gemini-3-pro\",\"messages\":[{\"role\":\"user\",\"content\":\"" + turn + "\"},{\"role\":\"model\",\"content\":\"" + turn + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + turn + "\"},{\"role\":\"model\",\"content\":\"" + turn + "\"}],"
                + "\"config\":{\"temperature\":0.2,\"topP\":0.95,\"systemInstruction\":\"" + "s".repeat(10_000) + "\"},\"toolConfig\":{\"mode\":\"AUTO\"}}}");

        Result result = run(HookClientCall.of("gemini", "BeforeModel", FailureAnswer.FAIL).withTimeout(20), NO_ENVIRONMENT, stdin);

        assertEquals(0, result.exit(), result.err());
        String body = stub.received.get(0).bodyText();
        assertEquals("{\"session_id\":\"g1\",\"transcript_path\":\"/t.json\",\"cwd\":\"/w\",\"llm_request\":{\"model\":\"gemini-3-pro\"}}", body);
        assertTrue(stdin.length > 4_000_000, "the model request is megabytes: " + stdin.length);
        assertFalse(body.contains("yyyy") || body.contains("systemInstruction"), "no part of the conversation or the config is sent");
        reporter.publishEntry("Gemini BeforeModel bytes, stdin -> sent", stdin.length + " -> " + stub.received.get(0).body().length);
    }

    @Test
    void aGeminiToolCallKeepsItsArgumentsContextAndResultAndDropsWhatTheServerDoesNotRead() throws IOException {
        String result = "r".repeat(100_000);

        assertEquals("{\"session_id\":\"g\",\"tool_name\":\"t\",\"tool_input\":{\"a\":{\"b\":[1,2.5,\"c\",null]}},\"mcp_context\":{\"server_name\":\"sennard\",\"tool_name\":\"x\"},"
                + "\"tool_response\":{\"llmContent\":\"" + result + "\"}}",
                sent("gemini", "AfterTool", "{\"session_id\":\"g\",\"hook_event_name\":\"AfterTool\",\"tool_name\":\"t\",\"tool_input\":{\"a\":{\"b\":[1,2.5,\"c\",null]}},"
                        + "\"mcp_context\":{\"server_name\":\"sennard\",\"tool_name\":\"x\",\"url\":\"u\"},\"tool_response\":{\"llmContent\":\"" + result + "\",\"returnDisplay\":\"shown\"}}"));
    }

    @Test
    void claudeKeepsAToolsInputAndSessionStartsFieldsAndDropsWhatTheServerDoesNotRead() throws IOException {
        String command = "echo " + "z".repeat(300_000);

        assertEquals("{\"session_id\":\"s\",\"agent_id\":\"a1\",\"tool_name\":\"Bash\",\"tool_use_id\":\"tu\",\"tool_input\":{\"command\":\"" + command + "\",\"x\":[true]}}",
                sent("claude", "PreToolUse", "{\"session_id\":\"s\",\"permission_mode\":\"default\",\"hook_event_name\":\"PreToolUse\",\"agent_id\":\"a1\",\"tool_name\":\"Bash\","
                        + "\"tool_use_id\":\"tu\",\"tool_input\":{\"command\":\"" + command + "\",\"x\":[true]}}"));
        assertEquals("{\"session_id\":\"s\",\"cwd\":\"/w\",\"model\":\"m\",\"effort\":{\"level\":\"high\"},\"source\":\"startup\"}",
                sent("claude", "SessionStart", "{\"session_id\":\"s\",\"cwd\":\"/w\",\"hook_event_name\":\"SessionStart\",\"model\":\"m\",\"effort\":{\"level\":\"high\",\"extra\":1},"
                        + "\"source\":\"startup\",\"permission_mode\":\"plan\"}"));
    }

    @Test
    void codexKeepsAToolsResponseAndDropsWhatTheServerDoesNotRead() throws IOException {
        String output = "o".repeat(200_000);

        assertEquals("{\"session_id\":\"s\",\"turn_id\":\"t\",\"tool_name\":\"Bash\",\"tool_input\":{\"cmd\":\"ls\"},\"tool_response\":{\"output\":\"" + output + "\",\"code\":0}}",
                sent("codex", "PostToolUse", "{\"session_id\":\"s\",\"turn_id\":\"t\",\"hook_event_name\":\"PostToolUse\",\"cli_version\":\"1\",\"tool_name\":\"Bash\",\"tool_input\":{\"cmd\":\"ls\"},"
                        + "\"tool_response\":{\"output\":\"" + output + "\",\"code\":0}}"));
    }

    @Test
    void antigravityKeepsAToolCallsArgumentsAndTheFirstWorkspacePathAsAList() throws IOException {
        assertEquals("{\"conversationId\":\"c1\",\"workspacePaths\":[\"/w1\"],\"toolCall\":{\"name\":\"call_mcp_tool\",\"args\":{\"ServerName\":\"sennard\",\"ToolName\":\"t\","
                + "\"Arguments\":{\"k\":[1,{\"z\":null}]}}}}",
                sent("agy", "PreToolUse", "{\"conversationId\":\"c1\",\"workspacePaths\":[\"/w1\",\"/w2\"],\"stepIndex\":4,\"toolCall\":{\"name\":\"call_mcp_tool\",\"id\":\"i\","
                        + "\"args\":{\"ServerName\":\"sennard\",\"ToolName\":\"t\",\"Arguments\":{\"k\":[1,{\"z\":null}]}}}}"));
    }

    @Test
    void aLabelWithoutFieldsIsSentByteForByteWhateverItHolds() throws IOException {
        byte[] json = bytes("{\"session_id\":\"s\",\"unknown\":[1,2,3],\"prompt\":\"한글 😀\"}");
        byte[] notJson = bytes("this is not JSON, and the server is the one to say so");

        run(HookClientCall.of("a-host-without-fields", "PreToolUse", FailureAnswer.FAIL), NO_ENVIRONMENT, json);
        run(HookClientCall.of("a-host-without-fields", "PreToolUse", FailureAnswer.FAIL), NO_ENVIRONMENT, notJson);

        assertArrayEquals(json, stub.received.get(0).body());
        assertArrayEquals(notJson, stub.received.get(1).body());
    }

    // 4: the AOT training's --warm

    /** The AOT training's {@code --warm}: the file is read as that host's input, as a hook of the host reads it, and nothing is sent or answered. */
    @Test
    void warmingReadsAFileAsAHostsInputAndSendsNothing() throws IOException {
        Path input = temporary.resolve("agy-input.json");
        Files.writeString(input, "{\"conversationId\":\"c1\",\"workspacePaths\":[\"/w1\"],\"invocationNum\":1,\"toolCall\":{\"name\":\"n\",\"args\":{\"k\":1}}}", StandardCharsets.UTF_8);
        HookClient client = clientThatStartsNothing();

        client.warm("agy", input);
        client.warm("agy=" + input);

        assertTrue(stub.received.isEmpty(), "warming sends no request");
    }

    @Test
    void warmingAnInputTheServerWouldRefuseIsRefusedLikeAHook() throws IOException {
        Path input = temporary.resolve("not-an-object.json");
        Files.writeString(input, "[1,2]", StandardCharsets.UTF_8);

        HookNoAnswer refused = assertThrows(HookNoAnswer.class, () -> clientThatStartsNothing().warm("agy", input));

        assertEquals(Reason.HOOK_BODY_NOT_A_JSON_OBJECT, refused.reason());
    }

    /** A host label that has no fields is no training: it fails naming the option and the label, never a success that learned nothing. */
    @Test
    void warmingAHostWithoutFieldsFailsWithTheLabelAndLearnsNothing() throws IOException {
        Path input = temporary.resolve("any-input.json");
        Files.writeString(input, "{\"conversationId\":\"c1\"}", StandardCharsets.UTF_8);
        HookClient client = clientThatStartsNothing();

        HookOptionException refused = assertThrows(HookOptionException.class, () -> client.warm("no-such-host", input));
        HookOptionException viaOption = assertThrows(HookOptionException.class, () -> client.warm("no-such-host=" + input));

        for (HookOptionException failure : List.of(refused, viaOption)) {
            assertEquals(Problem.UNKNOWN_VALUE, failure.problem());
            assertEquals("--warm", failure.option());
            assertEquals("no-such-host", failure.value());
            assertEquals("HOOK_OPTION_INVALID unknownValue --warm=no-such-host", failure.getMessage());
        }
        assertTrue(stub.received.isEmpty(), "nothing is sent");
    }

    @Test
    void aWarmValueThatIsNotALabelAndAFileIsRefused() {
        HookClient client = clientThatStartsNothing();

        for (String value : List.of("agy", "=file.json", "")) {
            HookOptionException failure = assertThrows(HookOptionException.class, () -> client.warm(value), value);
            assertEquals(Problem.OTHER, failure.problem(), value);
            assertEquals("--warm", failure.option(), value);
            assertEquals(value, failure.value(), value);
        }
    }

    // 5: an input the server would refuse

    private record Refused(String name, byte[] stdin, Reason reason) {
    }

    @Test
    void aBodyTheServerWouldRefuseIsNoRequestAndTheCallsFailureAnswerWithTheReasonsCode() throws IOException {
        List<Refused> refused = List.of(new Refused("empty", new byte[0], Reason.HOOK_BODY_EMPTY),
                new Refused("an array", bytes("[]"), Reason.HOOK_BODY_NOT_A_JSON_OBJECT), new Refused("a text", bytes("\"text\""), Reason.HOOK_BODY_NOT_A_JSON_OBJECT),
                new Refused("null", bytes("null"), Reason.HOOK_BODY_NOT_A_JSON_OBJECT), new Refused("a cut object", bytes("{\"session_id\":"), Reason.HOOK_BODY_NOT_A_JSON_OBJECT),
                new Refused("not JSON at all", bytes("this is not JSON"), Reason.HOOK_BODY_NOT_A_JSON_OBJECT),
                new Refused("a 0xFF byte", new byte[] {'{', '"', 'p', '"', ':', '"', (byte) 0xFF, '"', '}'}, Reason.HOOK_BODY_NOT_UTF8));
        for (String label : List.of("claude", "gemini")) {
            for (Refused body : refused) {
                for (FailureAnswer answer : FailureAnswer.values()) {
                    Result result = run(HookClientCall.of(label, "PreToolUse", answer).withTimeout(1), NO_ENVIRONMENT, body.stdin());
                    WhatTheHostSees.assertFailureAnswer(answer, result, body.reason().name(), label + " " + body.name());
                }
            }
        }
        assertEquals(0, stub.received.size(), "nothing was sent");
    }

    // 6: the time zone

    private String sentZone(Map<String, String> environment) throws IOException {
        stub.received.clear();
        run(call(FailureAnswer.FAIL), environment, bytes("{}"));
        assertEquals(1, stub.received.size());
        return stub.received.get(0).headers().getFirst(HookHeaders.TIME_ZONE);
    }

    @Test
    void theCallersTimeZoneIsSentWhenTzIsARegionName() throws IOException {
        assertEquals("Asia/Seoul", sentZone(Map.of("TZ", "Asia/Seoul")));
        assertEquals("Asia/Seoul", sentZone(Map.of("TZ", ":Asia/Seoul")), "POSIX allows a leading colon");
        assertEquals("Europe/Paris", sentZone(Map.of("TZ", "Europe/Paris")));
        assertEquals("UTC", sentZone(Map.of("TZ", "UTC")));
    }

    @Test
    void noTimeZoneIsSentWhenTzIsAbsentEmptyAPosixRuleOrNoRegion() throws IOException {
        assertNull(sentZone(Map.of()), "no TZ");
        assertNull(sentZone(Map.of("TZ", "")), "empty");
        assertNull(sentZone(Map.of("TZ", "KST-9")), "a POSIX rule");
        assertNull(sentZone(Map.of("TZ", "+09:00")), "an offset");
        assertNull(sentZone(Map.of("TZ", "Not/AZone")), "not a region");
    }

    // 7: the failure answers

    @Test
    void everyFailureAnswerIsWhatItsHostReadsWhenTheServerRefuses() throws IOException {
        for (int status : List.of(302, 400, 404, 500, 503)) {
            stub.status = status;
            stub.received.clear();
            for (FailureAnswer answer : FailureAnswer.values()) {
                WhatTheHostSees.assertFailureAnswer(answer, run(call(answer)), "SERVER_REFUSED " + status, "status " + status);
            }
            assertEquals(FailureAnswer.values().length, stub.received.size(), "one request per call: a redirect (302) is not followed, nothing is retried");
        }
    }

    /** A gate (a call that denies a refusal): a status of 400 or above stops the tool, whatever the call's failure answer is. */
    @Test
    void aGateDeniesTheToolWhenTheServerRefusesWithAnyStatusOfFourHundredOrAbove() throws IOException {
        for (int status : List.of(400, 404, 409, 422, 500, 503)) {
            stub.status = status;
            for (FailureAnswer answer : FailureAnswer.values()) {
                WhatTheHostSees.assertDenied(run(call(answer).denyingRefusal()), status, answer + " / status " + status);
            }
        }
    }

    /** A redirect is not followed and is no refusal: the gate gets the call's failure answer. */
    @Test
    void aGateIsNoRefusalForARedirect() throws IOException {
        stub.status = 302;
        for (FailureAnswer answer : FailureAnswer.values()) {
            WhatTheHostSees.assertFailureAnswer(answer, run(call(answer).denyingRefusal()), "SERVER_REFUSED 302", "a gate, status 302");
        }
    }

    /** A server that is not there is never a refusal: the gate passes it through the failure answer, as every hook does. */
    @Test
    void aGateGetsItsFailureAnswerWhenNothingListens() throws IOException {
        for (FailureAnswer answer : FailureAnswer.values()) {
            HookClientCall gate = HookClientCall.of("claude", "PreToolUse", answer).withTimeout(1).denyingRefusal();
            WhatTheHostSees.assertFailureAnswer(answer, runAt(announcedButGone, gate), "SERVER_NOT_REACHABLE ConnectException", "a gate, connection refused");
            WhatTheHostSees.assertFailureAnswer(answer, runAt(absent, gate), "SERVER_NOT_REACHABLE ConnectException", "a gate, no server announced");
        }
    }

    /** The stand-in takes the request and never answers: the gate's end is the read timeout, and a timeout is no refusal. */
    @Test
    void aGateGetsItsFailureAnswerWhenTheServerDoesNotAnswerInTime() throws IOException {
        stub.holdTheAnswer = true;
        for (FailureAnswer answer : FailureAnswer.values()) {
            WhatTheHostSees.assertFailureAnswer(answer, run(call(answer).denyingRefusal()), "SERVER_NOT_REACHABLE SocketTimeoutException", "a gate, no answer within --timeout 1");
        }
    }

    @Test
    void aGateIsAnsweredLikeEveryHookWhenTheServerAnswers() throws IOException {
        Result result = run(call(FailureAnswer.ALLOW_DECISION).denyingRefusal());

        assertEquals(0, result.exit());
        assertEquals(HookServerStub.ANSWER, result.out());
        assertEquals("", result.err());
    }

    @Test
    void everyFailureAnswerIsWhatItsHostReadsWhenNothingListens() throws IOException {
        for (FailureAnswer answer : FailureAnswer.values()) {
            HookClientCall call = HookClientCall.of("claude", "PreToolUse", answer).withTimeout(1);
            WhatTheHostSees.assertFailureAnswer(answer, runAt(announcedButGone, call), "SERVER_NOT_REACHABLE ConnectException", "connection refused");
            WhatTheHostSees.assertFailureAnswer(answer, runAt(absent, call), "SERVER_NOT_REACHABLE ConnectException", "no server is announced: the file is not there");
        }
    }

    /**
     * A file outlives a server that was killed: its process is gone, or its id belongs to another process now (it started at another time). That is no server, the same
     * {@code ConnectException} as a port that refuses, and the stub on the port the file names is never asked.
     */
    @Test
    void anAddressFileOfAProcessThatIsGoneOrNotTheSameIsNoServerAndItsPortIsNotAsked() throws Exception {
        Process ended = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        ended.waitFor();
        Path ofAGoneProcess = temporary.resolve("process-gone.properties");
        new ServerAddress(stub.port(), ended.pid(), Instant.parse("2020-01-01T00:00:00Z")).writeAtomically(ofAGoneProcess);
        Path ofAnotherStart = temporary.resolve("process-other-start.properties");
        ServerAddress self = ServerAddress.ofThisProcess(stub.port());
        new ServerAddress(self.port(), self.pid(), self.processStart().minusSeconds(3600)).writeAtomically(ofAnotherStart);

        for (FailureAnswer answer : FailureAnswer.values()) {
            HookClientCall call = HookClientCall.of("claude", "PreToolUse", answer).withTimeout(1);
            WhatTheHostSees.assertFailureAnswer(answer, runAt(ofAGoneProcess, call), "SERVER_NOT_REACHABLE ConnectException", "the process of the file has ended");
            WhatTheHostSees.assertFailureAnswer(answer, runAt(ofAnotherStart, call), "SERVER_NOT_REACHABLE ConnectException", "the process of that id started at another time");
        }
        assertTrue(stub.received.isEmpty(), "the stub on the file's port was not asked: a file of a process that is not the server is not trusted");
    }

    /** The port is read for every call: a server that was started again at another port is found by the next call with no change of the definition. */
    @Test
    void theNextCallFollowsAServerThatWasStartedAgainAtAnotherPort() throws IOException {
        assertEquals(0, run(call(FailureAnswer.FAIL)).exit());
        try (HookServerStub second = new HookServerStub()) {
            second.announceIn(serverFile);

            Result result = run(call(FailureAnswer.FAIL));

            assertEquals(0, result.exit());
            assertEquals(1, stub.received.size(), "the first server got the first call only");
            assertEquals(1, second.received.size(), "the second one, at its own port, got the next");
        }
    }

    /** The stand-in takes the request and never answers: what ends the call is the call's {@code --timeout 1}; with no timeout it would be the answer of a server that came back. */
    @Test
    void everyFailureAnswerIsWhatItsHostReadsWhenTheServerDoesNotAnswerInTime() throws IOException {
        stub.holdTheAnswer = true;
        for (FailureAnswer answer : FailureAnswer.values()) {
            WhatTheHostSees.assertFailureAnswer(answer, run(call(answer)), "SERVER_NOT_REACHABLE SocketTimeoutException", "no answer within --timeout 1");
        }
    }

    @Test
    void theFixedTextsAreAsciiWithoutQuotesAndBackslashes() {
        assertTrue(HookClient.GUIDANCE.chars().allMatch(c -> c >= 0x20 && c < 0x7f && c != '"' && c != '\\'), HookClient.GUIDANCE);
        assertEquals("{\"systemMessage\":\"" + HookClient.GUIDANCE + "\"}", HookClient.GUIDANCE_SYSTEM_MESSAGE_JSON);
        assertEquals("{\"injectSteps\":[{\"systemMessage\":{\"systemMessage\":\"" + HookClient.GUIDANCE + "\"}}]}", HookClient.GUIDANCE_STEPS_JSON);
        assertEquals("{\"decision\":\"allow\"}", HookClient.ALLOW_DECISION_JSON);
        assertEquals(2, HookClient.WAKE_EXIT);
    }

    // 8: the idle wake

    private static HookClientCall wake() {
        return HookClientCall.of("claude", "Stop", FailureAnswer.GUIDANCE_STDERR).asWake(1, "CLAUDE_CODE_SESSION_ATTENDED=1");
    }

    private static final Map<String, String> ATTENDED = Map.of("CLAUDE_CODE_SESSION_ATTENDED", "1");

    @Test
    void aWakeWithAMessageIsTheMessageOnStderrAndExitTwo() throws IOException {
        stub.answer = bytes("message for the idle session");

        Result result = run(wake(), ATTENDED, bytes("{}"));

        assertEquals(new Result(2, "", "message for the idle session"), result);
        assertEquals("/hooks/v1/claude/Stop/wake", stub.received.get(0).path());
    }

    @Test
    void aWakeThatEndsInAnyOtherWayPrintsNothingAndExitsZero() throws IOException {
        for (int status : List.of(400, 404, 500, 503)) {
            stub.status = status;
            assertEquals(new Result(0, "", ""), run(wake(), ATTENDED, bytes("{}")), "status " + status);
        }
        assertEquals(new Result(0, "", ""), runAt(announcedButGone, wake(), ATTENDED, bytes("{}"), NO_START), "nothing listening");
        assertEquals(new Result(0, "", ""), runAt(absent, wake(), ATTENDED, bytes("{}"), NO_START), "no server announced");
        stub.status = 200;
        stub.holdTheAnswer = true;
        assertEquals(new Result(0, "", ""), run(wake(), ATTENDED, bytes("{}")), "no message in time");
    }

    // 9: only-if-env

    @Test
    void aConditionThatDoesNotHoldIsNoRequestAndExitZeroWhateverTheFailureAnswer() throws IOException {
        List<Map<String, String>> environments = List.of(Map.of(), Map.of("CLAUDE_CODE_SESSION_ATTENDED", "0"), Map.of("CLAUDE_CODE_SESSION_ATTENDED", ""), Map.of("OTHER", "1"));
        for (FailureAnswer answer : FailureAnswer.values()) {
            for (Map<String, String> environment : environments) {
                HookClientCall call = new HookClientCall("claude", "Stop", null, answer, 1, false, false, "CLAUDE_CODE_SESSION_ATTENDED=1", 0, HookClientCall.RefusalAnswer.AS_FAILURE);
                assertEquals(new Result(0, "", ""), run(call, environment, bytes("{}")), answer + " " + environment);
            }
        }
        for (String malformed : List.of("CLAUDE_CODE_SESSION_ATTENDED", "=1")) {
            HookClientCall call = new HookClientCall("claude", "Stop", null, FailureAnswer.FAIL, 1, false, false, malformed, 0, HookClientCall.RefusalAnswer.AS_FAILURE);
            assertEquals(new Result(0, "", ""), run(call, Map.of("CLAUDE_CODE_SESSION_ATTENDED", "1"), bytes("{}")), malformed);
        }
        assertEquals(0, stub.received.size(), "no request was made");
    }

    @Test
    void aConditionThatHoldsGoesOnToTheRequest() throws IOException {
        HookClientCall call = new HookClientCall("claude", "Stop", null, FailureAnswer.FAIL, 1, false, false, "CLAUDE_CODE_SESSION_ATTENDED=1", 0, HookClientCall.RefusalAnswer.AS_FAILURE);

        Result result = run(call, ATTENDED, bytes("{}"));

        assertEquals(new Result(0, HookServerStub.ANSWER, ""), result);
        assertEquals(1, stub.received.size());
    }

    // 10: the server start of a session start; the real start of the installed server is the installer's tests

    /**
     * A start that does what the real one ends with: a server that answers (a real listener on a port of the system's choice) and is announced in the address file, as the real server
     * does when it is ready, after which the event is sent.
     */
    private static final class ListeningStart implements HookClient.ServerStart {
        final Path file;
        final List<Duration> asked = new CopyOnWriteArrayList<>();
        volatile HookServerStub started;

        ListeningStart(Path file) {
            this.file = file;
        }

        @Override
        public void startWithin(Duration within) {
            asked.add(within);
            try {
                started = new HookServerStub();
                started.announceIn(file);
            } catch (IOException cannotListen) {
                throw new IllegalStateException(cannotListen);
            }
        }
    }

    @Test
    void noLiveServerForACallThatAsksStartsTheServerOnceAndThenSendsTheEventAtThePortTheStartAnnouncedAsIfItWereUp() throws IOException {
        Path noFile = temporary.resolve("start-no-file.properties");
        Path stale = temporary.resolve("start-stale.properties");
        HookServerStub.announce(HookServerStub.unusedPort(), stale);
        for (Path file : List.of(noFile, stale)) {
            ListeningStart start = new ListeningStart(file);
            try {
                HookClientCall call = HookClientCall.of("claude", "SessionStart", FailureAnswer.GUIDANCE_STDERR).bindingHost().startingServer(7);

                Result result = runAt(file, call, NO_ENVIRONMENT, bytes("{\"session_id\":\"s1\"}"), start);

                assertEquals(new Result(0, HookServerStub.ANSWER, ""), result, "what the host sees is what a server that was up would have given it: " + file.getFileName());
                assertEquals(List.of(Duration.ofSeconds(7)), start.asked, "started once, with the wait the call asked for");
                assertEquals(1, start.started.received.size());
                assertEquals("/hooks/v1/claude/SessionStart", start.started.received.get(0).path());
                assertEquals("{\"session_id\":\"s1\"}", start.started.received.get(0).bodyText(), "the original event, sent once");
            } finally {
                if (start.started != null) {
                    start.started.close();
                }
            }
        }
    }

    @Test
    void aStartThatEndsWithNoServerAnnouncedIsStillNoAnswer() throws IOException {
        HookClientCall call = HookClientCall.of("claude", "SessionStart", FailureAnswer.GUIDANCE_STDERR).startingServer(7);

        // the start returns, and no server is announced: the event has nowhere to go, which is the same refusal of a connection, not a start that failed
        Result result = runAt(absent, call, NO_ENVIRONMENT, bytes("{}"), within -> {
        });

        WhatTheHostSees.assertFailureAnswer(FailureAnswer.GUIDANCE_STDERR, result, "SERVER_NOT_REACHABLE ConnectException", "the start announced nothing");
    }

    @Test
    void aServerThatAnswersIsNotStartedWhateverItAnswers() throws IOException {
        HookClientCall call = HookClientCall.of("claude", "SessionStart", FailureAnswer.GUIDANCE_STDERR).startingServer(7).withTimeout(1);
        for (int status : List.of(200, 400, 404, 500, 503)) {
            stub.status = status;
            run(call, NO_ENVIRONMENT, bytes("{}")); // NO_START fails the case when it is asked for a start
        }
        stub.status = 200;
        stub.holdTheAnswer = true;
        WhatTheHostSees.assertFailureAnswer(FailureAnswer.GUIDANCE_STDERR, run(call, NO_ENVIRONMENT, bytes("{}")), "SERVER_NOT_REACHABLE SocketTimeoutException",
                "no answer within --timeout 1 is not a missing server");
    }

    @Test
    void aStartThatFailsIsNoAnswerWithItsMessageAndTheHostGetsTheCallsFailureAnswer() throws IOException {
        for (FailureAnswer answer : FailureAnswer.values()) {
            HookClientCall call = HookClientCall.of("claude", "SessionStart", answer).startingServer(7);

            Result withMessage = runAt(absent, call, NO_ENVIRONMENT, bytes("{}"), within -> {
                throw new IllegalStateException("the installation was not found");
            });
            Result withoutMessage = runAt(absent, call, NO_ENVIRONMENT, bytes("{}"), within -> {
                throw new UnsupportedOperationException();
            });

            WhatTheHostSees.assertFailureAnswer(answer, withMessage, "SERVER_NOT_STARTED the installation was not found", "the start failed with a message");
            WhatTheHostSees.assertFailureAnswer(answer, withoutMessage, "SERVER_NOT_STARTED UnsupportedOperationException", "the start failed without a message");
        }
    }

    @Test
    void aCallThatDoesNotAskNeverStartsAndAnInputTheServerWouldRefuseDoesNotStartOne() throws IOException {
        // NO_START fails the case when it is called.
        WhatTheHostSees.assertFailureAnswer(FailureAnswer.GUIDANCE_STDERR, runAt(absent, HookClientCall.of("claude", "UserPromptSubmit", FailureAnswer.GUIDANCE_STDERR)),
                "SERVER_NOT_REACHABLE ConnectException", "the call does not ask");
        WhatTheHostSees.assertFailureAnswer(FailureAnswer.GUIDANCE_STDERR,
                runAt(absent, HookClientCall.of("claude", "SessionStart", FailureAnswer.GUIDANCE_STDERR).startingServer(7), NO_ENVIRONMENT, bytes("this is not JSON"), NO_START),
                "HOOK_BODY_NOT_A_JSON_OBJECT", "an input that is refused before any request");
    }

    // 11: the host process

    @Test
    void theHostCandidatesAreOnlySentForACallThatBindsThem() throws IOException {
        run(HookClientCall.of("claude", "SessionStart", FailureAnswer.FAIL));
        assertNull(stub.received.get(0).headers().getFirst(HookHeaders.HOST_PIDS));

        run(HookClientCall.of("claude", "SessionStart", FailureAnswer.FAIL).bindingHost());
        List<Long> expected = clientThatStartsNothing().hostCandidates();
        assertEquals(expected, candidates(stub.received.get(1).headers().getFirst(HookHeaders.HOST_PIDS)), "the header is the parent chain of this process");
        assertEquals(ProcessHandle.current().parent().orElseThrow().pid(), expected.get(0), "nearest first: the first is the direct parent");
        assertTrue(expected.size() <= 8, expected.toString());
        assertEquals(new HashSet<>(expected).size(), expected.size(), "each process once");
    }

    /** The header's value: process ids separated by commas, nearest first. */
    private static List<Long> candidates(String header) {
        return header == null || header.isEmpty() ? List.of() : Arrays.stream(header.split(",")).map(String::strip).map(Long::parseLong).toList();
    }

    private record Probed(int exit, String output, List<Long> candidates, long pid) {
    }

    /** Runs a Java process from the test's class path (as the tests that start the product do) and returns what the server stand-in received from it. */
    private Probed probe(Class<?> main, String... arguments) throws Exception {
        String childHeap = System.getProperty("sennard.test.childHeap");
        assertNotNull(childHeap, "sennard.test.childHeap is set by the build on every test task");
        Path log = Files.createTempFile(temporary, "probe-", ".log");
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx" + childHeap, "-Dsennard.test.childHeap=" + childHeap, "-cp",
                System.getProperty("sennard.test.classpath"), main.getName()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("the process did not end in 90 s: " + Files.readString(log));
        }
        String output = Files.readString(log);
        assertEquals(1, stub.received.size(), "the process posted once: " + output);
        return new Probed(process.exitValue(), output, candidates(stub.received.get(0).headers().getFirst(HookHeaders.HOST_PIDS)), process.pid());
    }

    private static long thisProcess() {
        return ProcessHandle.current().pid();
    }

    @Test
    void theFirstCandidateIsTheParentWhenNothingIsBetweenThem() throws Exception {
        Probed probed = probe(HookClientChainProbe.class, serverFile.toString(), "claude", "SessionStart");

        assertEquals(0, probed.exit(), probed.output());
        assertEquals(thisProcess(), probed.candidates().get(0), "the process that started the probe comes first: " + probed.candidates() + " " + probed.output());
        assertEquals(ProcessHandle.current().parent().map(ProcessHandle::pid).orElseThrow(), probed.candidates().get(1), "then the chain above it: " + probed.candidates());
    }

    @Test
    void aProgramBetweenWhoseCommandLineIsTheHooksOwnIsNotSkippedAndEverythingAboveItIsSentInOrder() throws Exception {
        // The command line of another process cannot be read on every OS (it is empty on Windows), so nothing is skipped here: the server picks the host.
        Probed probed = probe(HookClientChainRelay.class, serverFile.toString(), "claude", "SessionStart", "hook", "claude", "SessionStart");

        assertEquals(0, probed.exit(), probed.output());
        assertEquals(List.of(probed.pid(), thisProcess()), probed.candidates().subList(0, 2), "the relay, then the host above it, in the order of the parent chain: " + probed.candidates() + " " + probed.output());
    }

    @Test
    void aProgramBetweenWithAnotherCommandLineIsASentCandidateToo() throws Exception {
        Probed probed = probe(HookClientChainRelay.class, serverFile.toString(), "claude", "SessionStart");

        assertEquals(0, probed.exit(), probed.output());
        assertEquals(List.of(probed.pid(), thisProcess()), probed.candidates().subList(0, 2), "the relay and this test's process, in that order: " + probed.candidates() + " " + probed.output());
    }

    @Test
    void atMostEightCandidatesAreSentNearestFirstAndEachOnce() throws Exception {
        Probed probed = probe(HookClientChainProbe.class, serverFile.toString(), "claude", "SessionStart");

        assertTrue(probed.candidates().size() <= 8, probed.candidates().toString());
        assertEquals(new HashSet<>(probed.candidates()).size(), probed.candidates().size(), probed.candidates().toString());
    }
}
