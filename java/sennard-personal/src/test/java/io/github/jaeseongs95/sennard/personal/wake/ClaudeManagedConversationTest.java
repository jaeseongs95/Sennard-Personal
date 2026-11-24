// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.Launch;
import io.github.jaeseongs95.sennard.protocol.ToolProcess;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Outcome;
import tools.jackson.databind.json.JsonMapper;

class ClaudeManagedConversationTest {
    private static final String MODEL = "selected-model";
    private static final String EFFORT = "selected-effort";
    private static final String MARKER = "[sennard wake:01a14677-baf4-4350-922b-d5d63b40c168]";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final Launch launch = new Launch(UUID.randomUUID().toString(), MODEL, EFFORT);
    private final List<List<String>> commands = new ArrayList<>();
    private final List<ToolProcess.Stdio> processes = new ArrayList<>();

    @TempDir Path workspace;

    @Test
    void passivelyCreatesTheAssignedIdAndWaitsForAnExactResumeReceipt() throws Exception {
        try (var conversation = open("startup", "busy")) {
            assertEquals(launch.id(), conversation.threadId());
            assertEquals(1, commands.size());
            assertFalse(processes.getFirst().isAlive(), "The creating process must exit before resume.");
            assertInstanceOf(Outcome.NotSent.class, conversation.wake(UUID.randomUUID().toString(), MARKER));
            assertEquals(1, commands.size());
            assertInstanceOf(Outcome.Submitted.class, conversation.wake(launch.id(), MARKER));
            assertEquals(2, commands.size());
            assertTrue(processes.getLast().isAlive());
            assertInstanceOf(Outcome.NotSent.class, conversation.wake(launch.id(), MARKER));
            assertEquals(2, commands.size());
            assertEquals("--session-id", commands.getFirst().get(commands.getFirst().size() - 2));
            assertEquals("--resume", commands.getLast().get(commands.getLast().size() - 2));
            for (List<String> command : commands) {
                assertEquals(launch.id(), command.getLast());
                assertTrue(command.contains("--model=" + MODEL));
                assertTrue(command.contains("--effort=" + EFFORT));
                assertTrue(command.contains("--replay-user-messages"));
            }
        }
        assertFalse(processes.getLast().isAlive());
    }

    @Test
    void refusesTheSelectionBeforeGivingTheHostAnyInput() {
        assertThrows(NativeWakeAdapter.UnsupportedSelection.class, () -> open("unsupported", "unused"));
        assertEquals(1, commands.size());
        assertFalse(processes.getFirst().isAlive());
    }

    @Test
    void ignoresBlankLinesBeforeAndAfterStartupFrames() throws Exception {
        try (var conversation = open("blank-lines-startup", "unused")) {
            assertEquals(launch.id(), conversation.threadId());
        }
    }

    @Test
    void distinguishesCleanEofFromAnUnterminatedInitializationFrame() {
        assertEquals("Claude ended before initialization.",
                assertThrows(EOFException.class, () -> open("clean-eof", "unused")).getMessage());
        assertEquals("Claude returned an incomplete stream frame.",
                assertThrows(EOFException.class, () -> open("partial-eof", "unused")).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-startup-id", "queried-on-startup"})
    void requiresPassiveStartupOfTheAssignedId(String behavior) {
        assertThrows(IOException.class, () -> open(behavior, "unused"));
        assertFalse(processes.getFirst().isAlive());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-resume-id", "wrong-input-id", "text-replay"})
    void doesNotReportSubmittedForAForeignReceipt(String behavior) throws Exception {
        try (var conversation = open("startup", behavior)) {
            assertInstanceOf(Outcome.Unknown.class, conversation.wake(launch.id(), MARKER));
            assertFalse(processes.getLast().isAlive());
        }
    }

    @Test
    void rejectsForeignConversationBeforeSendingTheMarker() throws Exception {
        try (var conversation = open("startup", "wrong-initialization-id")) {
            assertInstanceOf(Outcome.NotSent.class, conversation.wake(launch.id(), MARKER));
            assertFalse(processes.getLast().isAlive());
        }
    }

    @Test
    void acknowledgesInputEvenWhenModelTurnFails() throws Exception {
        try (var conversation = open("startup", "model-error")) {
            assertInstanceOf(Outcome.Submitted.class, conversation.wake(launch.id(), MARKER));
        }
    }

    private ClaudeManagedConversation open(String startup, String resume) throws IOException, InterruptedException {
        return ClaudeManagedConversation.open(Path.of("claude"), launch, MAPPER, Duration.ofSeconds(8), command -> {
            commands.add(command);
            boolean initial = "--session-id".equals(command.get(command.size() - 2));
            var process = ToolProcess.startStdio(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx" + System.getProperty("sennard.test.childHeap"), "-cp", System.getProperty("sennard.test.classpath"),
                    Probe.class.getName(), command.getLast(), initial ? startup : resume), workspace, Map.of());
            processes.add(process);
            return process;
        });
    }

    public static final class Probe {
        private static final FileOutputStream OUTPUT = new FileOutputStream(FileDescriptor.out);

        public static void main(String[] args) throws Exception {
            String session = args[0];
            String behavior = args[1];
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            var initialize = MAPPER.readTree(input.readLine());
            require("initialize".equals(initialize.path("request").path("subtype").asString()));
            if ("clean-eof".equals(behavior)) {
                return;
            }
            if ("partial-eof".equals(behavior)) {
                OUTPUT.write('{');
                OUTPUT.flush();
                return;
            }
            boolean blankLines = "blank-lines-startup".equals(behavior);
            if (blankLines) {
                OUTPUT.write('\n');
                OUTPUT.flush();
            }
            boolean unsupported = "unsupported".equals(behavior);
            if (behavior.equals("wrong-initialization-id")) {
                emit(Map.of("type", "system", "subtype", "init", "session_id", UUID.randomUUID().toString()));
            }
            emit(Map.of("type", "control_response", "response", Map.of("subtype", "success", "request_id", initialize.path("request_id").asString(),
                    "response", Map.of("models", List.of(Map.of("value", MODEL, "supportedEffortLevels", List.of(unsupported ? "other" : EFFORT)))))));
            if (unsupported) {
                require(input.readLine() == null);
                return;
            }
            String userInput = input.readLine();
            if (userInput == null && behavior.equals("wrong-initialization-id")) {
                return;
            }
            var user = MAPPER.readTree(userInput);
            require("user".equals(user.path("type").asString()) && session.equals(user.path("session_id").asString()));
            boolean passive = behavior.equals("startup") || behavior.endsWith("startup") || behavior.equals("wrong-startup-id");
            require(user.path("shouldQuery").isBoolean() && user.path("shouldQuery").asBoolean() != passive);
            require(user.path("isSynthetic").isBoolean() && user.path("isSynthetic").asBoolean() == passive);
            if (!passive) {
                require(MARKER.equals(user.path("message").path("content").asString()));
            }
            require(input.readLine() == null);
            if (behavior.equals("wrong-startup-id") || behavior.equals("wrong-resume-id")) {
                session = UUID.randomUUID().toString();
            }
            emit(Map.of("type", "system", "subtype", "init", "session_id", session));
            if (!passive) {
                String uuid = behavior.equals("wrong-input-id") ? UUID.randomUUID().toString() : user.path("uuid").asString();
                emit(Map.of("type", "user", "isReplay", behavior.equals("text-replay") ? "true" : true, "session_id", session, "uuid", uuid));
                if (behavior.equals("busy")) {
                    Thread.sleep(Duration.ofSeconds(60));
                }
            }
            boolean modelError = behavior.equals("model-error");
            emit(Map.of("type", "result", "subtype", modelError ? "error_during_execution" : "success",
                    "session_id", session, "is_error", modelError, "modelUsage", Map.of(),
                    "num_turns", behavior.equals("queried-on-startup") || !passive ? 1 : 0));
            if (blankLines) {
                OUTPUT.write('\n');
                OUTPUT.flush();
            }
            if (modelError) {
                System.exit(1);
            }
        }

        private static void emit(Map<String, ?> frame) throws IOException {
            OUTPUT.write(MAPPER.writeValueAsBytes(frame));
            OUTPUT.write('\n');
            OUTPUT.flush();
        }

        private static void require(boolean condition) {
            if (!condition) {
                throw new IllegalArgumentException("Incorrect CLI input.");
            }
        }
    }
}
