// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol;
import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.protocol.ToolProcess;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CodexManagedConversationTest {
    private static final Duration DEADLINE = Duration.ofSeconds(8);
    private static final String MARKER = "[sennard wake:01a14677-baf4-4350-922b-d5d63b40c168]";
    private static final String NEXT_MARKER = "[sennard wake:01a14677-baf4-4350-922b-d5d63b40c169]";
    private final JsonMapper mapper = JsonMapper.builder().build();

    @TempDir
    Path workspace;

    @ParameterizedTest
    @CsvSource({"true,204", "false,204", "true,409"})
    void managedOnlyWorkerSendsTheExactResultWithoutStartingTheFirstTurn(boolean supported, int serverStatus) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String key = UUID.randomUUID().toString();
        String id = UUID.randomUUID().toString();
        var launch = new NativeWakeProtocol.Launch(id, "chosen-model", "chosen-effort");
        byte[] body = mapper.writeValueAsBytes(launch);
        var polled = new AtomicInteger();
        var capability = new AtomicReference<String>();
        var pollKey = new AtomicReference<String>();
        var report = new AtomicReference<List<String>>();
        var process = new AtomicReference<ToolProcess.Stdio>();
        var aliveAtNextPoll = new AtomicReference<Boolean>();
        server.createContext(NativeWakeProtocol.POLL, exchange -> {
            try (exchange) {
                if (polled.getAndIncrement() != 0) {
                    aliveAtNextPoll.set(process.get().isAlive());
                    exchange.sendResponseHeaders(410, -1);
                    return;
                }
                capability.set(exchange.getRequestHeaders().getFirst(NativeWakeProtocol.CAPABILITIES));
                pollKey.set(exchange.getRequestHeaders().getFirst(NativeWakeProtocol.KEY));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.createContext(NativeWakeProtocol.LAUNCH_COMPLETE, exchange -> {
            try (exchange) {
                report.set(java.util.Arrays.asList(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst(NativeWakeProtocol.KEY),
                        exchange.getRequestHeaders().getFirst(NativeWakeProtocol.LAUNCH_ID), exchange.getRequestHeaders().getFirst(NativeWakeProtocol.CHILD_ID)));
                exchange.sendResponseHeaders(serverStatus, -1);
            }
        });
        server.start();
        try {
            int exit = NativeWakeWorker.watch(ServerAddress.ofThisProcess(server.getAddress().getPort()), key, "parent", null, request -> {
                assertEquals(launch, request);
                process.set(probe(supported ? "ordinary" : "unlisted-effort"));
                return CodexManagedConversation.open(process.get(), mapper, DEADLINE, request.model(), request.effort(), null);
            }, mapper);
            assertEquals(0, exit);
            assertEquals(2, polled.get());
            assertEquals(NativeWakeProtocol.Capability.MANAGED.name(), capability.get());
            assertEquals(key, pollKey.get());
            assertEquals(java.util.Arrays.asList(NativeWakeProtocol.LAUNCH_COMPLETE + (supported ? "/CREATED" : "/SELECTION_UNSUPPORTED"),
                    key, id, supported ? "child" : null), report.get());
            assertEquals(supported ? List.of("initialize", "initialized", "model/list", "thread/start")
                    : List.of("initialize", "initialized", "model/list"), methods(requests()));
            assertEquals(supported && serverStatus == 204, aliveAtNextPoll.get());
            assertFalse(process.get().isAlive());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void workerDeliversTheMarkerOnlyToTheApprovedChild() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String key = UUID.randomUUID().toString();
        var launch = new NativeWakeProtocol.Launch(UUID.randomUUID().toString(), "chosen-model", "chosen-effort");
        byte[] launchBody = mapper.writeValueAsBytes(launch);
        var polls = new AtomicInteger();
        var rootWakes = new AtomicInteger();
        var completions = new CopyOnWriteArrayList<List<String>>();
        server.createContext(NativeWakeProtocol.POLL, exchange -> {
            try (exchange) {
                int poll = polls.getAndIncrement();
                if (poll == 0) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, launchBody.length);
                    exchange.getResponseBody().write(launchBody);
                } else if (poll <= 2) {
                    byte[] marker = (poll == 1 ? MARKER : NEXT_MARKER).getBytes(StandardCharsets.US_ASCII);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain");
                    exchange.getResponseHeaders().set(NativeWakeProtocol.CHILD_ID, poll == 1 ? "sibling" : "child");
                    exchange.sendResponseHeaders(200, marker.length);
                    exchange.getResponseBody().write(marker);
                } else {
                    exchange.sendResponseHeaders(410, -1);
                }
            }
        });
        server.createContext(NativeWakeProtocol.LAUNCH_COMPLETE, exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.createContext(NativeWakeProtocol.COMPLETE, exchange -> {
            try (exchange) {
                completions.add(java.util.Arrays.asList(exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst(NativeWakeProtocol.CHILD_ID),
                        exchange.getRequestHeaders().getFirst(NativeWakeProtocol.MARKER)));
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        try {
            int exit = NativeWakeWorker.watch(ServerAddress.ofThisProcess(server.getAddress().getPort()), key, "parent", (thread, marker) -> {
                rootWakes.incrementAndGet();
                return new WakeTransport.Outcome.Submitted();
            }, request -> open(probe("ordinary"), null), mapper);
            assertEquals(0, exit);
        } finally {
            server.stop(0);
        }
        assertEquals(0, rootWakes.get());
        assertEquals(4, polls.get());
        assertEquals(List.of(List.of(NativeWakeProtocol.COMPLETE + "/NOT_SENT", "sibling", MARKER),
                List.of(NativeWakeProtocol.COMPLETE + "/SUBMITTED", "child", NEXT_MARKER)), completions);
        var inputs = requests();
        assertEquals(List.of("initialize", "initialized", "model/list", "thread/start", "turn/start"), methods(inputs));
        var turn = inputs.getLast().path("params");
        assertEquals("child", turn.path("threadId").asString());
        assertEquals(NEXT_MARKER, turn.path("input").get(0).path("text").asString());
    }

    @Test
    void startsTheReturnedChildConversationAndOnlySubmitsMarkersToIt() throws Exception {
        try (var conversation = open(probe("ordinary"), null)) {
            assertEquals("child", conversation.threadId());
            assertInstanceOf(WakeTransport.Outcome.NotSent.class, conversation.wake("sibling", MARKER));
            assertInstanceOf(WakeTransport.Outcome.NotSent.class, conversation.wake("child", "a peer body"));
            assertInstanceOf(WakeTransport.Outcome.Submitted.class, conversation.wake("child", MARKER));
            assertInstanceOf(WakeTransport.Outcome.Submitted.class, conversation.wake("child", NEXT_MARKER));
        }
        var inputs = requests();
        assertEquals(List.of("initialize", "initialized", "model/list", "thread/start", "turn/start", "turn/start"), methods(inputs));
        assertEquals("chosen-model", inputs.get(3).path("params").path("model").asString());
        var markers = List.of(MARKER, NEXT_MARKER);
        for (int index = 0; index < markers.size(); index++) {
            var turn = inputs.get(4 + index);
            var params = turn.path("params");
            assertEquals("child", params.path("threadId").asString());
            assertEquals("chosen-effort", params.path("effort").asString());
            assertEquals(markers.get(index), params.path("input").get(0).path("text").asString());
        }
    }

    @Test
    void resumesTheNamedConversationAndRefusesAHostThatReturnsAnother() throws Exception {
        var process = probe("wrong-thread");
        assertThrows(IOException.class, () -> open(process, "child"));
        assertFalse(process.isAlive());
        assertEquals(List.of("initialize", "initialized", "model/list", "thread/resume"), methods(requests()));
        assertEquals("child", requests().getLast().path("params").path("threadId").asString());
    }

    @Test
    void resumesTheExactReportedConversation() throws Exception {
        try (var conversation = open(probe("ordinary"), "child")) {
            assertEquals("child", conversation.threadId());
            assertInstanceOf(WakeTransport.Outcome.Submitted.class, conversation.wake("child", MARKER));
        }
        assertEquals(List.of("initialize", "initialized", "model/list", "thread/resume", "turn/start"), methods(requests()));
    }

    @Test
    void drainsTurnOutputEvenWhenNoSecondWakeArrives() throws Exception {
        try (var conversation = open(probe("streaming"), null);
                var changes = workspace.getFileSystem().newWatchService()) {
            workspace.register(changes, StandardWatchEventKinds.ENTRY_CREATE);
            assertInstanceOf(WakeTransport.Outcome.Submitted.class, conversation.wake("child", MARKER));
            var completion = changes.poll(DEADLINE.toSeconds(), TimeUnit.SECONDS);
            assertNotNull(completion, "The host must finish writing the turn while the client waits for new work");
            assertTrue(Files.exists(workspace.resolve("turn-completed")));
            assertEquals(List.of("initialize", "initialized", "model/list", "thread/start", "turn/start"), methods(requests()));
        }
    }

    @Test
    void surfacesTheHostsExplicitRejectionOfTurnParameters() throws Exception {
        var process = probe("invalid-parameters");
        try (var conversation = open(process, null)) {
            var refusal = assertInstanceOf(WakeTransport.Outcome.NotSent.class, conversation.wake("child", MARKER));
            assertEquals("MANAGED_CODEX_INVALID_PARAMS", refusal.code());
            assertFalse(process.isAlive());
        }
        assertEquals(List.of("initialize", "initialized", "model/list", "thread/start", "turn/start"), methods(requests()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unlisted-model", "unlisted-effort"})
    void refusesAnUnlistedSelectionBeforeCreatingAConversation(String behavior) throws Exception {
        var process = probe(behavior);
        assertThrows(IOException.class, () -> open(process, null));
        assertFalse(process.isAlive());
        assertEquals(List.of("initialize", "initialized", "model/list"), methods(requests()));
    }

    @Test
    void followsTheHostCatalogCursorBeforeCreatingTheConversation() throws Exception {
        try (var conversation = open(probe("catalog-pages"), null)) {
            assertEquals("child", conversation.threadId());
        }
        var inputs = requests();
        assertEquals(List.of("initialize", "initialized", "model/list", "model/list", "thread/start"), methods(inputs));
        assertEquals("catalog-page-2", inputs.get(3).path("params").path("cursor").asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"silent", "oversized", "foreign-reply", "server-request", "internal-error"})
    void closesAnAmbiguousExchangeWithoutResubmission(String behavior) throws Exception {
        var process = probe(behavior);
        try (var conversation = open(process, null)) {
            assertInstanceOf(WakeTransport.Outcome.Unknown.class, conversation.wake("child", MARKER));
            assertFalse(process.isAlive());
            assertInstanceOf(WakeTransport.Outcome.NotSent.class, conversation.wake("child", MARKER));
        }
        assertEquals(List.of("initialize", "initialized", "model/list", "thread/start", "turn/start"), methods(requests()));
    }

    private CodexManagedConversation open(ToolProcess.Stdio process, String resume) throws IOException, InterruptedException {
        return CodexManagedConversation.open(process, mapper, DEADLINE, "chosen-model", "chosen-effort", resume);
    }

    private ToolProcess.Stdio probe(String behavior) throws IOException {
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx" + System.getProperty("sennard.test.childHeap"), "-cp",
                System.getProperty("sennard.test.classpath"), CodexAppServerProbe.class.getName(), behavior, workspace.resolve("requests.jsonl").toString());
        return ToolProcess.startStdio(command, workspace, Map.of());
    }

    private List<JsonNode> requests() throws IOException {
        return Files.readAllLines(workspace.resolve("requests.jsonl")).stream().map(mapper::readTree).toList();
    }

    private static List<String> methods(List<JsonNode> requests) {
        return requests.stream().map(node -> node.path("method").asString()).toList();
    }
}
