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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.Launch;
import io.github.jaeseongs95.sennard.protocol.ToolProcess;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Outcome;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** One ACP connection owns the session it created until the worker releases it. */
final class AntigravityManagedConversation implements NativeWakeAdapter.ManagedConversation, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(AntigravityManagedConversation.class.getName());
    private static final int MAX_FRAME_BYTES = 1024 * 1024;
    private static final Duration CONTROL_DEADLINE = Duration.ofSeconds(10);
    private final ToolProcess.Stdio process;
    private final BufferedInputStream output;
    private final JsonMapper mapper;
    private final ObjectReader reader;
    private final String threadId;
    private final AtomicBoolean closed = new AtomicBoolean();
    private int requestId;
    private FutureTask<Void> runningTurn;

    static AntigravityManagedConversation open(Path executable, Path workspace, Map<String, String> environment, JsonMapper mapper, Launch launch)
            throws IOException, InterruptedException {
        return open(ToolProcess.startStdio(List.of(executable.toString(), "acp"), workspace, environment), workspace, mapper, launch);
    }

    static AntigravityManagedConversation open(ToolProcess.Stdio process, Path workspace, JsonMapper mapper, Launch launch)
            throws IOException, InterruptedException {
        try {
            return new AntigravityManagedConversation(process, workspace, mapper, launch);
        } catch (IOException | InterruptedException | RuntimeException failure) {
            process.close();
            throw failure;
        }
    }

    private AntigravityManagedConversation(ToolProcess.Stdio process, Path workspace, JsonMapper mapper, Launch launch)
            throws IOException, InterruptedException {
        this.process = process;
        this.mapper = mapper;
        output = new BufferedInputStream(process.output());
        reader = mapper.readerFor(JsonNode.class).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        threadId = timed(() -> {
            JsonNode initialized = request("initialize", Map.of("protocolVersion", 1, "clientCapabilities", Map.of()));
            if (!initialized.path("protocolVersion").isInt() || initialized.path("protocolVersion").intValue() != 1) {
                throw new IOException("Antigravity did not negotiate ACP version 1.");
            }
            // AGY discovers its installed plugins and MCP settings in this host's normal environment.
            JsonNode opened = request("session/new", Map.of("cwd", workspace.toString(), "mcpServers", List.of()));
            String id = opened.path("sessionId").stringValue(null);
            if (!NativeWakeProtocol.validConversation(id)) {
                throw new IOException("Antigravity did not return a valid conversation identifier.");
            }
            JsonNode options = select(id, opened.path("configOptions"), "model", launch.model());
            options = select(id, options, "thought_level", launch.effort());
            if (!launch.model().equals(option(options, "model").path("currentValue").stringValue(null))) {
                throw new NativeWakeAdapter.UnsupportedSelection();
            }
            return id;
        });
    }

    private JsonNode select(String session, JsonNode options, String key, String value) throws IOException {
        JsonNode choice = option(options, key);
        if (!choice.path("options").isArray() || !hasValue(choice.path("options"), value)) {
            throw new NativeWakeAdapter.UnsupportedSelection();
        }
        if (value.equals(choice.path("currentValue").stringValue(null))) {
            return options;
        }
        JsonNode applied = request("session/set_config_option", Map.of("sessionId", session, "configId", key, "value", value)).path("configOptions");
        if (!value.equals(option(applied, key).path("currentValue").stringValue(null))) {
            throw new NativeWakeAdapter.UnsupportedSelection();
        }
        return applied;
    }

    private static JsonNode option(JsonNode options, String key) throws IOException {
        if (options.isArray()) {
            for (JsonNode option : options) {
                if (key.equals(option.path("id").stringValue(null)) && "select".equals(option.path("type").stringValue(null))) {
                    return option;
                }
            }
        }
        throw new NativeWakeAdapter.UnsupportedSelection();
    }

    private static boolean hasValue(JsonNode choices, String requested) {
        for (JsonNode choice : choices) {
            if (requested.equals(choice.path("value").stringValue(null))) {
                return true;
            }
            for (JsonNode nested : choice.path("options")) {
                if (requested.equals(nested.path("value").stringValue(null))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String threadId() {
        return threadId;
    }

    @Override
    public synchronized Outcome wake(String target, String marker) {
        if (!threadId.equals(target) || !NativeWakeProtocol.validMarker(marker) || !live() || Thread.currentThread().isInterrupted()) {
            return new Outcome.NotSent("MANAGED_AGY_TARGET_UNAVAILABLE");
        }
        try {
            if (runningTurn != null) {
                await(runningTurn);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Outcome.NotSent("MANAGED_AGY_INTERRUPTED_BEFORE_SEND");
        } catch (IOException incomplete) {
            LOG.log(System.Logger.Level.DEBUG, "The prior ACP turn did not settle ({0})", incomplete.getClass().getSimpleName());
            return new Outcome.NotSent("MANAGED_AGY_PREVIOUS_TURN_UNAVAILABLE");
        }
        try {
            int id = timed(() -> send("session/prompt", Map.of("sessionId", threadId,
                    "prompt", List.of(Map.of("type", "text", "text", marker)))));
            // ACP replies to session/prompt at the end of the turn. The relay reports submission when the frame was written.
            runningTurn = new FutureTask<>(() -> {
                try {
                    JsonNode result = response(id, "session/prompt", threadId);
                    if (result.path("stopReason").stringValue("").isBlank()) {
                        throw new IOException("Antigravity did not complete the managed prompt.");
                    }
                    return null;
                } catch (IOException failure) {
                    close();
                    throw failure;
                }
            });
            Thread.ofVirtual().start(runningTurn);
            return new Outcome.Submitted();
        } catch (InterruptedException interrupted) {
            close();
            Thread.currentThread().interrupt();
            return new Outcome.Unknown("MANAGED_AGY_INTERRUPTED_AFTER_SEND_ATTEMPT");
        } catch (IOException unavailable) {
            LOG.log(System.Logger.Level.DEBUG, "The ACP prompt submission outcome is unavailable ({0})", unavailable.getClass().getSimpleName());
            close();
            return new Outcome.Unknown("MANAGED_AGY_SUBMISSION_UNAVAILABLE");
        }
    }

    private JsonNode request(String method, Map<String, ?> params) throws IOException {
        return response(send(method, params), method, null);
    }

    private int send(String method, Map<String, ?> params) throws IOException {
        int id = ++requestId;
        write(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        return id;
    }

    private JsonNode response(int id, String requestedMethod, String session) throws IOException {
        while (true) {
            JsonNode frame = read();
            String method = frame.path("method").stringValue(null);
            if (method != null) {
                if (session != null && method.startsWith("session/")
                        && !session.equals(frame.path("params").path("sessionId").stringValue(null))) {
                    throw new IOException("Antigravity reported a different conversation.");
                }
                if (frame.has("id")) {
                    denyRequest(frame.path("id"), method);
                }
                continue;
            }
            if (!frame.path("id").isInt() || frame.path("id").intValue() != id) {
                throw new IOException("Antigravity returned an unexpected ACP reply.");
            }
            JsonNode result = frame.path("result");
            if (result.isObject() && !frame.has("error")) {
                return result;
            }
            JsonNode code = frame.path("error").path("code");
            if ("session/set_config_option".equals(requestedMethod) && code.isInt() && code.intValue() == -32602) {
                throw new NativeWakeAdapter.UnsupportedSelection();
            }
            throw new IOException("Antigravity did not accept the ACP request.");
        }
    }

    private void denyRequest(JsonNode id, String method) throws IOException {
        if (!id.isString() && !id.isIntegralNumber()) {
            throw new IOException("Antigravity sent an invalid ACP request identifier.");
        }
        if ("session/request_permission".equals(method)) {
            write(Map.of("jsonrpc", "2.0", "id", id, "result", Map.of("outcome", Map.of("outcome", "cancelled"))));
        } else {
            write(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", -32601, "message", "Method not available")));
        }
    }

    private void write(Map<String, ?> frame) throws IOException {
        var input = process.input();
        input.write(mapper.writeValueAsBytes(frame));
        input.write('\n');
        input.flush();
    }

    private JsonNode read() throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int octet = output.read(); octet != -1; octet = output.read()) {
            if (octet == '\n') {
                try {
                    JsonNode frame = reader.readValue(bytes.toByteArray());
                    if (frame != null && frame.isObject() && "2.0".equals(frame.path("jsonrpc").stringValue(null))) {
                        return frame;
                    }
                } catch (JacksonException invalid) {
                    throw new IOException("Antigravity sent invalid ACP JSON.");
                }
                throw new IOException("Antigravity sent an invalid ACP frame.");
            }
            if (bytes.size() == MAX_FRAME_BYTES) {
                throw new IOException("Antigravity exceeded the ACP frame limit.");
            }
            bytes.write(octet);
        }
        throw new IOException("Antigravity closed its ACP output.");
    }

    private static <T> T timed(Callable<T> exchange) throws IOException, InterruptedException {
        var operation = new FutureTask<>(exchange);
        Thread.ofVirtual().start(operation);
        try {
            return await(operation);
        } finally {
            operation.cancel(true);
        }
    }

    private static <T> T await(FutureTask<T> operation) throws IOException, InterruptedException {
        try {
            return operation.get(CONTROL_DEADLINE.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timedOut) {
            throw new IOException("Antigravity did not answer before the ACP control deadline.");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("The Antigravity ACP exchange failed.");
        }
    }

    @Override
    public boolean live() {
        return !closed.get() && process.isAlive();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            process.close();
        }
    }
}
