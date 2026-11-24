// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import static org.junit.jupiter.api.Assertions.*;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import io.github.jaeseongs95.sennard.protocol.ToolProcess;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.Launch;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Injected process boundary only. Fake binary headers and synthetic credentials are never launched or used against a host. */
class AntigravityWakeTransportTest {
    @TempDir Path temporary;
    private static final WakeTransport.Target TARGET = new WakeTransport.Target("conversation-a", "/work");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CHILD = "native-conversation";

    @Test void managedLaunchUsesAnExactTargetWorkspaceWithoutAgentApiCredentials() throws Exception {
        var adapter = adapter(request -> { throw new AssertionError("discovering launch support does not start a process"); });
        var exposed = environment();
        exposed.remove("ANTIGRAVITY_CSRF_TOKEN");
        exposed.remove("ANTIGRAVITY_LS_ADDRESS");
        var target = new WakeTransport.Target(TARGET.hostSessionId(), temporary.toString());
        assertTrue(adapter.managedLauncher(exposed, target).isPresent());
        assertTrue(adapter.managedLauncher(exposed, new WakeTransport.Target(TARGET.hostSessionId(), "relative")).isEmpty());
        exposed.put("ANTIGRAVITY_CONVERSATION_ID", "wrong-root");
        assertTrue(adapter.managedLauncher(exposed, target).isEmpty());
    }

    @Test @Timeout(20)
    void acpKeepsTheExactSessionAndSubmitsOnlyTheMarker() throws Exception {
        Path requests = temporary.resolve("requests.jsonl");
        try (var process = acpProbe(requests, false);
                var conversation = AntigravityManagedConversation.open(process, temporary, JSON, launch("chosen-effort"))) {
            assertEquals(CHILD, conversation.threadId());
            assertEquals(4, requests(requests).size(), "creating a session does not start a model turn");
            assertInstanceOf(WakeTransport.Outcome.NotSent.class, conversation.wake("sibling", marker()));
            assertInstanceOf(WakeTransport.Outcome.NotSent.class, conversation.wake(CHILD, "message body"));
            String first = marker();
            String second = marker();
            assertInstanceOf(WakeTransport.Outcome.Submitted.class, conversation.wake(CHILD, first));
            assertInstanceOf(WakeTransport.Outcome.Submitted.class, conversation.wake(CHILD, second));
            assertEquals(0, process.waitFor());

            var messages = requests(requests);
            assertEquals(List.of("initialize", "session/new", "session/set_config_option", "session/set_config_option",
                    "session/prompt", "", "session/prompt"), messages.stream().map(node -> node.path("method").stringValue("")).toList());
            assertEquals(1, messages.getFirst().path("params").path("protocolVersion").intValue());
            assertEquals(temporary.toString(), messages.get(1).path("params").path("cwd").stringValue());
            assertEquals(JSON.valueToTree(Map.of("sessionId", CHILD, "configId", "model", "value", "chosen-model")), messages.get(2).path("params"));
            assertEquals(JSON.valueToTree(Map.of("sessionId", CHILD, "configId", "thought_level", "value", "chosen-effort")), messages.get(3).path("params"));
            assertEquals(JSON.valueToTree(Map.of("sessionId", CHILD, "prompt", List.of(Map.of("type", "text", "text", first)))), messages.get(4).path("params"));
            assertEquals("cancelled", messages.get(5).path("result").path("outcome").path("outcome").stringValue());
            assertEquals(JSON.valueToTree(Map.of("sessionId", CHILD, "prompt", List.of(Map.of("type", "text", "text", second)))), messages.get(6).path("params"));
        }
    }

    @Test @Timeout(20)
    void acpRejectsAnUnlistedEffortOrAHostThatChangesTheSelectedModel() throws Exception {
        Path unlisted = temporary.resolve("unlisted.jsonl");
        try (var process = acpProbe(unlisted, false)) {
            assertThrows(NativeWakeAdapter.UnsupportedSelection.class,
                    () -> AntigravityManagedConversation.open(process, temporary, JSON, launch("unknown-effort")));
            assertFalse(process.isAlive());
            assertEquals(3, requests(unlisted).size());
        }
        Path changed = temporary.resolve("changed-model.jsonl");
        try (var process = acpProbe(changed, true)) {
            assertThrows(NativeWakeAdapter.UnsupportedSelection.class,
                    () -> AntigravityManagedConversation.open(process, temporary, JSON, launch("chosen-effort")));
            assertFalse(process.isAlive());
            assertEquals(4, requests(changed).size());
        }
    }

    private static Launch launch(String effort) {
        return new Launch(UUID.randomUUID().toString(), "chosen-model", effort);
    }

    private static String marker() {
        return "[sennard wake:" + UUID.randomUUID() + "]";
    }

    private ToolProcess.Stdio acpProbe(Path requests, boolean changedModel) throws IOException {
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx" + System.getProperty("sennard.test.childHeap"),
                "-cp", System.getProperty("sennard.test.classpath"), AcpProbe.class.getName(), requests.toString(), Boolean.toString(changedModel));
        return ToolProcess.startStdio(command, temporary, Map.of());
    }

    private static List<JsonNode> requests(Path requests) throws IOException {
        return Files.readAllLines(requests).stream().map(JSON::readTree).toList();
    }

    public static final class AcpProbe {
        private static final FileOutputStream OUTPUT = new FileOutputStream(FileDescriptor.out);

        public static void main(String[] args) throws IOException {
            int prompts = 0;
            Path requests = Path.of(args[0]);
            boolean changedModel = Boolean.parseBoolean(args[1]);
            try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                    var log = Files.newBufferedWriter(requests)) {
                for (String line; (line = input.readLine()) != null;) {
                    log.write(line);
                    log.newLine();
                    log.flush();
                    JsonNode request = JSON.readTree(line);
                    Object result = switch (request.path("method").stringValue()) {
                        case "initialize" -> Map.of("protocolVersion", 1);
                        case "session/new" -> Map.of("sessionId", CHILD, "configOptions", options("default-model", "default-effort"));
                        case "session/set_config_option" -> {
                            boolean model = "model".equals(request.path("params").path("configId").stringValue());
                            yield Map.of("configOptions", options(model || !changedModel ? "chosen-model" : "default-model",
                                    model ? "default-effort" : "chosen-effort"));
                        }
                        case "session/prompt" -> {
                            if (++prompts == 1) {
                                output(Map.of("jsonrpc", "2.0", "id", "permission", "method", "session/request_permission", "params", Map.of("sessionId", CHILD)));
                                log.write(input.readLine());
                                log.newLine();
                                log.flush();
                            }
                            output(Map.of("jsonrpc", "2.0", "method", "session/update", "params", Map.of("sessionId", CHILD, "update", Map.of(
                                    "sessionUpdate", "agent_message_chunk", "content", Map.of("type", "text", "text", "private model output")))));
                            yield Map.of("stopReason", "end_turn");
                        }
                        default -> throw new IOException("Unexpected ACP request");
                    };
                    output(Map.of("jsonrpc", "2.0", "id", request.path("id"), "result", result));
                    if (prompts == 2) {
                        return;
                    }
                }
            }
        }

        private static void output(Map<String, ?> frame) throws IOException {
            OUTPUT.write(JSON.writeValueAsBytes(frame));
            OUTPUT.write('\n');
            OUTPUT.flush();
        }

        private static List<Map<String, Object>> options(String model, String effort) {
            var selectedEfforts = "chosen-model".equals(model) ? List.of(Map.of("value", "default-effort"), Map.of("value", "chosen-effort"))
                    : List.of(Map.of("value", "default-effort"));
            return List.of(Map.of("id", "model", "type", "select", "currentValue", model,
                    "options", List.of(Map.of("value", "default-model"), Map.of("value", "chosen-model"))),
                    Map.of("id", "thought_level", "type", "select", "currentValue", effort, "options", selectedEfforts));
        }
    }

    @Test void missingCredentialsAndNonLoopbackEndpointsCannotConnect() throws Exception {
        var adapter = adapter(request -> { throw new AssertionError("connect never launches"); });
        Map<String, String> valid = environment();
        for (String name : List.of("ANTIGRAVITY_PROJECT_ID", "ANTIGRAVITY_CSRF_TOKEN", "ANTIGRAVITY_LS_ADDRESS")) {
            var missing = new HashMap<>(valid);
            missing.remove(name);
            assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(missing, TARGET));
            missing.put(name, "");
            assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(missing, TARGET));
        }
        for (String address : List.of("example.com:1234", "192.168.1.2:1234", "localhost.evil:1234", "http://127.0.0.1:1234",
                "127.0.0.1:0", "127.0.0.1:65536", "127.999.0.1:1234", "127.00.0.1:1234", "[::]:1234", "127.0.0.1:1234/path")) {
            var changed = new HashMap<>(valid);
            changed.put("ANTIGRAVITY_LS_ADDRESS", address);
            assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(changed, TARGET), address);
        }
        for (String address : List.of("localhost:1234", "127.0.0.1:1234", "127.2.3.4:65535", "[::1]:1234")) {
            var changed = new HashMap<>(valid);
            changed.put("ANTIGRAVITY_LS_ADDRESS", address);
            assertInstanceOf(WakeTransport.Availability.Ready.class, adapter.connect(changed, TARGET), address);
        }
    }

    @Test void foreignExplicitExecutableDisablesBothLaunchPathsWithoutPathFallback() throws Exception {
        var adapter = adapter(request -> { throw new AssertionError("discovery never launches"); });
        var exposed = environment();
        Path foreign = NativeLauncherFixture.executable(temporary.resolve("foreign.exe"), NativeLauncherFixture.foreignHeader());
        exposed.put("PATH", temporary.toString());
        exposed.put("ANTIGRAVITY_AGENTAPI_EXE", foreign.toString());
        var managedTarget = new WakeTransport.Target(TARGET.hostSessionId(), temporary.toString());
        assertAll(
                () -> assertEquals(new WakeTransport.Availability.Missing("AGY_NATIVE_EXECUTABLE_MISSING"), adapter.connect(exposed, TARGET)),
                () -> assertTrue(adapter.managedLauncher(exposed, managedTarget).isEmpty()));
    }

    @Test void onlyExplicitNativeExecutableOrExposedPathIsUsed() throws Exception {
        var adapter = adapter(request -> { throw new AssertionError("connect never launches"); });
        var exposed = environment();
        assertInstanceOf(WakeTransport.Availability.Ready.class, adapter.connect(exposed, TARGET));
        exposed.remove("ANTIGRAVITY_AGENTAPI_EXE");
        assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(exposed, TARGET));
        exposed.put("PATH", temporary.toString());
        assertInstanceOf(WakeTransport.Availability.Ready.class, adapter.connect(exposed, TARGET));
        exposed.put("ANTIGRAVITY_AGENTAPI_EXE", "");
        assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(exposed, TARGET), "present empty path cannot fall back");
        for (String name : List.of("unsafe.cmd", "unsafe.bat", "unsafe.PS1")) {
            Path script = NativeLauncherFixture.executable(temporary.resolve(name), NativeLauncherFixture.localHeader());
            exposed.put("ANTIGRAVITY_AGENTAPI_EXE", script.toString());
            assertEquals(new WakeTransport.Availability.Missing("AGY_NATIVE_EXECUTABLE_MISSING"), adapter.connect(exposed, TARGET), name);
        }
        exposed.put("ANTIGRAVITY_AGENTAPI_EXE", "relative-agy.exe");
        assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(exposed, TARGET));
    }

    @Test void onlySendRunsAndCapturedProjectEnvironmentCannotBeReplaced() throws Exception {
        var exposed = environment();
        exposed.put("UNRELATED_SETTING", "fixture-value");
        exposed.put("APPDATA", "managed-only");
        exposed.put("Path", "fixture-path");
        Map<String, String> expectedEnvironment = Map.of(
                "ANTIGRAVITY_LS_ADDRESS", exposed.get("ANTIGRAVITY_LS_ADDRESS"),
                "ANTIGRAVITY_PROJECT_ID", exposed.get("ANTIGRAVITY_PROJECT_ID"),
                "ANTIGRAVITY_CSRF_TOKEN", exposed.get("ANTIGRAVITY_CSRF_TOKEN"),
                "ANTIGRAVITY_CONVERSATION_ID", exposed.get("ANTIGRAVITY_CONVERSATION_ID"),
                "Path", "fixture-path");
        List<ToolProcess.Request> calls = new ArrayList<>();
        String executable = exposed.get("ANTIGRAVITY_AGENTAPI_EXE");
        var connection = ready(exposed, request -> {
            calls.add(request);
            return new ToolProcess.Finished(0, new byte[0]);
        });
        exposed.put("ANTIGRAVITY_PROJECT_ID", "other-project");
        exposed.put("ANTIGRAVITY_CSRF_TOKEN", "other-token");
        assertInstanceOf(WakeTransport.Outcome.Submitted.class, connection.wake("conversation-a", "[sennard wake:synthetic]"));
        assertEquals(1, calls.size(), "no metadata or probe command");
        ToolProcess.Request request = calls.getFirst();
        assertEquals(List.of(executable, "agentapi", "send-message", "conversation-a", "[sennard wake:synthetic]"), request.command());
        assertEquals(Duration.ofSeconds(10), request.timeout());
        assertFalse(request.inheritEnvironment());
        assertEquals(ToolProcess.Stderr.DISCARD, request.stderr());
        assertEquals(65536, request.maxOutputBytes());
        assertEquals(expectedEnvironment, request.environment());
    }

    @Test void actualSessionStartTargetFencesEverySendEvenWithoutOptionalConversationEnvironment() throws Exception {
        var exposed = environment();
        exposed.remove("ANTIGRAVITY_CONVERSATION_ID");
        AtomicInteger calls = new AtomicInteger();
        var connection = ready(exposed, request -> {
            calls.incrementAndGet(); return new ToolProcess.Finished(0, new byte[0]);
        });
        for (String session : List.of("other-conversation", "", "-option", " conversation-a", "conversation-a ")) {
            assertEquals(new WakeTransport.Outcome.NotSent("AGY_CONVERSATION_MISMATCH"), connection.wake(session, "marker"));
        }
        assertInstanceOf(WakeTransport.Outcome.NotSent.class, connection.wake(null, "marker"));
        assertEquals(0, calls.get());
        assertInstanceOf(WakeTransport.Outcome.Submitted.class, connection.wake("conversation-a", "marker"));
        assertEquals(1, calls.get());
    }

    @Test void invalidOrInheritedConversationTargetCannotConnect() throws Exception {
        var adapter = adapter(request -> { throw new AssertionError("invalid target never launches"); });
        var exposed = environment();
        for (String session : List.of("", " ", "-option", "conversation-a\n", "other-conversation", " conversation-a", "conversation-a ")) {
            assertInstanceOf(WakeTransport.Availability.Missing.class,
                    adapter.connect(exposed, new WakeTransport.Target(session, "/work")));
        }
        assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(exposed, null));
        assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(exposed, new WakeTransport.Target(null, "/work")));
        for (String conversation : List.of("", " ", "other-conversation", "conversation-a\n")) {
            exposed.put("ANTIGRAVITY_CONVERSATION_ID", conversation);
            assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(exposed, TARGET));
        }
    }

    @Test void postSendFailuresStayUnknownAndPreStartFailureIsNotSent() throws Exception {
        Map<ToolProcess.Outcome, String> failures = Map.of(
                new ToolProcess.Finished(1, "synthetic-token".getBytes(StandardCharsets.UTF_8)), "AGY_SEND_NONZERO",
                new ToolProcess.TimedOut(), "AGY_SEND_TIMED_OUT", new ToolProcess.Overflowed(), "AGY_SEND_OUTPUT_LIMIT");
        for (var entry : failures.entrySet()) {
            AtomicInteger calls = new AtomicInteger();
            var outcome = ready(environment(), request -> { calls.incrementAndGet(); return entry.getKey(); }).wake("conversation-a", "marker");
            assertEquals(new WakeTransport.Outcome.Unknown(entry.getValue()), outcome);
            assertEquals(1, calls.get());
            assertFalse(outcome.toString().contains("synthetic-token"));
        }
        Map<WakeProcess.Phase, WakeTransport.Outcome> expected = Map.of(
                WakeProcess.Phase.STARTED, new WakeTransport.Outcome.Unknown("AGY_SEND_IO_AFTER_START"),
                WakeProcess.Phase.NOT_STARTED, new WakeTransport.Outcome.NotSent("AGY_SEND_START_FAILED"));
        for (WakeProcess.Phase phase : WakeProcess.Phase.values()) {
            IOException cause = new IOException("synthetic-token");
            WakeProcess.Failure failure = new WakeProcess.Failure(phase, cause);
            assertSame(cause, failure.getCause(), "the production port preserves the failure cause");
            assertEquals(phase, failure.phase());
            AtomicInteger calls = new AtomicInteger();
            var outcome = ready(environment(), request -> { calls.incrementAndGet(); throw failure; }).wake("conversation-a", "marker");
            assertEquals(expected.get(phase), outcome);
            assertEquals(1, calls.get());
        }
        assertEquals(new WakeTransport.Outcome.NotSent("AGY_SEND_PREPARATION_FAILED"), ready(environment(), request -> {
            throw new IOException("synthetic-token");
        }).wake("conversation-a", "marker"));
    }

    @Test void failureLogsKeepCauseAndStackWithoutExceptionText() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(AntigravityWakeTransport.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        try {
            var cause = new IOException("synthetic-token");
            cause.addSuppressed(new IllegalStateException("synthetic-token"));
            ready(environment(), request -> { throw new WakeProcess.Failure(WakeProcess.Phase.STARTED, cause); })
                    .wake("conversation-a", "marker");
            assertEquals(1, logged.list.size());
            var line = logged.list.getFirst();
            assertEquals("AGY_SEND_FAILED", line.getFormattedMessage());
            assertNotNull(line.getThrowableProxy());
            String rendered = line.getFormattedMessage() + ThrowableProxyUtil.asString(line.getThrowableProxy());
            assertTrue(rendered.contains(IOException.class.getName()));
            assertTrue(rendered.contains(IllegalStateException.class.getName()));
            assertTrue(rendered.contains("AntigravityWakeTransportTest"));
            assertFalse(rendered.contains("synthetic-token"));
        } finally {
            logger.detachAppender(logged);
            logged.stop();
        }
    }

    @Test void alreadyInterruptedThreadNeverStartsSend() throws Exception {
        var connection = ready(environment(), request -> { throw new AssertionError("interrupted before start"); });
        try {
            Thread.currentThread().interrupt();
            assertEquals(new WakeTransport.Outcome.NotSent("AGY_SEND_CANCELLED_BEFORE_START"), connection.wake("conversation-a", "marker"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test void sendInterruptRestoresInterruptAndRemainsUncertain() throws Exception {
        try {
            var outcome = ready(environment(), request -> { throw new InterruptedException("synthetic-token"); }).wake("conversation-a", "marker");
            assertEquals(new WakeTransport.Outcome.Unknown("AGY_SEND_CANCELLED"), outcome);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private Map<String, String> environment() throws Exception {
        Path executable = NativeLauncherFixture.executable(temporary.resolve("agy.exe"), NativeLauncherFixture.localHeader());
        return new HashMap<>(Map.of("ANTIGRAVITY_AGENTAPI_EXE", executable.toString(), "ANTIGRAVITY_LS_ADDRESS", "127.0.0.1:1234",
                "ANTIGRAVITY_CSRF_TOKEN", "synthetic-token", "ANTIGRAVITY_PROJECT_ID", "project-a", "ANTIGRAVITY_CONVERSATION_ID", "conversation-a"));
    }
    private static AntigravityWakeTransport adapter(WakeProcess process) {
        return new AntigravityWakeTransport(process);
    }
    private static WakeTransport.Connection ready(Map<String, String> environment, WakeProcess process) {
        return assertInstanceOf(WakeTransport.Availability.Ready.class, adapter(process).connect(environment, TARGET)).connection();
    }
}
