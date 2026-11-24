// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

import io.github.jaeseongs95.sennard.protocol.ToolProcess;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** An initialized Codex app-server stdio connection used by one exchange at a time. */
final class CodexAppServerConnection implements AutoCloseable {
    private static final String CLOSED_OUTPUT = "The host closed its protocol output.";
    private final ToolProcess.Stdio process;
    private final ManagedStreamFrames frames;
    private final JsonMapper mapper;
    private final ObjectReader reader;
    private final ManagedExchangeDeadline deadline;
    private int requestId;

    CodexAppServerConnection(ToolProcess.Stdio process, JsonMapper mapper, Duration deadline) {
        this.process = process;
        this.mapper = mapper;
        this.deadline = new ManagedExchangeDeadline(deadline, "The host protocol did not answer before its deadline.",
                "The host protocol exchange failed.", false);
        frames = new ManagedStreamFrames(process.output(), process.input(),
                "The host exceeded the protocol frame limit.", () -> new IOException(CLOSED_OUTPUT));
        reader = WakeJson.strictReader(mapper, JsonNode.class);
    }

    void initialize() throws IOException {
        String version = Objects.requireNonNullElse(CodexAppServerConnection.class.getPackage().getImplementationVersion(), "unknown");
        request("initialize", Map.of("clientInfo", Map.of("name", "sennard", "title", "Sennard", "version", version)));
        write(Map.of("method", "initialized"));
    }

    static Optional<String> readThreadTitle(ToolProcess.Stdio process, JsonMapper mapper, Duration deadline, String threadId)
            throws IOException, InterruptedException {
        try (process) {
            var connection = new CodexAppServerConnection(process, mapper, deadline);
            return connection.timed(() -> {
                connection.initialize();
                JsonNode thread = connection.request("thread/read", Map.of("threadId", threadId, "includeTurns", false)).path("thread");
                if (!threadId.equals(thread.path("id").stringValue(null))) {
                    throw new IOException("The host returned a different conversation.");
                }
                JsonNode name = thread.path("name");
                if (name.isNull() || name.isMissingNode()) {
                    return Optional.empty();
                }
                String title = name.stringValue(null);
                if (title == null) {
                    throw new IOException("The host returned an invalid conversation name.");
                }
                return title.isBlank() ? Optional.empty() : Optional.of(title);
            });
        }
    }

    JsonNode request(String method, Map<String, ?> params) throws IOException {
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

    static final class InvalidParameters extends IOException {
        private static final long serialVersionUID = 1L;

        private InvalidParameters() {
            super("The host rejected the request parameters.");
        }
    }

    private void write(Map<String, ?> message) throws IOException {
        frames.write(mapper.writeValueAsBytes(message));
    }

    JsonNode read() throws IOException {
        byte[] frame = frames.read();
        if (frame == null) {
            throw new IOException(CLOSED_OUTPUT);
        }
        try {
            JsonNode message = reader.readValue(frame);
            if (message != null && message.isObject()) {
                return message;
            }
        } catch (JacksonException invalid) {
            throw new IOException("The host sent invalid protocol JSON.");
        }
        throw new IOException("The host sent an invalid protocol message.");
    }

    <T> T timed(Callable<T> exchange) throws IOException, InterruptedException {
        return deadline.run(exchange);
    }

    boolean live() {
        return process.isAlive();
    }

    @Override
    public void close() {
        process.close();
    }
}
