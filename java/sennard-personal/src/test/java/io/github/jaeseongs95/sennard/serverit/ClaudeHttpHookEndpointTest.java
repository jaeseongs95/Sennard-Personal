// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.hookclient.HookClientMain;
import io.github.jaeseongs95.sennard.hookclient.McpRelayMain;
import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.server.hook.HookInstallation;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict.ContextBlock;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostRegistrationFile;
import io.github.jaeseongs95.sennard.server.hook.host.ClaudeHookAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Claude Code's hook endpoint on a real Tomcat, and the command definition that reaches it: the hook client posts the hook's stdin JSON (POST, {@code Content-Type: application/json}),
 * and the host reads a 2xx body as the hook's output and takes any other status as a failed hook (a non-blocking error), except where the hook's definition says
 * {@code "onFailure": "block"} (the governance and native-peer checks). The cases that post to the endpoint send what the client sends with the JDK's {@link HttpClient}, with HTTP/1.1 and no header
 * added. (Claude Code's own {@code type: "http"} hook is not used: its URL takes no environment variable, and the server's port is the operating system's choice at each start.)
 *
 * <p>Case table (target: {@code /hooks/v1/claude/<event>[/<module>]} of the assembled application; stand-in: only the domain's verdict, as in
 * {@link HookEndpointTest}; input: the recorded Claude hook bodies, {@code hook-fixtures/linux/claude}):
 * <ol>
 * <li>every command of the definition the Claude adapter generates is run through the real hook client in a JVM of its own, at the port the server announced in its state folder, and is
 * answered with exit 0 and {@code {}}; it reaches the domain once at its endpoint; governance and native-peer have {@code onFailure: block}; the session start names the process above
 * the client</li>
 * <li>UserPromptSubmit with a context verdict: {@code hookSpecificOutput.additionalContext} with the event name</li>
 * <li>PreToolUse governance, Refuse: 2xx with {@code permissionDecision: deny} and the reason; the handler read the module governance</li>
 * <li>PreToolUse continuity, Proceed: {@code {}}; AddContext: {@code additionalContext} named PreToolUse</li>
 * <li>PostToolUse, Proceed: {@code {}}; AddContext: {@code additionalContext} named PostToolUse</li>
 * <li>Stop, ContinueTurn: {@code additionalContext} named Stop (Claude's way of continuing a turn here); Stop, Proceed: {@code {}}</li>
 * <li>SessionEnd, Proceed: {@code {}}</li>
 * <li>empty body 400, a body that is not JSON 400, a JSON body without a session 400, an unknown host 404, an unknown event 400, a verdict the event cannot
 * carry 500: none is 2xx, each has a JSON body with its reason, and none reaches the domain</li>
 * <li>a hook posted by the host has no host process id and the body reaches the handler unchanged</li>
 * </ol>
 */
class ClaudeHttpHookEndpointTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static RunningServer server;
    private static HookTestSupport.ScriptedHostEventHandler handler;
    private static HttpClient client;

    @TempDir
    Path temporary;

    @BeforeAll
    static void start() throws Exception {
        server = RunningServer.start(List.of(ServerFixtures.Beans.class, HookTestSupport.HandlerBeans.class));
        handler = server.bean(HookTestSupport.ScriptedHostEventHandler.class);
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @BeforeEach
    void reset() {
        handler.next.set(new HookVerdict.Proceed());
        handler.failure.set(null);
        handler.calls.clear();
    }

    /** What Claude Code sends: a POST of the hook's JSON with the one content-type header. */
    private static HttpResponse<byte[]> post(String url, byte[] body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static HttpResponse<byte[]> post(String path, String event, byte[] body) throws IOException, InterruptedException {
        return post("http://127.0.0.1:" + server.port + "/hooks/v1/claude/" + event + path, body);
    }

    private static byte[] fixture(String event) {
        return HookTestSupport.fixture("linux", "claude", event);
    }

    private static JsonNode json(HttpResponse<byte[]> response) {
        return MAPPER.readTree(response.body());
    }

    private static HttpResponse<byte[]> ok(HttpResponse<byte[]> response) {
        assertEquals(200, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("application/json"), response.headers().toString());
        assertTrue(json(response).isObject(), "the body is the hook's output, a JSON object");
        return response;
    }

    private void assertNotAnswered(HttpResponse<byte[]> response, int status, String reason) {
        assertEquals(status, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        assertFalse(response.statusCode() / 100 == 2, "Claude reads a 2xx body as the hook's output");
        assertEquals(reason, json(response).path("reason").asString());
        assertEquals(0, handler.calls.size(), "a hook with no answer reached the domain");
    }

    /**
     * The definition an installation writes for Claude Code is a command per event, so what Claude Code does is run that command with the event's JSON on stdin. This does it for every
     * command of the generated definition, with the real hook client in a JVM of its own (as the definition names it: the program, then the args), against this real server, at the port
     * the server announced in its state folder: nothing here names the port. Each is answered with the verdict's output on stdout and exit 0, reaches the domain once at its
     * endpoint. Governance, continuity and native-peer ask the host to block when the hook fails. The session start also tells the process above the client (this one).
     */
    @Test
    void everyCommandOfTheGeneratedDefinitionIsRunThroughTheRealHookClientAndAnsweredAtThePortTheServerAnnounced() throws Exception {
        String childHeap = System.getProperty("sennard.test.childHeap");
        String classPath = System.getProperty("sennard.test.classpath");
        assertNotNull(childHeap, "sennard.test.childHeap is set by the build on every test task");
        assertNotNull(classPath, "sennard.test.classpath is set by the build on every test task");
        ClaudeHookAdapter adapter = server.bean(ClaudeHookAdapter.class);
        Path serverFile = server.stateDirectory().resolve(ServerAddress.FILE_NAME);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path unusedJar = server.stateDirectory().resolve("sennard-unused.jar"); // the server is up, so the jar that would start one is never run
        HookInstallation installation = new HookInstallation(
                List.of(java, "-Xmx" + childHeap, "-cp", classPath, HookClientMain.class.getName(), "--server-file=" + serverFile, "--jar=" + unusedJar),
                List.of(java, "-Xmx" + childHeap, "-cp", classPath, McpRelayMain.class.getName(), "--server-file=" + serverFile, "--jar=" + unusedJar));
        HostRegistrationFile.WholeFile definition = assertInstanceOf(HostRegistrationFile.WholeFile.class, adapter.registrationFiles(installation).get(0));
        Path folder = temporary;
        List<String> blocking = new ArrayList<>();
        List<String> wakes = new ArrayList<>();
        int ran = 0;
        for (var event : definition.content().path("hooks").properties()) {
            for (JsonNode entry : event.getValue()) {
                for (JsonNode hook : entry.path("hooks")) {
                    assertEquals("command", hook.path("type").asString(), event.getKey() + ": no HTTP hook (its URL cannot hold a port that changes at each start)");
                    List<String> command = new ArrayList<>(List.of(hook.path("command").asString()));
                    hook.path("args").forEach(argument -> command.add(argument.asString()));
                    if (hook.path("asyncRewake").asBoolean(false)) {
                        wakes.add(event.getKey()); // waits for a message for as long as a session is idle: the wake's own case is HookLauncherProcessTest's
                        continue;
                    }
                    String body = "{\"session_id\":\"" + HookTestSupport.SESSION + "\",\"hook_event_name\":\"" + event.getKey() + "\"}";
                    Path stdin = folder.resolve("stdin-" + ran + ".json");
                    Path stdout = folder.resolve("stdout-" + ran + ".txt");
                    Path stderr = folder.resolve("stderr-" + ran + ".txt");
                    Files.write(stdin, fixtureOrMinimal(event.getKey(), body));
                    Process process = new ProcessBuilder(command).redirectInput(stdin.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
                    if (!process.waitFor(120, TimeUnit.SECONDS)) {
                        process.descendants().forEach(ProcessHandle::destroyForcibly);
                        process.destroyForcibly();
                        throw new AssertionError(event.getKey() + ": the hook client did not end in 120 s");
                    }
                    String where = event.getKey() + " " + command.subList(command.size() - 3, command.size()) + " stderr: " + Files.readString(stderr);
                    assertEquals(0, process.exitValue(), where);
                    assertEquals(MAPPER.readTree("{}"), MAPPER.readTree(Files.readString(stdout)), where);
                    ran++;
                    if (hook.path("onFailure").asString("").equals("block")) {
                        assertTrue(command.contains("--on-failure=block-stderr"), where);
                        blocking.add(command.stream().filter(arg -> arg.startsWith("--module=")).findFirst().orElseThrow());
                    }
                }
            }
        }
        assertEquals(14, ran, "the session start and the 13 other hooks of the definition ran; the two wakes did not: " + wakes);
        assertEquals(List.of("SessionStart", "Stop"), wakes);
        assertEquals(ran, handler.calls.size(), "each command reached the domain once");
        assertEquals(List.of("--module=governance", "--module=continuity", "--module=native-peer"), blocking);
        assertEquals(1, handler.calls.stream().filter(call -> "governance".equals(call.module())).count());
        assertEquals(1, handler.calls.stream().filter(call -> "continuity".equals(call.module())).count());
        assertEquals(1, handler.calls.stream().filter(call -> "native-peer".equals(call.module())).count());
        var sessionStart = handler.calls.stream().filter(call -> call.event() instanceof HostEvent.SessionStarted).findFirst().orElseThrow();
        assertEquals(ProcessHandle.current().pid(), sessionStart.hostCandidates().get(0), "the session start's hook client names the process above it: the one that ran it");
    }

    private static byte[] fixtureOrMinimal(String event, String minimal) {
        return switch (event) {
            case "SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse", "Stop", "SessionEnd" -> fixture(event);
            default -> minimal.getBytes(StandardCharsets.UTF_8);
        };
    }

    @Test
    void aContextVerdictAtAPromptIsClaudesAdditionalContext() throws Exception {
        handler.next.set(new HookVerdict.AddContext(List.of(new ContextBlock("Remember the rule.", false))));

        JsonNode answer = json(ok(post("", "UserPromptSubmit", fixture("UserPromptSubmit"))));

        assertEquals("UserPromptSubmit", answer.path("hookSpecificOutput").path("hookEventName").asString());
        assertEquals("Remember the rule.", answer.path("hookSpecificOutput").path("additionalContext").asString());
        assertInstanceOf(HostEvent.UserPromptSubmitted.class, handler.calls.peek().event());
    }

    @Test
    void aGovernanceRefusalIsA2xxAnswerWithTheDenyDecision() throws Exception {
        handler.next.set(new HookVerdict.Refuse("not allowed here"));

        JsonNode answer = json(ok(post("/governance", "PreToolUse", fixture("PreToolUse"))));

        assertEquals("PreToolUse", answer.path("hookSpecificOutput").path("hookEventName").asString());
        assertEquals("deny", answer.path("hookSpecificOutput").path("permissionDecision").asString());
        assertEquals("not allowed here", answer.path("hookSpecificOutput").path("permissionDecisionReason").asString());
        assertEquals("governance", handler.calls.peek().module());
        assertInstanceOf(HostEvent.ToolRequested.class, handler.calls.peek().event());
    }

    @Test
    void aContinuityPreToolUseAllowsWithAnEmptyObjectOrAddsContext() throws Exception {
        assertEquals(MAPPER.readTree("{}"), json(ok(post("/continuity", "PreToolUse", fixture("PreToolUse")))));
        assertEquals("continuity", handler.calls.peek().module());

        handler.next.set(new HookVerdict.AddContext(List.of(new ContextBlock("Mind the open item.", false))));
        JsonNode answer = json(ok(post("/continuity", "PreToolUse", fixture("PreToolUse"))));
        assertEquals("PreToolUse", answer.path("hookSpecificOutput").path("hookEventName").asString());
        assertEquals("Mind the open item.", answer.path("hookSpecificOutput").path("additionalContext").asString());
    }

    @Test
    void aPostToolUseAnswersAnEmptyObjectOrAdditionalContext() throws Exception {
        assertEquals(MAPPER.readTree("{}"), json(ok(post("", "PostToolUse", fixture("PostToolUse")))));
        assertInstanceOf(HostEvent.ToolFinished.class, handler.calls.peek().event());

        handler.next.set(new HookVerdict.AddContext(List.of(new ContextBlock("After the tool.", false))));
        JsonNode answer = json(ok(post("", "PostToolUse", fixture("PostToolUse"))));
        assertEquals("PostToolUse", answer.path("hookSpecificOutput").path("hookEventName").asString());
        assertEquals("After the tool.", answer.path("hookSpecificOutput").path("additionalContext").asString());
    }

    @Test
    void aStopThatContinuesTheTurnAnswersWithTheInstructionAsContext() throws Exception {
        assertEquals(MAPPER.readTree("{}"), json(ok(post("", "Stop", fixture("Stop")))));
        assertInstanceOf(HostEvent.TurnEnded.class, handler.calls.peek().event());

        handler.next.set(new HookVerdict.ContinueTurn("Check the open item first."));
        JsonNode answer = json(ok(post("", "Stop", fixture("Stop"))));
        assertEquals("Stop", answer.path("hookSpecificOutput").path("hookEventName").asString());
        assertEquals("Check the open item first.", answer.path("hookSpecificOutput").path("additionalContext").asString());
    }

    @Test
    void aSessionEndAnswersAnEmptyObject() throws Exception {
        assertEquals(MAPPER.readTree("{}"), json(ok(post("", "SessionEnd", fixture("SessionEnd")))));
        assertInstanceOf(HostEvent.SessionEnded.class, handler.calls.peek().event());
    }

    @Test
    void aHookTheHostPostsItselfHasNoHostProcessAndItsBodyArrivesUnchanged() throws Exception {
        ok(post("", "UserPromptSubmit", fixture("UserPromptSubmit")));

        var call = handler.calls.peek();
        assertEquals(List.of(), call.hostCandidates(), "only the Sennard launcher tells the processes above it");
        assertNull(call.module());
        assertEquals(HookTestSupport.SESSION, call.event().context().sessionId());
        JsonNode sent = MAPPER.readTree(fixture("UserPromptSubmit"));
        assertEquals(sent.path("prompt").asString(), assertInstanceOf(HostEvent.UserPromptSubmitted.class, call.event()).prompt());
        assertEquals(sent.path("cwd").asString(), call.event().context().workspace());
    }

    @Test
    void anEmptyBodyIsNotAnAnswer() throws Exception {
        assertNotAnswered(post("", "UserPromptSubmit", new byte[0]), 400, "HOOK_BODY_EMPTY");
    }

    @Test
    void aBodyThatIsNotJsonIsNotAnAnswer() throws Exception {
        assertNotAnswered(post("", "UserPromptSubmit", "this is not json".getBytes(StandardCharsets.UTF_8)), 400, "HOOK_BODY_NOT_A_JSON_OBJECT");
        assertNotAnswered(post("", "UserPromptSubmit", "[1,2]".getBytes(StandardCharsets.UTF_8)), 400, "HOOK_BODY_NOT_A_JSON_OBJECT");
    }

    @Test
    void aBodyWithoutASessionIsNotAnAnswer() throws Exception {
        assertNotAnswered(post("", "UserPromptSubmit", "{\"prompt\":\"hello\"}".getBytes(StandardCharsets.UTF_8)), 400, "HOOK_SESSION_ID_MISSING");
        assertNotAnswered(post("/governance", "PreToolUse", "{\"session_id\":\"\",\"tool_name\":\"Bash\"}".getBytes(StandardCharsets.UTF_8)), 400, "HOOK_SESSION_ID_MISSING");
    }

    @Test
    void anUnknownHostIsNotFound() throws Exception {
        assertNotAnswered(post("http://127.0.0.1:" + server.port + "/hooks/v1/nobody/UserPromptSubmit", fixture("UserPromptSubmit")), 404, "HOOK_HOST_UNKNOWN");
    }

    @Test
    void anEventClaudeDoesNotHaveIsNotAnAnswer() throws Exception {
        assertNotAnswered(post("", "BeforeAgent", fixture("UserPromptSubmit")), 400, "HOOK_EVENT_UNKNOWN");
    }

    @Test
    void aVerdictTheEventCannotCarryIsNotAnAnswerEither() throws Exception {
        handler.next.set(new HookVerdict.AddContext(List.of(new ContextBlock("nothing reads this", false))));

        HttpResponse<byte[]> response = post("", "SessionEnd", fixture("SessionEnd"));

        assertEquals(500, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        assertEquals("HOOK_VERDICT_NOT_APPLICABLE", json(response).path("reason").asString());
    }

    /**
     * The decision of 2026-11-16: a PreToolUse that is a gate (the governance check and the continuity edit gate) answers 2xx with the deny of the tool when the server refused the
     * call, whatever the status would have been (409 for a lost first creation, another 4xx, 503, 500), because Claude Code runs the tool when a hook answers a status above 299.
     * The deny names the code of the refusal. The input is the recorded PreToolUse body.
     */
    @Test
    void aGatePreToolUseTheServerRefusedIsAnsweredWithTheDenyAndTheCode() throws Exception {
        for (String module : List.of("/governance", "/continuity")) {
            for (HookTestSupport.FailingDomain failing : HookTestSupport.failingDomains()) {
                handler.failure.set(failing.failure());

                JsonNode answer = json(ok(post(module, "PreToolUse", fixture("PreToolUse"))));

                String where = module + " " + failing.situation() + ": " + answer;
                assertEquals("PreToolUse", answer.path("hookSpecificOutput").path("hookEventName").asString(), where);
                assertEquals("deny", answer.path("hookSpecificOutput").path("permissionDecision").asString(), where);
                assertTrue(answer.path("hookSpecificOutput").path("permissionDecisionReason").asString().startsWith(HookTestSupport.GATE_REFUSED + failing.code() + ")"), where);
            }
        }
    }

    /** Not a gate: every other event keeps the status of its refusal, which the host takes as a failed hook and goes on from. */
    @Test
    void anEventThatIsNoGateKeepsTheStatusOfTheRefusal() throws Exception {
        List<String> events = List.of("UserPromptSubmit", "PostToolUse", "Stop", "SessionEnd");
        for (String event : events) {
            for (HookTestSupport.FailingDomain failing : HookTestSupport.failingDomains()) {
                handler.failure.set(failing.failure());

                HttpResponse<byte[]> response = post("", event, fixture(event));

                assertEquals(failing.status(), response.statusCode(), event + " " + failing.situation() + ": " + new String(response.body(), StandardCharsets.UTF_8));
            }
        }
    }
}
