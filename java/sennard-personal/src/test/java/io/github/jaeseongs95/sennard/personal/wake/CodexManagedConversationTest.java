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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
    private static final long OBSERVED_AT_MILLIS = 1_700_000_000_123L;
    private static final Clock OBSERVATION_CLOCK = Clock.fixed(Instant.ofEpochMilli(OBSERVED_AT_MILLIS), ZoneOffset.UTC);
    private static final String MARKER = "[sennard wake:01a14677-baf4-4350-922b-d5d63b40c168]";
    private static final String NEXT_MARKER = "[sennard wake:01a14677-baf4-4350-922b-d5d63b40c169]";
    private static final WakeTransport.Target PARENT = new WakeTransport.Target("parent", null);
    private final JsonMapper mapper = JsonMapper.builder().build();

    @TempDir
    Path workspace;

    @Test
    void readsOnlyTheMatchingThreadsUserTitleWithoutResumingIt() throws Exception {
        try (var process = probe("named-thread")) {
            assertEquals(Optional.of("Chosen title"), CodexAppServerConnection.readThreadTitle(process, mapper, DEADLINE, "session"));
            assertFalse(process.isAlive());
            var sent = requests();
            assertEquals(List.of("initialize", "initialized", "thread/read"), methods(sent));
            assertEquals("session", sent.getLast().path("params").path("threadId").asString());
            assertTrue(sent.getLast().path("params").path("includeTurns").isBoolean());
            assertFalse(sent.getLast().path("params").path("includeTurns").asBoolean());
        }
    }

    @Test
    void anUntitledThreadDoesNotSupplyAUserTitle() throws Exception {
        try (var process = probe("untitled-thread")) {
            assertEquals(Optional.empty(), CodexAppServerConnection.readThreadTitle(process, mapper, DEADLINE, "session"));
            assertFalse(process.isAlive());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-thread", "internal-error", "malformed-title"})
    void rejectsAnUnusableTitleResponseAndClosesTheProcess(String behavior) throws Exception {
        try (var process = probe(behavior)) {
            assertThrows(IOException.class, () -> CodexAppServerConnection.readThreadTitle(process, mapper, DEADLINE, "session"));
            assertFalse(process.isAlive());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,NONE", "true,ROOT"})
    void workerReportsCapabilitiesWithoutAManagedLauncher(boolean rootReady, NativeWakeProtocol.Capability expected) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var capability = new AtomicReference<String>();
        server.createContext(NativeWakeProtocol.POLL, exchange -> {
            try (exchange) {
                capability.set(exchange.getRequestHeaders().getFirst(NativeWakeProtocol.CAPABILITIES));
                exchange.sendResponseHeaders(410, -1);
            }
        });
        server.start();
        try {
            NativeWakeAdapter adapter = (environment, target) -> rootReady
                    ? new WakeTransport.Availability.Ready((thread, marker) -> new WakeTransport.Outcome.Submitted())
                    : new WakeTransport.Availability.Disabled();
            assertEquals(0, NativeWakeWorker.watch(ServerAddress.ofThisProcess(server.getAddress().getPort()), UUID.randomUUID().toString(),
                    PARENT, Map.of(), adapter, mapper, OBSERVATION_CLOCK));
            assertEquals(expected.name(), capability.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theWorkerReportsTheTitleWithItsRegistrationWhilePollingNormally() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var key = UUID.randomUUID().toString();
        var receivedKey = new AtomicReference<String>();
        var firstTitle = new AtomicReference<NativeWakeProtocol.SessionTitle>();
        var receivedTitle = new AtomicReference<NativeWakeProtocol.SessionTitle>();
        var pollCount = new java.util.concurrent.atomic.AtomicInteger();
        var reported = new CountDownLatch(1);
        server.createContext(NativeWakeProtocol.SESSION_TITLE, exchange -> {
            try (exchange) {
                receivedKey.set(exchange.getRequestHeaders().getFirst(NativeWakeProtocol.KEY));
                var title = mapper.readValue(exchange.getRequestBody(), NativeWakeProtocol.SessionTitle.class);
                if (firstTitle.compareAndSet(null, title)) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                receivedTitle.set(title);
                exchange.sendResponseHeaders(204, -1);
                reported.countDown();
            }
        });
        server.createContext(NativeWakeProtocol.POLL, exchange -> {
            try (exchange) {
                try {
                    int poll = pollCount.incrementAndGet();
                    exchange.sendResponseHeaders(reported.await(6, TimeUnit.SECONDS) || poll >= 3 ? 410 : 204, -1);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.start();
            try {
                NativeWakeAdapter adapter = new NativeWakeAdapter() {
                    @Override
                    public WakeTransport.Availability connect(Map<String, String> environment, WakeTransport.Target target) {
                        return new WakeTransport.Availability.Ready((session, marker) -> new WakeTransport.Outcome.Submitted());
                    }

                    @Override
                    public Optional<SessionTitleReader> sessionTitles(Map<String, String> environment, WakeTransport.Target target) {
                        assertEquals(PARENT, target);
                        var title = new AtomicReference<>("Review β");
                        return Optional.of(() -> Optional.ofNullable(title.getAndSet(null)));
                    }
                };
                assertEquals(0, NativeWakeWorker.watch(ServerAddress.ofThisProcess(server.getAddress().getPort()), key, PARENT, Map.of(), adapter, mapper, OBSERVATION_CLOCK));
                assertEquals(key, receivedKey.get());
                assertNotNull(receivedTitle.get(), "a failed first upload must be retried without another title change");
                assertEquals(firstTitle.get(), receivedTitle.get());
                assertEquals("Review β", receivedTitle.get().title());
                assertEquals(OBSERVED_AT_MILLIS, receivedTitle.get().observedAtMillis());
            } finally {
                server.stop(0);
            }
        }
    }

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
            var adapter = managedAdapter(new WakeTransport.Availability.Disabled(), request -> {
                assertEquals(launch, request);
                process.set(probe(supported ? "ordinary" : "unlisted-effort"));
                return CodexManagedConversation.open(process.get(), mapper, DEADLINE, request.model(), request.effort(), null);
            });
            int exit = NativeWakeWorker.watch(ServerAddress.ofThisProcess(server.getAddress().getPort()), key, PARENT, Map.of(), adapter, mapper, OBSERVATION_CLOCK);
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
        var capability = new AtomicReference<String>();
        var completions = new CopyOnWriteArrayList<List<String>>();
        server.createContext(NativeWakeProtocol.POLL, exchange -> {
            try (exchange) {
                int poll = polls.getAndIncrement();
                if (poll == 0) {
                    capability.set(exchange.getRequestHeaders().getFirst(NativeWakeProtocol.CAPABILITIES));
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
            var root = new WakeTransport.Availability.Ready((thread, marker) -> {
                rootWakes.incrementAndGet();
                return new WakeTransport.Outcome.Submitted();
            });
            var adapter = managedAdapter(root, request -> open(probe("ordinary"), null));
            int exit = NativeWakeWorker.watch(ServerAddress.ofThisProcess(server.getAddress().getPort()), key, PARENT, Map.of(), adapter, mapper, OBSERVATION_CLOCK);
            assertEquals(0, exit);
        } finally {
            server.stop(0);
        }
        assertEquals(0, rootWakes.get());
        assertEquals(NativeWakeProtocol.Capability.BOTH.name(), capability.get());
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

    private static NativeWakeAdapter managedAdapter(WakeTransport.Availability root, NativeWakeAdapter.ManagedLauncher launcher) {
        return new NativeWakeAdapter() {
            @Override
            public WakeTransport.Availability connect(Map<String, String> environment, WakeTransport.Target target) {
                return root;
            }

            @Override
            public Optional<ManagedLauncher> managedLauncher(Map<String, String> environment, WakeTransport.Target target) {
                return Optional.of(launcher);
            }
        };
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
