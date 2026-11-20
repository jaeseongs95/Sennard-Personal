// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.hookclient.HookServerStub.Reply;

/**
 * The MCP relay ({@link McpRelay}), the real class, in this process, between a stdin and a stdout the test holds and a {@link HookServerStub} on a real loopback socket that is announced in
 * a real address file ({@link ServerAddress}). The process of its own is {@link HookClientProcessTest}'s. Nothing here is timed: a case that waits for a line waits at most
 * {@link #BOUND}, so a relay that wrote nothing fails the case instead of hanging it. Case table (input: the lines the host writes, what the server answers; expected: the lines the host
 * reads, and what the server received):
 * <ol>
 * <li>a request: posted to {@code /mcp} as written, with {@code Content-Type: application/json}, {@code Accept: application/json, text/event-stream} and {@code Sennard-Host-Label}; the
 * answer is one line, the JSON the server answered (a raw line end inside it becomes a space); the {@code TZ} of the environment is sent as the time zone</li>
 * <li>the {@code protocolVersion} of the {@code initialize} answer is sent as {@code MCP-Protocol-Version} on the requests after it, and not on the {@code initialize}</li>
 * <li>an event stream: each event's {@code data} is a line, in order; the other fields and comments are not; the data lines of one event are joined</li>
 * <li>a notification (no {@code id}): posted, the server's 202 writes nothing</li>
 * <li>a refusal (status 400 or above), no server announced, a server whose port refuses the connection, an answer that is not JSON or a stream: the request is answered as a JSON-RPC error
 * with its own id (a number or a string, as written) and the reason's code as the message; a notification gets nothing on stdout</li>
 * <li>a line that is not a JSON object: a parse error with a null id</li>
 * <li>no server and an {@code initialize}: the server is started once with the wait asked for, and the request goes to the port the start announced; a start that fails is the error
 * {@code SERVER_NOT_STARTED} with its message; with no wait asked for, or for any request but an {@code initialize}, nothing is started</li>
 * <li>the port is read for every request: a server that was started again at another port gets the next request</li>
 * <li>the requests are sent at the same time: a request the server holds does not hold the one after it, and its answer is written when stdin has already ended (that the process does not
 * end before it is {@code HookClientProcessTest}'s: its stdin is a file that ends at once)</li>
 * <li>the error message's text is quoted as JSON</li>
 * </ol>
 */
class McpRelayTest {
    /** The bound of a wait for a line: for a relay that does not answer, which fails the case. It is not a time the relay is expected to take. */
    private static final Duration BOUND = Duration.ofSeconds(60);
    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}";
    private static final String INITIALIZED = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"serverInfo\":{\"name\":\"sennard\"}}}";
    private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
    private static final String TOOLS = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}";

    @TempDir
    Path temporary;

    private HookServerStub stub;
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

    /** The host's stdin: lines the test sends, read by the relay; it ends when the test says so. */
    private static final class Feed extends InputStream {
        private static final byte[] END = new byte[0];
        private final LinkedBlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private byte[] current = new byte[0];
        private int at;
        private boolean ended;

        void send(String line) {
            chunks.add((line + "\n").getBytes(StandardCharsets.UTF_8));
        }

        void end() {
            chunks.add(END);
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] into, int offset, int length) throws IOException {
            while (!ended && at == current.length) {
                try {
                    current = chunks.take();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", interrupted);
                }
                at = 0;
                ended = current == END;
            }
            if (ended) {
                return -1;
            }
            int count = Math.min(length, current.length - at);
            System.arraycopy(current, at, into, offset, count);
            at += count;
            return count;
        }
    }

    /** The host's stdout: each line the relay writes is queued. */
    private static final class Lines extends OutputStream {
        final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                lines.add(pending.toString(StandardCharsets.UTF_8));
                pending.reset();
            } else {
                pending.write(b);
            }
        }

        String next() throws InterruptedException {
            String line = lines.poll(BOUND.toSeconds(), TimeUnit.SECONDS);
            assertNotNull(line, "the relay wrote no line within the bound");
            return line;
        }
    }

    /** A relay running in this process on a feed and a stdout, which the test closes. */
    private final class Running implements AutoCloseable {
        final Feed stdin = new Feed();
        final Lines stdout = new Lines();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        final CompletableFuture<Integer> exit;

        Running(McpRelayArguments.Parsed arguments, HookClient.ServerStart start, Map<String, String> environment) {
            McpRelay relay = new McpRelay(environment, arguments, start);
            exit = new CompletableFuture<>();
            // A thread of its own: the relay blocks on stdin, and several relays run at once in one case (the common pool may have one thread).
            Thread.ofPlatform().start(() -> {
                try {
                    exit.complete(relay.run(stdin, stdout, stderr));
                } catch (IOException | RuntimeException failed) {
                    exit.completeExceptionally(failed);
                }
            });
        }

        int finish() throws Exception {
            stdin.end();
            return exit.get(BOUND.toSeconds(), TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            if (exit.isDone()) {
                return;
            }
            try {
                finish();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while closing the relay", e);
            } catch (Exception e) {
                throw new IllegalStateException("the relay did not finish on close", e);
            }
        }
    }

    private static final HookClient.ServerStart NO_START = within -> {
        throw new AssertionError("a server start was asked for within " + within);
    };

    private McpRelayArguments.Parsed arguments(Path file, int startSeconds) {
        return new McpRelayArguments.Parsed("gemini", file, startSeconds > 0 ? temporary.resolve("sennard.jar") : null, startSeconds);
    }

    private Running relay() {
        return new Running(arguments(serverFile, 0), NO_START, Map.of());
    }

    private static Reply json(String body) {
        return new Reply(200, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private static Reply none(int status) {
        return new Reply(status, null, new byte[0]);
    }

    private static boolean carries(HookServerStub.Received request, String text) {
        return request.bodyText().contains(text);
    }

    // 1, 2

    @Test
    void aRequestIsPostedAsWrittenToTheMcpEndpointAndTheAnswerIsOneLine() throws Exception {
        stub.responder = request -> json("{\"jsonrpc\":\"2.0\",\n\"id\":1,\r\n\"result\":{\"note\":\"한글 😀\"}}\n");
        Map<String, String> environment = Map.of("TZ", "Asia/Seoul");
        try (Running relay = new Running(arguments(serverFile, 0), NO_START, environment)) {
            relay.stdin.send(INITIALIZE);

            assertEquals("{\"jsonrpc\":\"2.0\", \"id\":1,  \"result\":{\"note\":\"한글 😀\"}}", relay.stdout.next(), "one line: the raw line ends of the JSON are white space");
        }
        assertEquals(1, stub.received.size());
        HookServerStub.Received request = stub.received.get(0);
        assertEquals("POST", request.method());
        assertEquals("/mcp", request.path());
        assertEquals(INITIALIZE, request.bodyText());
        assertEquals("application/json", request.headers().getFirst("Content-Type"));
        assertEquals("application/json, text/event-stream", request.headers().getFirst("Accept"));
        assertEquals("gemini", request.headers().getFirst("Sennard-Host-Label"));
        assertEquals("Asia/Seoul", request.headers().getFirst(HookHeaders.TIME_ZONE));
        assertNull(request.headers().getFirst("MCP-Protocol-Version"), "the initialize has no version yet");
    }

    @Test
    void theProtocolVersionTheServerNegotiatedIsSentOnTheRequestsAfterTheInitialize() throws Exception {
        stub.responder = request -> json(carries(request, "initialize") ? INITIALIZED : TOOLS);
        try (Running relay = relay()) {
            relay.stdin.send(INITIALIZE);
            assertEquals(INITIALIZED, relay.stdout.next());
            relay.stdin.send(TOOLS_LIST);
            assertEquals(TOOLS, relay.stdout.next());
        }
        assertNull(stub.received.get(0).headers().getFirst("MCP-Protocol-Version"));
        assertEquals("2025-06-18", stub.received.get(1).headers().getFirst("MCP-Protocol-Version"));
    }

    // 3

    @Test
    void anEventStreamIsOneLinePerEventsDataInOrderAndOnlyTheData() throws Exception {
        String stream = ": a comment\nevent: message\nid: 4\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\"}\n\n"
                + "event: message\r\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\r\ndata: \"result\":{}}\r\n\r\nretry: 3\n\ndata:{\"jsonrpc\":\"2.0\",\"id\":3}";
        stub.responder = request -> new Reply(200, "text/event-stream;charset=UTF-8", stream.getBytes(StandardCharsets.UTF_8));
        try (Running relay = relay()) {
            relay.stdin.send(TOOLS_LIST);

            assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\"}", relay.stdout.next());
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2, \"result\":{}}", relay.stdout.next(), "two data lines of one event are one message, on one line");
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":3}", relay.stdout.next(), "the stream's end ends the last event; 'data:' with no space");
        }
    }

    @Test
    void theMessagesOfAnEventStreamAreItsEventsData() {
        assertEquals(List.of(), McpRelay.serverSentMessages(""));
        assertEquals(List.of(), McpRelay.serverSentMessages(": only a comment\n\nevent: ping\n\n"));
        assertEquals(List.of("a", "b"), McpRelay.serverSentMessages("data: a\n\ndata: b\n\n"));
        assertEquals(List.of("a b"), McpRelay.serverSentMessages("data: a\ndata: b\n\n"));
        assertEquals(List.of(" a"), McpRelay.serverSentMessages("data:  a\n\n"), "only one space after the colon is the field's own");
    }

    // 4

    @Test
    void aNotificationIsPostedAndTheServersAcceptanceWritesNothing() throws Exception {
        stub.responder = request -> carries(request, "notifications/initialized") ? none(202) : json(TOOLS);
        try (Running relay = relay()) {
            relay.stdin.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            relay.stdin.send(TOOLS_LIST);

            assertEquals(TOOLS, relay.stdout.next(), "the first line is the request's answer: the notification wrote none");
            assertEquals(0, relay.finish());
            assertTrue(relay.stdout.lines.isEmpty(), "nothing else was written: " + relay.stdout.lines);
        }
        assertEquals(2, stub.received.size());
    }

    // 5

    @Test
    void aRequestTheServerRefusesIsAnErrorWithItsOwnIdAndTheStatusAsTheReasonsCode() throws Exception {
        stub.responder = request -> none(503);
        try (Running relay = relay()) {
            relay.stdin.send("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}");
            relay.stdin.send("{\"jsonrpc\":\"2.0\",\"id\":\"abc-1\",\"method\":\"tools/list\"}");

            List<String> answers = List.of(relay.stdout.next(), relay.stdout.next());
            assertTrue(answers.contains("{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-32000,\"message\":\"SERVER_REFUSED 503\"}}"), answers.toString());
            assertTrue(answers.contains("{\"jsonrpc\":\"2.0\",\"id\":\"abc-1\",\"error\":{\"code\":-32000,\"message\":\"SERVER_REFUSED 503\"}}"), answers.toString());
        }
    }

    @Test
    void aNotificationThatCannotBeDeliveredWritesItsReasonToStderrAndNothingToStdout() throws Exception {
        stub.responder = request -> none(500);
        Running relay = relay();
        relay.stdin.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(0, relay.finish());

        assertEquals(0, relay.stdout.lines.size());
        assertEquals("Sennard relay: SERVER_REFUSED 500\n", relay.stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void noServerAnnouncedAndAServerWhosePortRefusesAreTheSameErrorOfTheRequest() throws Exception {
        Path gone = temporary.resolve("gone.properties");
        HookServerStub.announce(HookServerStub.unusedPort(), gone);
        try (Running none = new Running(arguments(temporary.resolve("absent.properties"), 0), NO_START, Map.of());
                Running refusing = new Running(arguments(gone, 0), NO_START, Map.of())) {
            none.stdin.send(TOOLS_LIST);
            refusing.stdin.send(TOOLS_LIST);

            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32000,\"message\":\"SERVER_NOT_REACHABLE NoLiveServerAnnounced\"}}", none.stdout.next());
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32000,\"message\":\"SERVER_NOT_REACHABLE ConnectException\"}}", refusing.stdout.next());
        }
    }

    @Test
    void anAnswerThatIsNeitherJsonNorAStreamIsAnErrorOfTheRequest() throws Exception {
        stub.responder = request -> new Reply(200, "text/html; charset=utf-8", "<html></html>".getBytes(StandardCharsets.UTF_8));
        try (Running relay = relay()) {
            relay.stdin.send(TOOLS_LIST);

            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32000,\"message\":\"MCP_ANSWER_UNREADABLE text/html??charset?utf-8\"}}", relay.stdout.next());
        }
    }

    @Test
    void anInitializeAnswerThatIsNoJsonObjectIsAnErrorOfTheInitialize() throws Exception {
        stub.responder = request -> json("this is not JSON");
        try (Running relay = relay()) {
            relay.stdin.send(INITIALIZE);

            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"MCP_ANSWER_UNREADABLE initialize-answer-is-not-json\"}}", relay.stdout.next());
        }
    }

    // 6

    @Test
    void aLineThatIsNotAJsonObjectIsAParseErrorWithANullId() throws Exception {
        try (Running relay = relay()) {
            relay.stdin.send("this is not JSON");
            relay.stdin.send("[1,2]");
            relay.stdin.send("");

            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"HOOK_BODY_NOT_A_JSON_OBJECT\"}}", relay.stdout.next());
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"HOOK_BODY_NOT_A_JSON_OBJECT\"}}", relay.stdout.next());
            assertEquals(0, relay.finish());
            assertTrue(relay.stdout.lines.isEmpty(), "an empty line is no message: " + relay.stdout.lines);
        }
        assertTrue(stub.received.isEmpty(), "nothing was sent");
    }

    // 7

    @Test
    void anInitializeWithNoLiveServerStartsTheServerOnceAndGoesToThePortTheStartAnnounced() throws Exception {
        Path file = temporary.resolve("start.properties");
        List<HookServerStub> started = new CopyOnWriteArrayList<>();
        List<Duration> asked = new CopyOnWriteArrayList<>();
        HookClient.ServerStart start = within -> {
            asked.add(within);
            try {
                HookServerStub second = new HookServerStub();
                second.responder = request -> json(INITIALIZED);
                second.announceIn(file);
                started.add(second);
            } catch (IOException cannotListen) {
                throw new IllegalStateException(cannotListen);
            }
        };
        try (Running relay = new Running(arguments(file, 30), start, Map.of())) {
            relay.stdin.send(INITIALIZE);

            assertEquals(INITIALIZED, relay.stdout.next());
        } finally {
            started.forEach(HookServerStub::close);
        }
        assertEquals(List.of(Duration.ofSeconds(30)), asked, "started once, with the wait asked for");
        assertEquals(1, started.get(0).received.size());
        assertEquals(INITIALIZE, started.get(0).received.get(0).bodyText());
    }

    @Test
    void aStartThatFailsIsTheInitializesErrorWithItsMessage() throws Exception {
        HookClient.ServerStart failing = within -> {
            throw new IllegalStateException("the installation was not found");
        };
        try (Running relay = new Running(arguments(temporary.resolve("absent.properties"), 30), failing, Map.of())) {
            relay.stdin.send(INITIALIZE);

            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"SERVER_NOT_STARTED the installation was not found\"}}", relay.stdout.next());
        }
    }

    @Test
    void aStartThatEndsWithNoServerAnnouncedIsAnErrorToo() throws Exception {
        try (Running relay = new Running(arguments(temporary.resolve("absent.properties"), 30), within -> {
        }, Map.of())) {
            relay.stdin.send(INITIALIZE);

            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"SERVER_NOT_STARTED NoLiveServerAnnounced\"}}", relay.stdout.next());
        }
    }

    @Test
    void nothingIsStartedForARequestThatIsNotAnInitializeOrWhenNoWaitWasAsked() throws Exception {
        // NO_START fails the case when it is called.
        try (Running noInitialize = new Running(arguments(temporary.resolve("absent.properties"), 30), NO_START, Map.of());
                Running noWait = new Running(arguments(temporary.resolve("absent.properties"), 0), NO_START, Map.of())) {
            noInitialize.stdin.send(TOOLS_LIST);
            noWait.stdin.send(INITIALIZE);

            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32000,\"message\":\"SERVER_NOT_REACHABLE NoLiveServerAnnounced\"}}", noInitialize.stdout.next());
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"SERVER_NOT_REACHABLE NoLiveServerAnnounced\"}}", noWait.stdout.next());
        }
    }

    @Test
    void anInitializeThatFindsALiveServerStartsNothing() throws Exception {
        stub.responder = request -> json(INITIALIZED);
        try (Running relay = new Running(arguments(serverFile, 30), NO_START, Map.of())) {
            relay.stdin.send(INITIALIZE);

            assertEquals(INITIALIZED, relay.stdout.next());
        }
    }

    // 8

    @Test
    void theNextRequestFollowsAServerThatWasStartedAgainAtAnotherPort() throws Exception {
        stub.responder = request -> json(TOOLS);
        try (Running relay = relay(); HookServerStub second = new HookServerStub()) {
            second.responder = request -> json("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"from\":\"second\"}}");
            relay.stdin.send(TOOLS_LIST);
            assertEquals(TOOLS, relay.stdout.next());

            second.announceIn(serverFile);
            relay.stdin.send(TOOLS_LIST);

            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"from\":\"second\"}}", relay.stdout.next());
            assertEquals(1, stub.received.size());
            assertEquals(1, second.received.size());
        }
    }

    // 9

    @Test
    void aRequestTheServerHoldsDoesNotHoldTheOneAfterItAndItsAnswerIsWrittenWhenStdinHasEnded() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        stub.responder = request -> {
            if (carries(request, "\"id\":1")) {
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return json("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"held\":true}}");
            }
            return json(TOOLS);
        };
        Running relay = relay();
        relay.stdin.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"}");
        relay.stdin.send(TOOLS_LIST);

        assertEquals(TOOLS, relay.stdout.next(), "the second request was answered while the first was held");
        relay.stdin.end();
        release.countDown();

        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"held\":true}}", relay.stdout.next());
        assertEquals(0, relay.exit.get(BOUND.toSeconds(), TimeUnit.SECONDS));
    }

    // 10

    @Test
    void anErrorsMessageIsQuotedAsJsonText() {
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"error\":{\"code\":-32000,\"message\":\"x\\\"y\\\\z\\u000a\"}}", McpRelay.error("\"a\"", McpRelay.SERVER_ERROR, "x\"y\\z\n"));
    }

    // the relay's own command line

    @Test
    void theRelaysArgumentsAreReadBackAsTheyAreWritten() {
        McpRelayArguments.Parsed parsed = McpRelayArguments.parse(List.of("--server-file=/state/server.properties", "--jar=/install/sennard.jar", "--host=claude", "--start-server=30"));

        assertEquals(new McpRelayArguments.Parsed("claude", Path.of("/state/server.properties"), Path.of("/install/sennard.jar"), 30), parsed);
        assertEquals(List.of("--host=claude", "--start-server=30"), McpRelayArguments.arguments("claude", 30));
        assertEquals(parsed, McpRelayArguments.parse(withInstallation(McpRelayArguments.arguments("claude", 30))));
        assertEquals(new McpRelayArguments.Parsed("agy", Path.of("/state/server.properties"), null, 0), McpRelayArguments.parse(List.of("--host=agy", "--server-file=/state/server.properties")), "no start wanted: no jar needed");
    }

    private static List<String> withInstallation(List<String> words) {
        List<String> all = new ArrayList<>(words);
        all.addAll(List.of("--server-file=/state/server.properties", "--jar=/install/sennard.jar"));
        return all;
    }

    @Test
    void aRelayCommandLineThatCannotBeUnderstoodIsRefusedWithItsOptionAndValue() {
        for (List<String> words : List.of(List.of("--server-file=/s"), List.of("--host=", "--server-file=/s"), List.of("--host=a"), List.of("--host=a", "--server-file=/s", "--start-server=3"),
                List.of("--host=a", "--server-file=/s", "--port=1"), List.of("--host=a", "--host=b", "--server-file=/s"), List.of("--host", "--server-file=/s"), List.of("host", "--server-file=/s"))) {
            HookOptionException failure = assertThrows(HookOptionException.class, () -> McpRelayArguments.parse(words), words.toString());
            assertEquals(HookOptionException.Problem.OTHER, failure.problem(), words.toString());
        }
        HookOptionException notANumber = assertThrows(HookOptionException.class, () -> McpRelayArguments.parse(List.of("--host=a", "--server-file=/s", "--start-server=soon")));
        assertEquals("HOOK_OPTION_INVALID notANumber --start-server=soon", notANumber.getMessage());
    }

    @Test
    void theEntryPointRefusesACommandLineItCannotUnderstandWithExitTwoAndTheCodeOnStderr() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = McpRelayMain.run(List.of("--host=a"), Map.of(), InputStream.nullInputStream(), out, err);

        assertEquals(2, exit);
        assertEquals("", out.toString(StandardCharsets.UTF_8), "stdout is the MCP channel: nothing else is written to it");
        assertEquals("Sennard relay failed: HOOK_OPTION_INVALID other --server-file=", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theEntryPointEndsWithExitZeroWhenStdinEndsAndWritesNothingForNoMessage() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exit = McpRelayMain.run(List.of("--host=a", "--server-file=" + serverFile), Map.of(), InputStream.nullInputStream(), out, new ByteArrayOutputStream());

        assertEquals(0, exit);
        assertEquals(0, out.size());
        assertTrue(stub.received.isEmpty());
    }

    @Test
    void everyOfManyRequestsInFlightAtTheSameTimeIsAnsweredOnceAndWhole() throws Exception {
        AtomicInteger answered = new AtomicInteger();
        stub.responder = request -> {
            answered.incrementAndGet();
            return json(TOOLS);
        };
        int requests = 50;
        try (Running relay = relay()) {
            for (int i = 0; i < requests; i++) {
                relay.stdin.send(TOOLS_LIST);
            }
            for (int i = 0; i < requests; i++) {
                assertEquals(TOOLS, relay.stdout.next());
            }
        }
        assertEquals(requests, answered.get());
    }
}
