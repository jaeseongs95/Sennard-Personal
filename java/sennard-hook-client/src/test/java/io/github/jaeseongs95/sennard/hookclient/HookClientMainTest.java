// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.hookclient.HookClientCall.FailureAnswer;
import io.github.jaeseongs95.sennard.hookclient.HookNoAnswer.Reason;
import io.github.jaeseongs95.sennard.hookclient.WhatTheHostSees.Result;

/**
 * The entry point of the hook client without its process ({@link HookClientMain#run}): the words a host's command gives, the environment, and in-memory streams, against a
 * {@link HookServerStub} on a real loopback socket that is announced in a real address file ({@link ServerAddress}). The process itself is {@link HookClientProcessTest}'s. Case table
 * (input: the words after the class name, the environment, stdin; expected: exit code, stdout, stderr and what the server received):
 * <ol>
 * <li>{@code hook gemini AfterTool --server-file=<file of the stub>}: the server's answer on stdout, exit 0, nothing on stderr; the server received a POST to
 * {@code /hooks/v1/gemini/AfterTool} of the reduced input and, with {@code TZ=Asia/Seoul} in the environment, {@code X-Sennard-Time-Zone: Asia/Seoul}</li>
 * <li>the server is not there, each of the six failure answers by {@code --on-failure=<option>} (FAIL when none): what the host sees of that answer, with FAIL's stderr
 * {@code Sennard hook failed: SERVER_NOT_REACHABLE ConnectException}, whether the file is not there, the file's port refuses the connection, or the file is of a process that is gone;
 * a server that refuses (status 503) is {@code SERVER_REFUSED 503}</li>
 * <li>a command line that cannot be understood (no operand, no {@code --server-file}, the {@code --port} that no longer exists, a start with no {@code --jar}, an unknown failure answer,
 * an unknown option, an option twice): exit 2, nothing on stdout, stderr {@code Sennard hook failed: HOOK_OPTION_INVALID <problem code> <option>=<value>}, stdin not read, nothing sent</li>
 * <li>{@code --start-server=<seconds>} with no live server: the Sennard jar is run ({@code java -jar <jar> start --within=<seconds>}); a jar that is not a jar is a start that failed
 * with the exit code of the java that could not open it, {@code SERVER_NOT_STARTED the start ended with exit code 1}; a call without {@code --start-server} never reaches that</li>
 * <li>{@code --warm=<label>=<file>}: the file is read as that host's input and sends nothing, then the hook itself goes on (one request); a label with no fields and a value that is not
 * {@code <label>=<file>} are {@code HOOK_OPTION_INVALID} with exit 2 and nothing sent</li>
 * <li>{@code --only-if-env}, {@code --wake}: read from the words as the client reads a call (a condition that does not hold is exit 0 and no request; a wake is the message on stderr and
 * exit 2)</li>
 * </ol>
 */
class HookClientMainTest {
    private static final String GEMINI_INPUT = "{\"session_id\":\"g\",\"hook_event_name\":\"AfterTool\",\"tool_name\":\"t\",\"tool_response\":{\"llmContent\":\"c\",\"returnDisplay\":\"d\"}}";
    private static final String GEMINI_REDUCED = "{\"session_id\":\"g\",\"tool_name\":\"t\",\"tool_response\":{\"llmContent\":\"c\"}}";

    @TempDir
    Path temporary;

    private HookServerStub stub;
    /** The file where the stub is announced as the server. */
    private Path serverFile;

    @BeforeEach
    void startStub() throws IOException {
        stub = new HookServerStub();
        serverFile = temporary.resolve(ServerAddress.FILE_NAME);
        stub.announceIn(serverFile);
    }

    @AfterEach
    void stopStub() {
        stub.close();
    }

    private static List<String> words(String... words) {
        return List.of(words);
    }

    private static Result run(List<String> words, Map<String, String> environment, String stdin) throws IOException {
        return run(words, environment, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
    }

    private static Result run(List<String> words, Map<String, String> environment, ByteArrayInputStream stdin) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = HookClientMain.run(words, environment, ProcessHandle.current(), stdin, out, err);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static List<String> hook(String label, String event, Path serverFile, String... options) {
        List<String> words = new ArrayList<>(List.of("hook", label, event, "--server-file=" + serverFile));
        words.addAll(List.of(options));
        return words;
    }

    private List<String> hook(String label, String event, String... options) {
        return hook(label, event, serverFile, options);
    }

    /** A file that announces a server of this process at a port nobody listens on: the server is announced and its port refuses the connection. */
    private Path announcedButGone() throws IOException {
        Path file = temporary.resolve("gone.properties");
        HookServerStub.announce(HookServerStub.unusedPort(), file);
        return file;
    }

    @Test
    void aHookTheServerAnswersIsItsAnswerOnStdoutWithExitZeroAndTheReducedInputIsWhatTheServerGot() throws IOException {
        Result result = run(hook("gemini", "AfterTool"), Map.of("TZ", "Asia/Seoul"), GEMINI_INPUT);

        assertEquals(new Result(0, HookServerStub.ANSWER, ""), result);
        assertEquals(1, stub.received.size());
        HookServerStub.Received request = stub.received.get(0);
        assertEquals("POST", request.method());
        assertEquals("/hooks/v1/gemini/AfterTool", request.path());
        assertEquals(GEMINI_REDUCED, request.bodyText());
        assertEquals("Asia/Seoul", request.headers().getFirst(HookHeaders.TIME_ZONE));
    }

    @Test
    void everyFailureAnswerOfACommandLineIsWhatItsHostReadsWhenTheServerIsNotThere() throws IOException {
        Path absent = temporary.resolve("absent.properties");
        Path gone = announcedButGone();
        for (Path file : List.of(absent, gone)) {
            for (FailureAnswer answer : FailureAnswer.values()) {
                WhatTheHostSees.assertFailureAnswer(answer, run(hook("gemini", "AfterTool", file, "--on-failure=" + answer.option(), "--timeout=1"), Map.of(), GEMINI_INPUT),
                        "SERVER_NOT_REACHABLE ConnectException", file.getFileName().toString());
            }
            WhatTheHostSees.assertFailureAnswer(FailureAnswer.FAIL, run(hook("gemini", "AfterTool", file), Map.of(), GEMINI_INPUT), "SERVER_NOT_REACHABLE ConnectException",
                    "no --on-failure is FAIL: " + file.getFileName());
        }
        assertTrue(stub.received.isEmpty(), "the stub that was announced in another file was not asked");
    }

    @Test
    void everyFailureAnswerOfACommandLineIsWhatItsHostReadsWhenTheServerRefuses() throws IOException {
        stub.status = 503;
        for (FailureAnswer answer : FailureAnswer.values()) {
            WhatTheHostSees.assertFailureAnswer(answer, run(hook("gemini", "AfterTool", "--on-failure=" + answer.option(), "--timeout=1"), Map.of(), GEMINI_INPUT), "SERVER_REFUSED 503",
                    "status 503");
        }
    }

    @Test
    void anInputTheServerWouldRefuseIsTheFailureAnswerWithTheReasonsCode() throws IOException {
        Result result = run(hook("gemini", "AfterTool", "--on-failure=allow-decision"), Map.of(), "[1,2]");

        WhatTheHostSees.assertFailureAnswer(FailureAnswer.ALLOW_DECISION, result, "HOOK_BODY_NOT_A_JSON_OBJECT", "not an object");
        assertTrue(stub.received.isEmpty(), "nothing was sent");
        WhatTheHostSees.assertFailureAnswer(FailureAnswer.FAIL, run(hook("gemini", "AfterTool"), Map.of(), ""), "HOOK_BODY_EMPTY", "empty");
    }

    // a command line that cannot be understood

    private void assertUsageError(String expectedReason, List<String> words) throws IOException {
        ByteArrayInputStream stdin = new ByteArrayInputStream(GEMINI_INPUT.getBytes(StandardCharsets.UTF_8));
        int unread = stdin.available();

        Result result = run(words, Map.of(), stdin);

        assertEquals(new Result(2, "", WhatTheHostSees.FAILED + expectedReason), result, words.toString());
        assertEquals(unread, stdin.available(), "the input is not read for a command line that cannot be understood: " + words);
        assertEquals(0, stub.received.size(), "nothing was sent: " + words);
    }

    @Test
    void aCommandLineThatCannotBeUnderstoodIsExitTwoWithTheOptionsCodeOnStderrAndNothingRead() throws IOException {
        assertUsageError("HOOK_OPTION_INVALID other operands=", words("--server-file=" + serverFile));
        assertUsageError("HOOK_OPTION_INVALID other operands=hook gemini", words("hook", "gemini", "--server-file=" + serverFile));
        assertUsageError("HOOK_OPTION_INVALID other operands=hook gemini AfterTool extra", hook("gemini", "AfterTool", "extra"));
        assertUsageError("HOOK_OPTION_INVALID other --server-file=", words("hook", "gemini", "AfterTool"));
        assertUsageError("HOOK_OPTION_INVALID other --port=1234", hook("gemini", "AfterTool", "--port=1234"));
        assertUsageError("HOOK_OPTION_INVALID other --jar=", hook("gemini", "SessionStart", "--start-server=3"));
        assertUsageError("HOOK_OPTION_INVALID notANumber --timeout=", hook("gemini", "AfterTool", "--timeout="));
        assertUsageError("HOOK_OPTION_INVALID unknownValue --on-failure=explode", hook("gemini", "AfterTool", "--on-failure=explode"));
        assertUsageError("HOOK_OPTION_INVALID other --nope=1", hook("gemini", "AfterTool", "--nope=1"));
        assertUsageError("HOOK_OPTION_INVALID other --server-file=" + temporary.resolve("other"), hook("gemini", "AfterTool", "--server-file=" + temporary.resolve("other")));
        assertUsageError("HOOK_OPTION_INVALID other --wake=1", hook("gemini", "AfterTool", "--wake=1"));
    }

    // the server start

    @Test
    void aSessionStartThatFindsNoServerRunsTheJarAndAJarThatCannotRunIsAStartThatFailedWithJavasExitCode() throws IOException {
        Path notAJar = temporary.resolve("no-such.jar");
        for (FailureAnswer answer : FailureAnswer.values()) {
            Result result = run(hook("gemini", "SessionStart", temporary.resolve("absent.properties"), "--start-server=3", "--jar=" + notAJar, "--on-failure=" + answer.option()), Map.of(),
                    "{\"session_id\":\"s\"}");

            WhatTheHostSees.assertFailureAnswer(answer, result, "SERVER_NOT_STARTED the start ended with exit code 1", "--jar is not a jar");
        }
    }

    @Test
    void aCallWithoutStartServerNeverReachesAStartWhateverTheJar() throws IOException {
        Result result = run(hook("gemini", "SessionStart", temporary.resolve("absent.properties"), "--jar=" + temporary.resolve("no-such.jar")), Map.of(), "{\"session_id\":\"s\"}");

        WhatTheHostSees.assertFailureAnswer(FailureAnswer.FAIL, result, "SERVER_NOT_REACHABLE ConnectException", "no server, no start asked for");
    }

    @Test
    void aSessionStartTheServerAnswersIsNotStarted() throws IOException {
        Result result = run(hook("gemini", "SessionStart", "--start-server=3", "--jar=" + temporary.resolve("no-such.jar"), "--bind-host"), Map.of(), "{\"session_id\":\"s\"}");

        assertEquals(new Result(0, HookServerStub.ANSWER, ""), result);
        assertEquals(1, stub.received.size());
    }

    // --warm

    @Test
    void aWarmFileIsReadAsTheHostsInputAndSendsNothingThenTheHookGoesOn() throws IOException {
        Path input = temporary.resolve("agy-input.json");
        Files.writeString(input, "{\"conversationId\":\"c1\",\"workspacePaths\":[\"/w1\",\"/w2\"],\"toolCall\":{\"name\":\"n\",\"args\":{\"k\":1}}}", StandardCharsets.UTF_8);

        Result result = run(hook("gemini", "AfterTool", "--warm=agy=" + input), Map.of(), GEMINI_INPUT);

        assertEquals(new Result(0, HookServerStub.ANSWER, ""), result);
        assertEquals(1, stub.received.size(), "the warm file is no request: the one request is the hook's");
        assertEquals(GEMINI_REDUCED, stub.received.get(0).bodyText());
    }

    @Test
    void aWarmFileThatIsNotAnObjectIsRefusedAsAHooksInputIs() throws IOException {
        Path input = temporary.resolve("not-an-object.json");
        Files.writeString(input, "[1,2]", StandardCharsets.UTF_8);

        HookNoAnswer refused = assertThrows(HookNoAnswer.class, () -> run(hook("gemini", "AfterTool", "--warm=agy=" + input), Map.of(), GEMINI_INPUT));

        assertEquals(Reason.HOOK_BODY_NOT_A_JSON_OBJECT, refused.reason());
        assertTrue(stub.received.isEmpty(), "nothing was sent");
    }

    @Test
    void aWarmValueThatNamesNoHostOrIsNoLabelAndFileIsAnOptionFailure() throws IOException {
        assertUsageError("HOOK_OPTION_INVALID unknownValue --warm=no-such-host", hook("gemini", "AfterTool", "--warm=no-such-host=" + temporary.resolve("in.json")));
        assertUsageError("HOOK_OPTION_INVALID other --warm=agy", hook("gemini", "AfterTool", "--warm=agy"));
        assertUsageError("HOOK_OPTION_INVALID other --warm==file.json", hook("gemini", "AfterTool", "--warm==file.json"));
    }

    // --only-if-env, --wake

    @Test
    void aConditionThatDoesNotHoldIsExitZeroAndNoRequestAndOneThatHoldsGoesOn() throws IOException {
        List<String> words = hook("claude", "Stop", "--only-if-env=CLAUDE_CODE_SESSION_ATTENDED=1");

        assertEquals(new Result(0, "", ""), run(words, Map.of(), "{}"));
        assertEquals(new Result(0, "", ""), run(words, Map.of("CLAUDE_CODE_SESSION_ATTENDED", "0"), "{}"));
        assertEquals(0, stub.received.size());
        assertEquals(new Result(0, HookServerStub.ANSWER, ""), run(words, Map.of("CLAUDE_CODE_SESSION_ATTENDED", "1"), "{}"));
        assertEquals(1, stub.received.size());
    }

    @Test
    void aWakeIsTheMessageOnStderrAndExitTwo() throws IOException {
        stub.answer = "message for the idle session".getBytes(StandardCharsets.UTF_8);

        Result result = run(hook("claude", "Stop", "--module=wake", "--wake", "--timeout=1"), Map.of(), "{}");

        assertEquals(new Result(2, "", "message for the idle session"), result);
        assertEquals("/hooks/v1/claude/Stop/wake", stub.received.get(0).path());
    }
}
