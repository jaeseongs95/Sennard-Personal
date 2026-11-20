// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol;
import io.github.jaeseongs95.sennard.protocol.ToolProcess;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** Drives one managed conversation over the Codex app-server stdio protocol. Model events never leave this connection. */
final class CodexManagedConversation implements NativeWakeAdapter.ManagedConversation, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(CodexManagedConversation.class.getName());
    private static final int MAX_FRAME_BYTES = 1024 * 1024;
    private final ToolProcess.Stdio process;
    private final BufferedInputStream output;
    private final JsonMapper mapper;
    private final ObjectReader reader;
    private final Duration deadline;
    private final String model;
    private final String effort;
    private final String threadId;
    private int requestId;
    private FutureTask<Void> runningTurn;

    static CodexManagedConversation open(Path executable, Path workspace, Map<String, String> environment, JsonMapper mapper, Duration deadline,
            String model, String effort, String resumeId) throws IOException, InterruptedException {
        var process = ToolProcess.startStdio(
                List.of(executable.toString(), "app-server", "-c", "features.multi_agent=false", "--listen", "stdio://"), workspace, environment);
        return open(process, mapper, deadline, model, effort, resumeId);
    }

    static CodexManagedConversation open(ToolProcess.Stdio process, JsonMapper mapper, Duration deadline, String model, String effort, String resumeId)
            throws IOException, InterruptedException {
        try {
            return new CodexManagedConversation(process, mapper, deadline, model, effort, resumeId);
        } catch (IOException | InterruptedException | RuntimeException failure) {
            process.close();
            throw failure;
        }
    }

    private CodexManagedConversation(ToolProcess.Stdio process, JsonMapper mapper, Duration deadline, String model, String effort, String resumeId)
            throws IOException, InterruptedException {
        this.process = process;
        this.mapper = mapper;
        this.deadline = deadline;
        this.model = model;
        this.effort = effort;
        output = new BufferedInputStream(process.output());
        reader = mapper.readerFor(JsonNode.class).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        threadId = timed(() -> {
            String version = Objects.requireNonNullElse(CodexManagedConversation.class.getPackage().getImplementationVersion(), "unknown");
            request("initialize", Map.of("clientInfo", Map.of("name", "sennard", "title", "Sennard", "version", version)));
            write(Map.of("method", "initialized"));
            requireListedSelection();
            JsonNode result = resumeId == null ? request("thread/start", Map.of("model", model))
                    : request("thread/resume", Map.of("threadId", resumeId, "model", model));
            String id = requiredText(result.path("thread"), "id");
            if (resumeId != null && !resumeId.equals(id)) {
                throw new IOException("The host resumed a different conversation.");
            }
            return id;
        });
    }

    @Override
    public String threadId() {
        return threadId;
    }

    private void requireListedSelection() throws IOException {
        Map<String, ?> parameters = Map.of("limit", 100, "includeHidden", true);
        while (true) {
            JsonNode page = request("model/list", parameters);
            JsonNode models = page.path("data");
            if (!models.isArray()) {
                throw new IOException("The host returned an invalid model catalog.");
            }
            for (JsonNode candidate : models) {
                if (model.equals(candidate.path("model").stringValue(null))) {
                    for (JsonNode option : candidate.path("supportedReasoningEfforts")) {
                        if (effort.equals(option.path("reasoningEffort").stringValue(null))) {
                            return;
                        }
                    }
                }
            }
            String cursor = page.path("nextCursor").stringValue(null);
            if (cursor == null) {
                throw new NativeWakeAdapter.UnsupportedSelection();
            }
            parameters = Map.of("limit", 100, "includeHidden", true, "cursor", cursor);
        }
    }

    @Override
    public synchronized WakeTransport.Outcome wake(String target, String marker) {
        if (!threadId.equals(target) || !NativeWakeProtocol.validMarker(marker) || !live()) {
            return new WakeTransport.Outcome.NotSent("MANAGED_CODEX_TARGET_UNAVAILABLE");
        }
        try {
            String turnId = timed(() -> {
                if (runningTurn != null) {
                    runningTurn.get();
                }
                JsonNode turn = request("turn/start", Map.of("threadId", threadId, "model", model, "effort", effort,
                        "input", List.of(Map.of("type", "text", "text", marker, "text_elements", List.of())))).path("turn");
                return requiredText(turn, "id");
            });
            // The host can emit more than a pipe buffer before its turn ends, even when no other wake is queued.
            runningTurn = new FutureTask<>(() -> {
                try {
                    readCompletion(turnId);
                    return null;
                } catch (IOException invalid) {
                    close();
                    throw invalid;
                }
            });
            Thread.ofVirtual().start(runningTurn);
            return new WakeTransport.Outcome.Submitted();
        } catch (InterruptedException interrupted) {
            close();
            Thread.currentThread().interrupt();
            return new WakeTransport.Outcome.Unknown("MANAGED_CODEX_INTERRUPTED");
        } catch (InvalidParameters refused) {
            LOG.log(System.Logger.Level.DEBUG, "The host rejected managed turn parameters ({0})", refused.getClass().getSimpleName());
            close();
            return new WakeTransport.Outcome.NotSent("MANAGED_CODEX_INVALID_PARAMS");
        } catch (IOException unavailable) {
            LOG.log(System.Logger.Level.DEBUG, "The managed turn outcome is unavailable ({0})", unavailable.getClass().getSimpleName());
            close();
            return new WakeTransport.Outcome.Unknown("MANAGED_CODEX_OUTCOME_UNAVAILABLE");
        }
    }

    private void readCompletion(String turnId) throws IOException {
        while (true) {
            JsonNode notification = read();
            if (notification.has("id")) {
                throw new IOException("The host sent an unexpected request or reply.");
            }
            if ("turn/completed".equals(notification.path("method").asString(null))) {
                JsonNode params = notification.path("params");
                if (!threadId.equals(requiredText(params, "threadId")) || !turnId.equals(requiredText(params.path("turn"), "id"))) {
                    throw new IOException("The host reported a different completed turn.");
                }
                return;
            }
        }
    }

    private JsonNode request(String method, Map<String, ?> params) throws IOException {
        int id = ++requestId;
        write(Map.of("id", id, "method", method, "params", params));
        while (true) {
            JsonNode message = read();
            if (!message.has("id")) {
                continue;
            }
            JsonNode number = message.path("id");
            if (!number.isInt() || number.asInt() != id || message.has("method")) {
                throw new IOException("The host sent an unexpected request or reply.");
            }
            JsonNode code = message.path("error").path("code");
            if (!message.has("result") && code.isInt() && code.asInt() == -32602) {
                throw new InvalidParameters();
            }
            if (!message.has("result") || message.has("error")) {
                throw new IOException("The host did not accept the current request.");
            }
            return message.path("result");
        }
    }

    private static final class InvalidParameters extends IOException {
        private static final long serialVersionUID = 1L;

        private InvalidParameters() {
            super("The host rejected the request parameters.");
        }
    }

    private void write(Map<String, ?> message) throws IOException {
        var input = process.input();
        input.write(mapper.writeValueAsBytes(message));
        input.write('\n');
        input.flush();
    }

    private JsonNode read() throws IOException {
        var frame = new ByteArrayOutputStream();
        for (int octet = output.read(); octet != -1; octet = output.read()) {
            if (octet == '\n') {
                try {
                    JsonNode message = reader.readValue(frame.toByteArray());
                    if (message != null && message.isObject()) {
                        return message;
                    }
                } catch (JacksonException invalid) {
                    throw new IOException("The host sent invalid protocol JSON.");
                }
                throw new IOException("The host sent an invalid protocol message.");
            }
            if (frame.size() == MAX_FRAME_BYTES) {
                throw new IOException("The host exceeded the protocol frame limit.");
            }
            frame.write(octet);
        }
        throw new IOException("The host closed its protocol output.");
    }

    private static String requiredText(JsonNode object, String field) throws IOException {
        String value = object.path(field).stringValue("");
        if (value.isBlank()) {
            throw new IOException("The host omitted a required protocol field.");
        }
        return value;
    }

    private <T> T timed(Callable<T> exchange) throws IOException, InterruptedException {
        var operation = new FutureTask<>(exchange);
        Thread.ofVirtual().start(operation);
        try {
            return operation.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timedOut) {
            throw new IOException("The host protocol did not answer before its deadline.");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("The host protocol exchange failed.");
        } finally {
            operation.cancel(true);
        }
    }

    @Override
    public boolean live() {
        return process.isAlive();
    }

    @Override
    public void close() {
        process.close();
    }
}
