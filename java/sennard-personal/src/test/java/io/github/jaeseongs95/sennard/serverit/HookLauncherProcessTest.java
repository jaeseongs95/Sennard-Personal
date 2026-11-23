// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.sun.net.httpserver.HttpServer;

import io.github.jaeseongs95.sennard.hookclient.HookClientArguments;
import io.github.jaeseongs95.sennard.hookclient.HookClientMain;
import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.personal.install.InstalledHookCommand;
import io.github.jaeseongs95.sennard.personal.install.InstalledVersion;
import io.github.jaeseongs95.sennard.personal.install.ReleaseJarFixture;
import io.github.jaeseongs95.sennard.server.hook.HookInstallation;
import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.server.hook.HookRejection;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict.ContextBlock;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.HostRegistrationFile;
import io.github.jaeseongs95.sennard.server.messaging.WakeClaims;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The generated hook definitions run for real: the command of a definition is started as the host starts it, with no shell and no other program in between; the real server
 * answers, the recorded host stdin goes in. What is run is taken from the definition, so a definition that names its program wrongly fails here. Nothing here needs a host or a
 * model, and nothing in it depends on the OS: the same code runs wherever the release was built.
 *
 * <p>There is one way a definition's command reaches the hook client: the installation's own ({@code HookClientCommand#hookProgram}), the {@code java} of the JDK with the hook client's
 * options and jar as the class path, the client's main class, and the installation's two options: {@code --server-file=} (where the server announces its address) and {@code --jar=}
 * (the installed Sennard jar, which a session start runs when no live server is announced). No launcher stands in between and no PATH is read. The server's port is the operating system's
 * choice at each start, so a definition holds none and this test chooses none: the server is started the way the server tests start it (port 0), and its real port is announced to the
 * client in an address file written by {@link ServerAddress#writeAtomically} for this process (its pid and its start, so that the client takes it for live), as the real server announces
 * itself. The stand-in servers (a refusing one, a silent one) are announced the same way, on the port the operating system gave them.
 *
 * <p>The version the definitions run holds the hook client's jar and no application jar ({@link InstalledHookCommand#hookClientOnly}): a session start that finds no server asks
 * the installed jar to start one, which is not there, so no case can start a server (and none reaches an installation of the PC). That is the guidance's case.
 *
 * <p>No case asserts a time. What the server was not reached with, what was printed and the exit code are what the cases look at.
 *
 * <p>Case table (host, event, server, expected):
 * <pre>
 * claude  SessionStart (exec form)        up, Proceed          -> exit 0, stdout {}
 * claude  SessionStart                    up, AddContext       -> exit 0, additionalContext on stdout, the server saw the session
 * claude  SessionStart                    down (no address file, an address file of another process start, a port nobody listens on)
 *                                                              -> exit 2, the guidance on stderr (the start of a server finds no installed jar)
 * claude  SessionStart                    input not a JSON object -> exit 2, the guidance on stderr, the server was never called (the client refuses it as the server would)
 * claude  SessionStart                    answers 400          -> exit 2, the guidance on stderr (a status above 299 is no answer)
 * claude  SessionStart/Stop wake          env attended unset/0/true -> exit 0, no request; 1 + a message -> exit 2, message on stderr
 * claude  SessionStart wake               down                 -> exit 0, nothing printed
 * claude  PreToolUse                      down or refused      -> exit 2 with the failure code on stderr
 * codex   SessionStart (command string)   up, AddContext       -> exit 0, additionalContext on stdout
 * codex   SessionStart                    down                 -> exit 0, the guidance on stdout
 * codex   PreToolUse continuity           down or refused      -> exit 2 with the failure code on stderr
 * codex   SessionEnd                      down                 -> exit 1, "Sennard hook failed: SERVER_NOT_REACHABLE" on stderr
 * codex   SessionEnd                      listens, no answer   -> the definition's --timeout=1 is what the client's reader reads; the client ends by its read timeout
 *                                                                (SERVER_NOT_REACHABLE SocketTimeoutException), closing the connection itself; exit 1
 * agy     PreToolUse                      up, Proceed / Refuse -> allow / deny with the reason
 * agy     PreToolUse                      down                 -> exit 0, deny with the failure code
 * agy     PreToolUse                      input not a JSON object -> exit 0, deny, the server never called
 * agy     PreToolUse (the gate)           the domain fails: 409 (lost first creation), another 4xx, 503, 500 -> exit 0, deny; the server answered it 200 with the catalog sentence
 *                                                                and the code of the refusal (HookPipeline), the client only passed it on
 * agy     PreToolUse (the gate)           something that is not the pipeline answers 400/404/409/500/503 -> exit 0, deny, the client's own reason with SERVER_REFUSED and the status
 * agy     PreToolUse (the gate)           listens, no answer   -> exit 0, deny with the read-timeout reason
 * agy     PostToolUse (no gate)           the domain fails with 409 -> exit 1, nothing on stdout, SERVER_REFUSED 409 on stderr
 * agy     SessionStart                    down                 -> exit 0, injectSteps with the guidance
 * agy     Stop, PostToolUse               down                 -> exit 1, nothing on stdout
 * gemini  SessionStart                    up, AddContext       -> exit 0, additionalContext on stdout
 * gemini  SessionStart                    down                 -> exit 0, systemMessage with the guidance
 * gemini  AfterTool                       down                 -> exit 1, nothing on stdout
 * gemini  BeforeTool (the gate)           up, Proceed          -> exit 0, {}
 * gemini  BeforeTool (the gate)           the domain fails 409 / 503, or a stand-in answers 400/404/500/503 -> exit 0, deny with the code
 * gemini  BeforeTool (the gate)           down                 -> exit 0, deny with the failure code
 * gemini  AfterTool (no gate)             a stand-in answers 400 -> exit 1, nothing on stdout, SERVER_REFUSED 400 on stderr
 * </pre>
 *
 * <p>Input: the recorded Antigravity and Claude inputs of {@code hook-fixtures}. No Gemini CLI input was ever recorded (docs/contributing/hooks.md section 4: not verified on a
 * machine with the Gemini CLI), so the Gemini cases run on a handwritten {@code BeforeTool} / {@code AfterTool} input ({@link #GEMINI_BEFORE_TOOL}); they judge the client's
 * answer for that host's definition, not that host's real input.
 */
class HookLauncherProcessTest {
    /** How an announced server is not there: the client finds no live server in the file, or finds one it cannot connect to. */
    enum Down {
        /** The server never announced itself in this state folder (or it ended and removed its file). */
        NO_FILE,
        /** A file of a server that is gone: the process of its pid has another start than the file says (a process that was given the pid later), so it is no live server. */
        STALE_FILE,
        /** The file names a live process and a port that nobody listens on. */
        PORT_REFUSED
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String ATTENDED = "CLAUDE_CODE_SESSION_ATTENDED";
    /** The recorded host inputs: the client passes the bytes on (reduced to the fields the server reads), so the OS a set was recorded on does not matter to it. */
    private static final String FIXTURES = "linux";
    private static final String GUIDANCE = "Sennard server is not reachable. Check with: java -jar <the Sennard jar in the install folder> status. After it is running, restart this session; tools do not attach later.";
    /** The {@code java} the definitions name: the JDK the tests run with (an installation names the one that ran its install). */
    private static final Path JAVA = ReleaseJarFixture.javaProgram();
    private static final String GEMINI_SESSION_START = "{\"session_id\":\"" + HookTestSupport.SESSION + "\",\"transcript_path\":\"\",\"cwd\":\"/work\","
            + "\"hook_event_name\":\"SessionStart\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"source\":\"startup\"}";

    private static final String GEMINI_BEFORE_TOOL = "{\"session_id\":\"" + HookTestSupport.SESSION + "\",\"transcript_path\":\"\",\"cwd\":\"/work\","
            + "\"hook_event_name\":\"BeforeTool\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"tool_name\":\"run_shell_command\",\"tool_input\":{\"command\":\"echo hi\"}}";
    private static final String GEMINI_AFTER_TOOL = GEMINI_BEFORE_TOOL.replace("BeforeTool", "AfterTool");

    private static RunningServer server;
    private static HookTestSupport.ScriptedHostEventHandler handler;
    /** The version the definitions run: the hook client's jar, no application jar. */
    private static InstalledVersion version;
    /** The address file that announces the running server. */
    private static Path liveFile;

    @TempDir
    static Path shared;

    @TempDir
    Path temporary;

    @BeforeAll
    static void start() throws Exception {
        version = InstalledHookCommand.hookClientOnly(shared.resolve("installation"));
        assertFalse(Files.exists(version.appJar()), "the installation names no application jar that exists: a session start cannot start a server");
        server = RunningServer.start(List.of(ServerFixtures.Beans.class, HookTestSupport.HandlerBeans.class, AnsweredWake.class));
        handler = server.bean(HookTestSupport.ScriptedHostEventHandler.class);
        // The server's own port, which the operating system gave it, announced as the server announces it: the file is read by the client for every call.
        liveFile = shared.resolve("live-state").resolve(ServerAddress.FILE_NAME);
        Files.createDirectories(liveFile.getParent());
        InstalledHookCommand.announceThisProcess(liveFile, server.port);
    }

    /** This server has no store, and these runs look only at what the client does with an answer. The claim itself is SessionLifecycleServerTest's. */
    static class AnsweredWake {
        /** How many wake hooks reached the server and were answered. */
        static final AtomicInteger CLAIMS = new AtomicInteger();

        @Bean
        @Primary
        WakeClaims answeredWakeClaims() {
            return new WakeClaims(null, null, null) {
                @Override
                public Optional<String> claim(String host, String sessionId) {
                    CLAIMS.incrementAndGet();
                    return Optional.of("{}");
                }
            };
        }
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

    /** A server that was never announced: the address file is not there. */
    private Path nobody() {
        return temporary.resolve("nobody").resolve(ServerAddress.FILE_NAME);
    }

    /** The address file of a server that is down in the way {@code down} says. */
    private Path down(Down down) throws IOException {
        ProcessHandle self = ProcessHandle.current();
        Instant started = self.info().startInstant().orElseThrow(() -> new IllegalStateException("The JVM does not tell when this test's process started"));
        return switch (down) {
            case NO_FILE -> nobody();
            case STALE_FILE -> written(down, new ServerAddress(closedPort(), self.pid(), started.minusSeconds(3600)));
            case PORT_REFUSED -> written(down, new ServerAddress(closedPort(), self.pid(), started));
        };
    }

    private Path written(Down down, ServerAddress address) throws IOException {
        Path file = temporary.resolve("down-" + down.name().toLowerCase(Locale.ROOT)).resolve(ServerAddress.FILE_NAME);
        Files.createDirectories(file.getParent());
        address.writeAtomically(file);
        return file;
    }

    /** Nothing listens here: a port this test just owned and closed. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** The address file of a stand-in server this test runs, at the port the operating system gave it. */
    private Path announce(int port) throws IOException {
        Path file = temporary.resolve("stand-in-" + port).resolve(ServerAddress.FILE_NAME);
        Files.createDirectories(file.getParent());
        InstalledHookCommand.announceThisProcess(file, port);
        return file;
    }

    /** A server that answers every request with this status and no body. */
    private static HttpServer refusing(int status) throws IOException {
        HttpServer refusing = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        refusing.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        refusing.start();
        return refusing;
    }

    /** What an installation generates: the programs by their absolute paths (the {@code java} with the client's words), told where the server announces itself. */
    private static HookInstallation installation(Path serverFile) {
        return InstalledHookCommand.installation(JAVA, version, serverFile);
    }

    /** The hook definition (the host's {@code hooks.json}) an installation generates for a host: the real adapter, this installation's programs. */
    private static JsonNode hooks(HostHookAdapter adapter, Path serverFile) {
        return adapter.registrationFiles(installation(serverFile)).stream().filter(file -> file.relativePath().getFileName().toString().equals("hooks.json")).findFirst()
                .map(HostRegistrationFile::content).orElseThrow(() -> new AssertionError("the adapter registers no hooks.json"));
    }

    private record Finished(int exit, byte[] stdout, String stderr) {
        JsonNode json() {
            return MAPPER.readTree(stdout);
        }

        String text() {
            return new String(stdout, StandardCharsets.UTF_8);
        }
    }

    /**
     * The places of the product are this test's, for a command that is started: no command of these cases reads an installation of the PC or its state (a session start would, if it
     * found a jar to run, and none exists), and the host's attended mark is left out unless the case sets it.
     */
    private void isolate(ProcessBuilder builder) {
        builder.environment().putAll(ReleaseJarFixture.isolated(temporary.resolve("home"),
                Map.of("SENNARD_INSTALL_ROOT", temporary.resolve("no-installation").toString(), "SENNARD_STATE_DIR", temporary.resolve("state").toString())));
        builder.environment().remove(ATTENDED);
    }

    /**
     * Starts a handler of a definition as the host does and waits for it. The bound is only there to stop a wedged process from holding the suite; it
     * decides nothing.
     *
     * @param attended the value of CLAUDE_CODE_SESSION_ATTENDED the host gives the hook process; null leaves it unset
     */
    private Finished run(JsonNode hookHandler, byte[] stdin, String attended) throws Exception {
        Path out = temporary.resolve("stdout");
        Path err = temporary.resolve("stderr");
        Path in = temporary.resolve("stdin");
        Files.write(in, stdin);
        ProcessBuilder builder = new ProcessBuilder(argv(hookHandler)).redirectInput(in.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
        isolate(builder);
        if (attended != null) {
            builder.environment().put(ATTENDED, attended);
        }
        Process process = builder.start();
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the hook process did not end: " + hookHandler);
        return new Finished(process.exitValue(), Files.readAllBytes(out), Files.readString(err, StandardCharsets.UTF_8));
    }

    private Finished run(JsonNode hookHandler, byte[] stdin) throws Exception {
        return run(hookHandler, stdin, null);
    }

    /**
     * The program and its arguments of a command hook, the way a host splits them and with no shell: an exec form is its {@code command} and
     * {@code args}; a command string is split at spaces, a word in double quotes (a path with a space) being one word.
     */
    private static List<String> argv(JsonNode hookHandler) {
        String command = hookHandler.path("command").stringValue();
        List<String> argv = new ArrayList<>();
        if (hookHandler.has("args")) {
            argv.add(command);
            hookHandler.path("args").forEach(argument -> argv.add(argument.stringValue()));
        } else {
            argv.addAll(InstalledHookCommand.split(command));
        }
        assertEquals(JAVA.toString(), argv.get(0), "the one program a definition starts");
        return argv;
    }

    private static JsonNode handlerOf(JsonNode file, String event, int index) {
        JsonNode group = file.path("hooks").path(event).path(index);
        return group.path("hooks").path(0);
    }

    private static JsonNode agyHandler(JsonNode file, String bundle, String event) {
        JsonNode entry = file.path(bundle).path(event).path(0);
        return entry.has("hooks") ? entry.path("hooks").path(0) : entry;
    }

    /** The wake hook registered on an event: the one handler with asyncRewake. */
    private static JsonNode claudeWake(JsonNode file, String event) {
        for (JsonNode entry : file.path("hooks").path(event)) {
            JsonNode hookHandler = entry.path("hooks").path(0);
            if (hookHandler.path("asyncRewake").asBoolean(false)) {
                return hookHandler;
            }
        }
        throw new AssertionError("no wake hook on " + event);
    }

    private static byte[] stdin(String host, String event) {
        return HookTestSupport.fixture(FIXTURES, host, event);
    }

    private static HostEvent onlyCall() {
        assertEquals(1, handler.calls.size(), handler.calls.toString());
        return handler.calls.peek().event();
    }

    // ---------------------------------------------------------------------------------------------------------------------------- Claude

    @Test
    void claudeToolGateDeniesWhenTheServerIsDownOrRefuses() throws Exception {
        HttpServer refusing = refusing(503);
        try {
            byte[] input = stdin("claude", "PreToolUse");
            JsonNode down = hooks(HookAdapters.claude(), nobody());
            JsonNode refused = hooks(HookAdapters.claude(), announce(refusing.getAddress().getPort()));
            for (int gate : List.of(0, 1, 2)) {
                assertBlockedOnStderr(run(handlerOf(down, "PreToolUse", gate), input), "SERVER_NOT_REACHABLE ConnectException");
                assertBlockedOnStderr(run(handlerOf(refused, "PreToolUse", gate), input), "SERVER_REFUSED 503");
            }
        } finally {
            refusing.stop(0);
        }
    }

    @Test
    void codexToolGateDeniesWhenTheServerIsDownOrRefuses() throws Exception {
        HttpServer refusing = refusing(503);
        try {
            byte[] input = stdin("codex", "PreToolUse");
            JsonNode down = hooks(HookAdapters.codex(), nobody());
            JsonNode refused = hooks(HookAdapters.codex(), announce(refusing.getAddress().getPort()));
            for (int gate : List.of(1, 2)) {
                assertCodexDenied(run(handlerOf(down, "PreToolUse", gate), input), "SERVER_NOT_REACHABLE ConnectException");
                assertCodexDenied(run(handlerOf(refused, "PreToolUse", gate), input), "SERVER_REFUSED 503");
            }
        } finally {
            refusing.stop(0);
        }
    }

    private static void assertBlockedOnStderr(Finished finished, String reason) {
        assertEquals(2, finished.exit(), finished.stderr());
        assertEquals("", finished.text());
        assertTrue(finished.stderr().contains("(" + reason + ")"), finished.stderr());
    }

    private static void assertCodexDenied(Finished finished, String reason) {
        assertEquals(0, finished.exit(), finished.stderr());
        assertEquals("", finished.stderr());
        JsonNode output = finished.json().path("hookSpecificOutput");
        assertEquals("PreToolUse", output.path("hookEventName").stringValue(), finished.text());
        assertEquals("deny", output.path("permissionDecision").stringValue(), finished.text());
        assertTrue(output.path("permissionDecisionReason").stringValue().contains(reason), finished.text());
    }

    private static void assertDenied(Finished finished, String reason) {
        assertEquals(0, finished.exit(), finished.stderr());
        assertEquals("", finished.stderr());
        assertEquals("deny", finished.json().path("decision").stringValue(), finished.text());
        assertTrue(finished.json().path("reason").stringValue().contains(reason), finished.text());
    }

    @Test
    void claudeSessionStartExecHookPassesTheServersAnswerThroughWhenTheServerIsUp() throws Exception {
        JsonNode file = hooks(HookAdapters.claude(), liveFile);

        Finished finished = run(handlerOf(file, "SessionStart", 0), stdin("claude", "SessionStart"));

        assertEquals(0, finished.exit(), finished.stderr());
        assertEquals("{}", finished.text().strip());
        assertEquals(HookTestSupport.SESSION, onlyCall().context().sessionId());
    }

    @Test
    void claudeSessionStartExecHookPrintsAnAnswerThatHoldsContext() throws Exception {
        handler.next.set(new HookVerdict.AddContext(List.of(new ContextBlock("hello", false))));
        JsonNode file = hooks(HookAdapters.claude(), liveFile);

        Finished finished = run(handlerOf(file, "SessionStart", 0), stdin("claude", "SessionStart"));

        assertEquals(0, finished.exit(), finished.stderr());
        assertEquals("hello", finished.json().path("hookSpecificOutput").path("additionalContext").stringValue());
    }

    /** Design 4.2: shown to the user, exit 2, no blocking. */
    @ParameterizedTest
    @EnumSource(Down.class)
    void claudeSessionStartSaysTheServerIsDownOnStderrAndExitsTwo(Down down) throws Exception {
        JsonNode file = hooks(HookAdapters.claude(), down(down));

        Finished finished = run(handlerOf(file, "SessionStart", 0), stdin("claude", "SessionStart"));

        assertEquals(2, finished.exit(), finished.stderr());
        assertTrue(finished.stderr().contains(GUIDANCE), finished.stderr());
        assertEquals("", finished.text());
    }

    /** An input the server would refuse (not a JSON object) is refused by the client before anything is sent: no answer, the failure answer, and the server never called. */
    @Test
    void claudeSessionStartTreatsAnInputThatIsNotAJsonObjectAsNoAnswerAndNeverCallsTheServer() throws Exception {
        JsonNode file = hooks(HookAdapters.claude(), liveFile);

        Finished finished = run(handlerOf(file, "SessionStart", 0), "this is not JSON".getBytes(StandardCharsets.UTF_8));

        assertEquals(2, finished.exit(), finished.stderr());
        assertTrue(finished.stderr().contains(GUIDANCE), finished.stderr());
        assertEquals(List.of(), List.copyOf(handler.calls));
    }

    /** A status above 299 is no answer: the server refusing the call is as down as the server not being there. */
    @Test
    void claudeSessionStartTreatsAServerThatAnswersWithARefusalAsDown() throws Exception {
        HttpServer refusing = refusing(400);
        try {
            JsonNode file = hooks(HookAdapters.claude(), announce(refusing.getAddress().getPort()));

            Finished finished = run(handlerOf(file, "SessionStart", 0), stdin("claude", "SessionStart"));

            assertEquals(2, finished.exit(), finished.stderr());
            assertTrue(finished.stderr().contains(GUIDANCE), finished.stderr());
            assertEquals("", finished.text());
        } finally {
            refusing.stop(0);
        }
    }

    @Test
    void claudeWakeExitsTwoOnAnAnswerSoTheSessionWakesAndZeroOnAFailureSoItDoesNot() throws Exception {
        JsonNode live = claudeWake(hooks(HookAdapters.claude(), liveFile), "SessionStart");
        Finished answered = run(live, stdin("claude", "SessionStart"), "1");
        assertEquals(2, answered.exit(), answered.stderr());
        assertTrue(answered.stderr().contains("{}"), "the answer goes to stderr, which is what the host wakes the session with: " + answered.stderr());
        assertEquals("", answered.text());

        JsonNode dead = claudeWake(hooks(HookAdapters.claude(), nobody()), "SessionStart");
        Finished unanswered = run(dead, stdin("claude", "SessionStart"), "1");
        assertEquals(0, unanswered.exit(), unanswered.stderr());
        assertEquals("", unanswered.text());
        assertEquals("", unanswered.stderr());
    }

    /**
     * {@code claude -p} waits for an asyncRewake SessionStart hook before it starts, and a Stop wake keeps the process from
     * ending. Claude Code gives hooks CLAUDE_CODE_SESSION_ATTENDED, 1 only in an interactive session (measured with 2.1.319; the variable is not
     * in Claude Code's documentation). The wake waits on the server only for exactly 1; any other value or none ends at once without a request.
     */
    @Test
    void claudeWakeWaitsOnTheServerOnlyInASessionClaudeCodeMarksAsAttended() throws Exception {
        JsonNode file = hooks(HookAdapters.claude(), liveFile);
        for (String event : List.of("SessionStart", "Stop")) {
            JsonNode wake = claudeWake(file, event);
            for (String attended : Arrays.asList(null, "0", "true")) {
                int before = AnsweredWake.CLAIMS.get();
                Finished skipped = run(wake, stdin("claude", event), attended);
                assertEquals(0, skipped.exit(), event + " with " + attended + ": " + skipped.stderr());
                assertEquals(before, AnsweredWake.CLAIMS.get(), event + " with " + attended + " reached the server");
            }
            int before = AnsweredWake.CLAIMS.get();
            Finished woken = run(wake, stdin("claude", event), "1");
            assertEquals(2, woken.exit(), event + ": " + woken.stderr());
            assertEquals(before + 1, AnsweredWake.CLAIMS.get(), event + " with 1 did not reach the server");
        }
    }

    /** The unattached client must not wait for stdin or make a request; an attended one must actually wait until its receiver answers. */
    @Test
    void claudeWakeChecksAttendanceBeforeReadingAndWaitsForAnAnswer() throws Exception {
        for (String event : List.of("SessionStart", "Stop")) {
            CountDownLatch received = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            receiver.createContext("/hooks/v1/claude/" + event + "/wake", exchange -> {
                try (exchange) {
                    received.countDown();
                    exchange.getRequestBody().readAllBytes();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test receiver was not released");
                    byte[] body = "wake-test".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            });
            receiver.start();
            Process process = null;
            try {
                JsonNode waiting = claudeWake(hooks(HookAdapters.claude(), announce(receiver.getAddress().getPort())), event);
                for (String attended : Arrays.asList(null, "0")) {
                    ProcessBuilder unattended = new ProcessBuilder(argv(waiting)).redirectErrorStream(true);
                    isolate(unattended);
                    if (attended != null) unattended.environment().put(ATTENDED, attended);
                    Process skipped = unattended.start();
                    try {
                        assertTrue(skipped.waitFor(10, TimeUnit.SECONDS), event + " waited for stdin with " + attended);
                        assertEquals(0, skipped.exitValue());
                        assertEquals(1, received.getCount(), "unattended wake reached HTTP");
                    } finally {
                        if (skipped.isAlive()) skipped.destroyForcibly().waitFor();
                    }
                }
                ProcessBuilder builder = new ProcessBuilder(argv(waiting));
                isolate(builder);
                builder.environment().put(ATTENDED, "1");
                process = builder.start();
                process.getOutputStream().write(stdin("claude", event));
                process.getOutputStream().close();
                assertTrue(received.await(10, TimeUnit.SECONDS), event + " did not reach the receiver");
                assertTrue(process.isAlive(), event + " did not wait for a response");
                release.countDown();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), event + " did not exit after response");
                assertEquals(2, process.exitValue());
                assertTrue(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).contains("wake-test"));
            } finally {
                release.countDown();
                if (process != null && process.isAlive()) process.destroyForcibly().waitFor();
                receiver.stop(0);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------- Codex

    @Test
    void codexSessionStartCommandPrintsTheServersAnswerOnStdout() throws Exception {
        handler.next.set(new HookVerdict.AddContext(List.of(new ContextBlock("hello", false))));
        JsonNode file = hooks(HookAdapters.codex(), liveFile);

        Finished finished = run(handlerOf(file, "SessionStart", 0), stdin("codex", "SessionStart"));

        assertEquals(0, finished.exit(), finished.stderr());
        assertEquals("hello", finished.json().path("hookSpecificOutput").path("additionalContext").stringValue());
        assertEquals(HookTestSupport.SESSION, onlyCall().context().sessionId());
    }

    /** Design 4.2: this host passes a failed hook, and the guidance is the hook's stdout. */
    @Test
    void codexSessionStartPrintsTheGuidanceOnStdoutWhenTheServerIsDown() throws Exception {
        JsonNode file = hooks(HookAdapters.codex(), nobody());

        Finished finished = run(handlerOf(file, "SessionStart", 0), stdin("codex", "SessionStart"));

        assertEquals(0, finished.exit(), finished.stderr());
        assertEquals(GUIDANCE, finished.text().strip());
    }

    @Test
    void codexSessionEndFailsWithAReasonCodeOnStderrAndNothingOnStdoutWhenTheServerIsDown() throws Exception {
        JsonNode file = hooks(HookAdapters.codex(), nobody());

        Finished finished = run(handlerOf(file, "SessionEnd", 0), stdin("codex", "SessionEnd"));

        assertEquals(1, finished.exit(), finished.stderr());
        assertEquals("", finished.text());
        assertTrue(finished.stderr().startsWith("Sennard hook failed: SERVER_NOT_REACHABLE"), finished.stderr());
    }

    /**
     * Codex gives this event one second, so the definition's {@code --timeout=1} must be what the client waits. The words a host starts are read by the client's own
     * reader (the one the client program uses), which says one second; a server that accepts the request and never answers then shows that the client
     * gives up by itself: it ends by its read timeout (the reason's detail names it) and closes the connection, and the server's side never sees the request answered. No time
     * is compared: that the wait is the definition's and not the default's is read from what the client was given.
     */
    @Test
    void codexSessionEndGivesUpOnAServerThatDoesNotAnswerByTheReadTimeoutTheDefinitionSets() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            JsonNode file = hooks(HookAdapters.codex(), announce(silent.getLocalPort()));
            List<String> words = argv(handlerOf(file, "SessionEnd", 0));
            // Everything after the client's main class is what the client reads: the installation's options and the call's.
            List<String> clientWords = words.subList(words.indexOf(HookClientMain.class.getName()) + 1, words.size());
            assertEquals(1, HookClientArguments.parse(clientWords).call().timeoutSeconds(), "the definition gives the client Codex's one second: " + words);
            AtomicBoolean accepted = new AtomicBoolean();
            Thread listener = Thread.ofPlatform().daemon().start(() -> {
                try (Socket client = silent.accept()) {
                    accepted.set(true);
                    // The server never writes an answer; this ends only when the client closes its side (or resets it).
                    client.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                } catch (IOException closed) {
                    // a reset is the client ending the connection too
                }
            });

            Finished finished = run(handlerOf(file, "SessionEnd", 0), stdin("codex", "SessionEnd"));
            listener.join(TimeUnit.SECONDS.toMillis(30));

            assertEquals(1, finished.exit(), finished.stderr());
            assertTrue(accepted.get(), "the client reached the server that never answers");
            assertTrue(finished.stderr().startsWith("Sennard hook failed: SERVER_NOT_REACHABLE SocketTimeoutException"), "the client ended by its read timeout: " + finished.stderr());
            assertFalse(listener.isAlive(), "the client ended the connection itself: the server's read of it ended");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------- Antigravity

    @Test
    void antigravityToolHookAllowsWhenTheServerAnswersAllowAndDeniesWhenItDenies() throws Exception {
        JsonNode file = hooks(HookAdapters.antigravity(), liveFile);
        JsonNode preToolUse = agyHandler(file, "sennard-tool-boundary", "PreToolUse");

        Finished allowed = run(preToolUse, stdin("agy", "PreToolUse"));
        assertEquals(0, allowed.exit(), allowed.stderr());
        assertEquals("allow", allowed.json().path("decision").stringValue());
        assertEquals(HookTestSupport.SESSION, onlyCall().context().sessionId());

        handler.next.set(new HookVerdict.Refuse("no"));
        Finished denied = run(preToolUse, stdin("agy", "PreToolUse"));
        assertEquals("deny", denied.json().path("decision").stringValue());
        assertEquals("no", denied.json().path("reason").stringValue());
    }

    @Test
    void antigravityToolHookDeniesWhenTheServerIsDown() throws Exception {
        JsonNode file = hooks(HookAdapters.antigravity(), nobody());

        Finished finished = run(agyHandler(file, "sennard-tool-boundary", "PreToolUse"), stdin("agy", "PreToolUse"));

        assertDenied(finished, "(SERVER_NOT_REACHABLE ConnectException)");
    }

    @Test
    void antigravityNativePeerCommandDeniesWhenTheServerIsAbsent() throws Exception {
        JsonNode file = hooks(HookAdapters.antigravity(), nobody());
        JsonNode nativePeer = file.path("sennard-tool-boundary").path("PreToolUse").path(1);
        assertEquals("^(default_api:)?send_message$", nativePeer.path("matcher").stringValue());
        JsonNode hook = nativePeer.path("hooks").path(0);
        assertTrue(hook.path("command").stringValue().contains(" --module=native-peer "));
        byte[] request = """
                {"conversationId":"native-peer-fixture","toolCall":{"name":"send_message","args":{"Recipient":"child","Message":"fixture"}}}
                """.getBytes(StandardCharsets.UTF_8);

        Finished finished = run(hook, request);

        assertEquals(0, finished.exit(), finished.stderr());
        assertEquals("", finished.stderr());
        assertEquals("deny", finished.json().path("decision").stringValue(), finished.text());
        assertTrue(finished.json().path("reason").stringValue().contains("SERVER_NOT_REACHABLE"), finished.text());
    }

    @Test
    void antigravityToolHookDeniesWhenTheInputIsNotAJsonObject() throws Exception {
        JsonNode file = hooks(HookAdapters.antigravity(), liveFile);

        Finished finished = run(agyHandler(file, "sennard-tool-boundary", "PreToolUse"), "this is not JSON".getBytes(StandardCharsets.UTF_8));

        assertDenied(finished, "(HOOK_BODY_NOT_A_JSON_OBJECT)");
        assertEquals(List.of(), List.copyOf(handler.calls), "the client refused the input: the server was never called");
    }

    /**
     * The gate of this host (the definition's {@code --on-refusal=deny-decision}): something that answers a status of 400 or above and is not the Sennard pipeline (which would have
     * answered its own refusal, see the next cases) still stops the tool. The client says the status as the code; the server is a stand-in only here, because the server that
     * answers 400 on its own (a filter before the controller) is not one this suite can make misbehave.
     */
    @Test
    void antigravityToolHookDeniesWhenTheServerAnswersWithARefusalOfFourHundredOrAbove() throws Exception {
        for (int status : List.of(400, 404, 409, 500, 503)) {
            HttpServer refusing = refusing(status);
            try {
                JsonNode file = hooks(HookAdapters.antigravity(), announce(refusing.getAddress().getPort()));

                Finished finished = run(agyHandler(file, "sennard-tool-boundary", "PreToolUse"), stdin("agy", "PreToolUse"));

                assertEquals(0, finished.exit(), finished.stderr());
                assertEquals("", finished.stderr());
                assertEquals("deny", finished.json().path("decision").stringValue(), "status " + status + ": " + finished.text());
                assertTrue(finished.json().path("reason").stringValue().contains("(SERVER_REFUSED " + status + ")"), finished.text());
            } finally {
                refusing.stop(0);
            }
        }
    }


    /**
     * The decision of 2026-11-16 for the gate PreToolUse of the host: a call the server refused (4xx or 5xx) stops the tool. The pipeline turns what the domain throws into the
     * refusal of the tool and answers it 200 in the host's own JSON, so the client prints the server's sentence (the catalog's {@code hooks.gateRefused}) with the code.
     */
    @Test
    void antigravityToolHookDeniesWithTheServersSentenceAndCodeWhenTheDomainFails() throws Exception {
        JsonNode file = hooks(HookAdapters.antigravity(), liveFile);
        for (HookTestSupport.FailingDomain failing : HookTestSupport.failingDomains()) {
            handler.failure.set(failing.failure());

            Finished finished = run(agyHandler(file, "sennard-tool-boundary", "PreToolUse"), stdin("agy", "PreToolUse"));

            assertEquals(0, finished.exit(), failing.situation() + ": " + finished.stderr());
            assertEquals("deny", finished.json().path("decision").stringValue(), failing.situation() + ": " + finished.text());
            String reason = finished.json().path("reason").stringValue();
            assertTrue(reason.startsWith(HookTestSupport.GATE_REFUSED + failing.code() + ")"), failing.situation() + ": " + reason);
        }
    }

    /** The server accepts the request and never answers, so the launcher denies on its own read timeout. */
    @Test
    void antigravityToolHookDeniesWhenTheServerDoesNotAnswerInTime() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            JsonNode file = hooks(HookAdapters.antigravity(), announce(silent.getLocalPort()));
            Thread listener = Thread.ofPlatform().daemon().start(() -> {
                try (Socket client = silent.accept()) {
                    client.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                } catch (IOException closed) {
                    // the client ended the connection (its read timeout): the end of this listener
                }
            });

            Finished finished = run(agyHandler(file, "sennard-tool-boundary", "PreToolUse"), stdin("agy", "PreToolUse"));
            listener.join(TimeUnit.SECONDS.toMillis(30));

            assertDenied(finished, "(SERVER_NOT_REACHABLE SocketTimeoutException)");
        }
    }

    /** Not a gate: the same failure of the domain on a hook that cannot stop a tool stays a failed hook, which the host goes on from. */
    @Test
    void antigravityPostToolUseStaysAFailedHookWhenTheDomainFailsWithAConflict() throws Exception {
        JsonNode file = hooks(HookAdapters.antigravity(), liveFile);
        handler.failure.set(new HookRejected(HookRejection.HOOK_HANDLER_CONFLICT, "STORE_UNIQUE_VIOLATION", new IllegalStateException("lost")));

        Finished finished = run(agyHandler(file, "sennard-tool-boundary", "PostToolUse"), stdin("agy", "PostToolUse"));

        assertEquals(1, finished.exit(), finished.stderr());
        assertEquals("", finished.text());
        assertEquals("Sennard hook failed: SERVER_REFUSED 409", finished.stderr());
    }

    @Test
    void antigravitySessionStartInjectsTheGuidanceWhenTheServerIsDown() throws Exception {
        JsonNode file = hooks(HookAdapters.antigravity(), nobody());

        Finished finished = run(agyHandler(file, "sennard-session-lifecycle", "SessionStart"), stdin("agy", "SessionStart"));

        assertEquals(0, finished.exit(), finished.stderr());
        assertEquals(MAPPER.readTree("{\"injectSteps\":[{\"systemMessage\":{\"systemMessage\":\"" + GUIDANCE + "\"}}]}"), finished.json());
    }

    /** The contract of a hook with no failure answer: the client fails with a reason code on stderr and nothing on stdout, which this host takes as a failed hook and goes on. */
    @Test
    void antigravityHooksWithNoFailureAnswerFailWithEmptyStdoutWhenTheServerIsDown() throws Exception {
        JsonNode file = hooks(HookAdapters.antigravity(), nobody());

        Finished stop = run(agyHandler(file, "sennard-session-lifecycle", "Stop"), stdin("agy", "Stop"));
        Finished postToolUse = run(agyHandler(file, "sennard-tool-boundary", "PostToolUse"), stdin("agy", "PostToolUse"));

        for (Finished finished : List.of(stop, postToolUse)) {
            assertEquals(1, finished.exit(), finished.stderr());
            assertEquals("", finished.text());
            assertTrue(finished.stderr().startsWith("Sennard hook failed: SERVER_NOT_REACHABLE"), finished.stderr());
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------- Gemini

    @Test
    @Tag("business-db")
    void geminiSessionStartPrintsAnAnswerThatHoldsContextWhenTheServerIsUp() throws Exception {
        try (RunningServer ready = SessionServerSupport.started(List.of(ServerFixtures.Beans.class, GeminiHookServerTest.Verdicts.class))) {
            var readyHandler = ready.bean(HookTestSupport.ScriptedHostEventHandler.class);
            readyHandler.next.set(new HookVerdict.AddContext(List.of(new ContextBlock("hello", false))));
            Path readyAddress = temporary.resolve(ServerAddress.FILE_NAME);
            InstalledHookCommand.announceThisProcess(readyAddress, ready.port);
            JsonNode file = hooks(HookAdapters.gemini(), readyAddress);

            Finished finished = run(handlerOf(file, "SessionStart", 0), GEMINI_SESSION_START.getBytes(StandardCharsets.UTF_8));

            assertEquals(0, finished.exit(), finished.stderr());
            assertEquals("hello", finished.json().path("hookSpecificOutput").path("additionalContext").stringValue());
            assertEquals(1, readyHandler.calls.size(), readyHandler.calls.toString());
            assertEquals(HookTestSupport.SESSION, readyHandler.calls.getFirst().event().context().sessionId());
        }
    }

    @Test
    void geminiSessionStartShowsTheGuidanceAsASystemMessageWhenTheServerIsDown() throws Exception {
        JsonNode file = hooks(HookAdapters.gemini(), nobody());

        Finished finished = run(handlerOf(file, "SessionStart", 0), GEMINI_SESSION_START.getBytes(StandardCharsets.UTF_8));

        assertEquals(0, finished.exit(), finished.stderr());
        assertEquals(MAPPER.readTree("{\"systemMessage\":\"" + GUIDANCE + "\"}"), finished.json());
    }

    @Test
    void geminiHooksOtherThanTheSessionStartFailWithEmptyStdoutWhenTheServerIsDown() throws Exception {
        JsonNode file = hooks(HookAdapters.gemini(), nobody());

        Finished finished = run(handlerOf(file, "AfterTool", 0), GEMINI_SESSION_START.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, finished.exit(), finished.stderr());
        assertEquals("", finished.text());
    }

    @Test
    void geminiBeforeToolPrintsTheServersAnswerWhenTheServerIsUp() throws Exception {
        JsonNode file = hooks(HookAdapters.gemini(), liveFile);

        Finished finished = run(handlerOf(file, "BeforeTool", 0), GEMINI_BEFORE_TOOL.getBytes(StandardCharsets.UTF_8));

        assertEquals(0, finished.exit(), finished.stderr());
        assertTrue(finished.json().path("decision").isMissingNode(), "nothing is refused: " + finished.text());
        assertEquals(HookTestSupport.SESSION, onlyCall().context().sessionId());
    }

    /** The gate of this host (BeforeTool): the server's sentence and the code of the refusal when the domain fails, for the four kinds of failure. */
    @Test
    void geminiBeforeToolDeniesWithTheServersSentenceAndCodeWhenTheDomainFails() throws Exception {
        JsonNode file = hooks(HookAdapters.gemini(), liveFile);
        for (HookTestSupport.FailingDomain failing : HookTestSupport.failingDomains()) {
            handler.failure.set(failing.failure());

            Finished finished = run(handlerOf(file, "BeforeTool", 0), GEMINI_BEFORE_TOOL.getBytes(StandardCharsets.UTF_8));

            assertEquals(0, finished.exit(), failing.situation() + ": " + finished.stderr());
            assertEquals("deny", finished.json().path("decision").stringValue(), failing.situation() + ": " + finished.text());
            assertTrue(finished.json().path("reason").stringValue().startsWith(HookTestSupport.GATE_REFUSED + failing.code() + ")"), failing.situation() + ": " + finished.text());
        }
    }

    @Test
    void geminiBeforeToolDeniesWhenTheServerAnswersWithARefusalOfFourHundredOrAbove() throws Exception {
        for (int status : List.of(400, 404, 409, 500, 503)) {
            HttpServer refusing = refusing(status);
            try {
                JsonNode file = hooks(HookAdapters.gemini(), announce(refusing.getAddress().getPort()));

                Finished finished = run(handlerOf(file, "BeforeTool", 0), GEMINI_BEFORE_TOOL.getBytes(StandardCharsets.UTF_8));

                assertEquals(0, finished.exit(), finished.stderr());
                assertEquals("deny", finished.json().path("decision").stringValue(), "status " + status + ": " + finished.text());
                assertTrue(finished.json().path("reason").stringValue().contains("(SERVER_REFUSED " + status + ")"), finished.text());
            } finally {
                refusing.stop(0);
            }
        }
    }

    @Test
    void geminiBeforeToolDeniesWhenTheServerIsDown() throws Exception {
        JsonNode file = hooks(HookAdapters.gemini(), nobody());

        Finished finished = run(handlerOf(file, "BeforeTool", 0), GEMINI_BEFORE_TOOL.getBytes(StandardCharsets.UTF_8));

        assertDenied(finished, "(SERVER_NOT_REACHABLE ConnectException)");
    }

    /** Not a gate: a hook that cannot stop a tool stays a failed hook on a refusal. */
    @Test
    void geminiAfterToolStaysAFailedHookWhenTheServerAnswersWithARefusal() throws Exception {
        HttpServer refusing = refusing(400);
        try {
            JsonNode file = hooks(HookAdapters.gemini(), announce(refusing.getAddress().getPort()));

            Finished finished = run(handlerOf(file, "AfterTool", 0), GEMINI_AFTER_TOOL.getBytes(StandardCharsets.UTF_8));

            assertEquals(1, finished.exit(), finished.stderr());
            assertEquals("", finished.text());
            assertEquals("Sennard hook failed: SERVER_REFUSED 400", finished.stderr());
        } finally {
            refusing.stop(0);
        }
    }
}
