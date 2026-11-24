// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.Launch;
import io.github.jaeseongs95.sennard.protocol.ToolProcess;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Outcome;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/** Creates a Claude Code conversation without a model turn and resumes it for each exact native input. */
final class ClaudeManagedConversation implements NativeWakeAdapter.ManagedConversation, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ClaudeManagedConversation.class.getName());
    private static final String STARTUP_NOTE = "Sennard managed session initialized.";
    private static final JsonNode SUCCESS = JsonNodeFactory.instance.stringNode("success");
    private final Path executable;
    private final Launch launch;
    private final JsonMapper mapper;
    private final Duration deadline;
    private final Starter starter;
    private Exchange active;
    private boolean closed;

    @FunctionalInterface
    interface Starter {
        ToolProcess.Stdio start(List<String> command) throws IOException;
    }

    static ClaudeManagedConversation open(Path executable, Launch launch, JsonMapper mapper, Duration deadline, Starter starter)
            throws IOException, InterruptedException {
        var conversation = new ClaudeManagedConversation(executable, launch, mapper, deadline, starter);
        try (var exchange = conversation.start("--session-id")) {
            var startup = new ManagedExchangeDeadline(deadline, "Claude managed startup timed out.",
                    "Claude managed startup failed.", true);
            startup.run(() -> {
                conversation.requireListedSelection(exchange.initialize());
                exchange.submit(launch.id(), STARTUP_NOTE, true);
                boolean persisted = false;
                JsonNode frame;
                while ((frame = exchange.read()) != null) {
                    conversation.checkSession(frame);
                    if (type(frame, "assistant")) {
                        throw new IOException("Claude queried the model during managed startup.");
                    }
                    if (type(frame, "result")) {
                        JsonNode turns = frame.path("num_turns");
                        JsonNode usage = frame.path("modelUsage");
                        if (!launch.id().equals(frame.path("session_id").stringValue(null)) || !successful(frame)
                                || !turns.isInt() || turns.asInt() != 0
                                || !(usage.isMissingNode() || usage.isObject() && usage.isEmpty())) {
                            throw new IOException("Claude did not finish passive managed startup.");
                        }
                        persisted = true;
                    }
                }
                if (exchange.process.waitFor() != 0 || !persisted) {
                    throw new IOException("Claude did not persist the assigned conversation.");
                }
                return null;
            });
        }
        return conversation;
    }

    private ClaudeManagedConversation(Path executable, Launch launch, JsonMapper mapper, Duration deadline, Starter starter) {
        this.executable = executable;
        this.launch = launch;
        this.mapper = mapper;
        this.deadline = deadline;
        this.starter = starter;
    }

    @Override
    public String threadId() {
        return launch.id();
    }

    @Override
    public synchronized boolean live() {
        return !closed;
    }

    @Override
    public synchronized Outcome wake(String target, String marker) {
        if (closed || active != null || !threadId().equals(target) || !NativeWakeProtocol.validMarker(marker)) {
            return new Outcome.NotSent("MANAGED_CLAUDE_TARGET_UNAVAILABLE");
        }
        final Exchange exchange;
        try {
            exchange = start("--resume");
        } catch (IOException unavailable) {
            LOG.log(System.Logger.Level.DEBUG, "Claude resume did not start ({0})", unavailable.getClass().getSimpleName());
            return new Outcome.NotSent("MANAGED_CLAUDE_RESUME_NOT_STARTED");
        }
        active = exchange;
        var receipt = new CompletableFuture<Outcome>();
        Thread.ofVirtual().start(() -> receiveTurn(exchange, marker, receipt));
        try {
            return receipt.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException unavailable) {
            LOG.log(System.Logger.Level.DEBUG, "Claude did not confirm input before the handoff deadline");
            exchange.close();
            return new Outcome.Unknown("MANAGED_CLAUDE_RECEIPT_TIMEOUT");
        } catch (InterruptedException interrupted) {
            exchange.close();
            Thread.currentThread().interrupt();
            return new Outcome.Unknown("MANAGED_CLAUDE_INTERRUPTED");
        } catch (ExecutionException failed) {
            LOG.log(System.Logger.Level.DEBUG, "Claude receipt failed ({0})", failed.getClass().getSimpleName());
            exchange.close();
            return new Outcome.Unknown("MANAGED_CLAUDE_OUTCOME_UNAVAILABLE");
        }
    }

    private void receiveTurn(Exchange exchange, String marker, CompletableFuture<Outcome> receipt) {
        boolean sending = false;
        try (exchange) {
            requireListedSelection(exchange.initialize());
            sending = true;
            String uuid = exchange.submit(threadId(), marker, false);
            JsonNode frame;
            while ((frame = exchange.read()) != null) {
                checkSession(frame);
                JsonNode replay = frame.path("isReplay");
                if (type(frame, "user") && replay.isBoolean() && replay.asBoolean()
                        && uuid.equals(frame.path("uuid").stringValue(null)) && threadId().equals(frame.path("session_id").stringValue(null))) {
                    receipt.complete(new Outcome.Submitted());
                }
                if (type(frame, "result") && !successful(frame)) {
                    LOG.log(System.Logger.Level.DEBUG, "Claude reported an unsuccessful managed turn");
                }
            }
        } catch (IOException | JacksonException failed) {
            LOG.log(System.Logger.Level.DEBUG, "Claude managed stream ended ({0})", failed.getClass().getSimpleName());
        } finally {
            receipt.complete(sending ? new Outcome.Unknown("MANAGED_CLAUDE_RECEIPT_UNCONFIRMED")
                    : new Outcome.NotSent("MANAGED_CLAUDE_RESUME_UNAVAILABLE"));
            finished(exchange);
        }
    }

    private synchronized void finished(Exchange exchange) {
        if (active == exchange) {
            active = null;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (active != null) {
            active.close();
        }
    }

    private Exchange start(String sessionOption) throws IOException {
        var command = List.of(executable.toString(), "--print", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                "--replay-user-messages", "--model=" + launch.model(), "--effort=" + launch.effort(), sessionOption, launch.id());
        return new Exchange(starter.start(command));
    }

    private void requireListedSelection(JsonNode initialization) throws IOException {
        JsonNode models = initialization.path("models");
        if (!models.isArray()) {
            throw new IOException("Claude returned an invalid model catalog.");
        }
        for (JsonNode model : models) {
            if (launch.model().equals(model.path("value").stringValue(null)) || launch.model().equals(model.path("resolvedModel").stringValue(null))) {
                for (JsonNode effort : model.path("supportedEffortLevels")) {
                    if (launch.effort().equals(effort.stringValue(null))) {
                        return;
                    }
                }
            }
        }
        throw new NativeWakeAdapter.UnsupportedSelection();
    }

    private void checkSession(JsonNode frame) throws IOException {
        if (frame.hasNonNull("session_id") && !threadId().equals(frame.path("session_id").stringValue(null))) {
            throw new IOException("Claude reported a different conversation.");
        }
    }

    private static boolean type(JsonNode frame, String value) {
        return value.equals(frame.path("type").stringValue(null));
    }

    private static boolean successful(JsonNode frame) {
        JsonNode error = frame.path("is_error");
        return SUCCESS.equals(frame.path("subtype")) && error.isBoolean() && !error.asBoolean();
    }

    private final class Exchange implements AutoCloseable {
        private final ToolProcess.Stdio process;
        private final ObjectReader reader;
        private final ManagedStreamFrames frames;

        Exchange(ToolProcess.Stdio process) {
            this.process = process;
            this.reader = WakeJson.strictReader(mapper, JsonNode.class);
            this.frames = new ManagedStreamFrames(process.output(), process.input(),
                    "Claude stream frame exceeds its limit.",
                    () -> new EOFException("Claude returned an incomplete stream frame."));
        }

        JsonNode initialize() throws IOException {
            String id = UUID.randomUUID().toString();
            write(mapper.valueToTree(Map.of("type", "control_request", "request_id", id, "request", Map.of("subtype", "initialize"))));
            JsonNode frame;
            while ((frame = read()) != null) {
                checkSession(frame);
                if (type(frame, "assistant") || type(frame, "result")) {
                    throw new IOException("Claude produced a turn before receiving input.");
                }
                JsonNode response = frame.path("response");
                if (type(frame, "control_response") && id.equals(response.path("request_id").stringValue(null))) {
                    if (SUCCESS.equals(response.path("subtype"))) {
                        return response.path("response");
                    }
                    throw new IOException("Claude rejected initialization.");
                }
            }
            throw new EOFException("Claude ended before initialization.");
        }

        String submit(String sessionId, String content, boolean passive) throws IOException {
            String uuid = UUID.randomUUID().toString();
            var frame = mapper.createObjectNode().put("type", "user").put("uuid", uuid).put("session_id", sessionId).putNull("parent_tool_use_id")
                    .put("isSynthetic", passive).put("shouldQuery", !passive);
            frame.putObject("message").put("role", "user").put("content", content);
            write(frame);
            process.input().close();
            return uuid;
        }

        private void write(JsonNode frame) throws IOException {
            frames.write(mapper.writeValueAsBytes(frame));
        }

        JsonNode read() throws IOException {
            byte[] bytes;
            do {
                bytes = frames.read();
                if (bytes == null) {
                    return null;
                }
            } while (bytes.length == 0);
            JsonNode frame = reader.readValue(bytes);
            if (!frame.isObject()) {
                throw new IOException("Claude returned a non-object stream frame.");
            }
            return frame;
        }

        @Override
        public void close() {
            process.close();
        }
    }
}
