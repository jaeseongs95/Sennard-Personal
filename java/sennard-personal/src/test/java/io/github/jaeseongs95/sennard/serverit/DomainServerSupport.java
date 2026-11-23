// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import io.github.jaeseongs95.sennard.app.message.CatalogMessageSource;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the continuity and messaging server tests share: a real MCP client under a host's label, and a recorded hook body with this test's
 * session in it. A body the probe did not record (an event no fixture has, or another tool) is a recorded one with named fields changed, so
 * every other field stays what the host really sent.
 */
final class DomainServerSupport {
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private DomainServerSupport() {
    }

    static McpSyncClient client(int port, String label) {
        return client(port, label, Duration.ofSeconds(60));
    }

    /** An initialized client of the server on {@code port}; with a null {@code label} the requests carry no host label. */
    static McpSyncClient client(int port, String label, Duration requestTimeout) {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp");
        if (label != null) {
            transport.requestBuilder(HttpRequest.newBuilder().header("Sennard-Host-Label", label));
        }
        McpSyncClient client = McpClient.sync(transport.build()).requestTimeout(requestTimeout).build();
        client.initialize();
        return client;
    }

    /**
     * The clients one test class shares: one per host label (null for none), each built and initialized the first time a test asks for it and all closed
     * together by {@link #close()}, which the class calls in its {@code @AfterAll}. The SDK's transport keeps a JDK {@code HttpClient} of its own for each
     * client, so a client made for every call piles up connections on the server for as long as the server lives; a call's own data (session, call id)
     * travels in its {@code _meta}, not in the client.
     */
    static final class Clients implements AutoCloseable {
        private final int port;
        private final Duration requestTimeout;
        private final Map<String, McpSyncClient> byLabel = new LinkedHashMap<>();

        Clients(int port, Duration requestTimeout) {
            this.port = port;
            this.requestTimeout = requestTimeout;
        }

        /** The one client of this label. A test that calls from several threads at once builds a client for each thread with {@link DomainServerSupport#client(int, String, Duration)} instead: the SDK's source was not at hand to confirm that a client may be shared by threads. */
        synchronized McpSyncClient of(String label) {
            return byLabel.computeIfAbsent(label, key -> client(port, key, requestTimeout));
        }

        @Override
        public synchronized void close() {
            RuntimeException failure = null;
            for (McpSyncClient client : byLabel.values()) {
                try {
                    client.close();
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            byLabel.clear();
            if (failure != null) {
                throw failure;
            }
        }
    }

    /** The tool's result envelope. */
    static JsonNode call(McpSyncClient client, String tool, Map<String, Object> arguments, Map<String, Object> meta) {
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(OperationKeys.withKey(tool, arguments)).meta(meta).build());
        return MAPPER.readTree(((McpSchema.TextContent) result.content().getFirst()).text());
    }

    /**
     * The tool's result envelope for the arguments exactly as they are sent: {@link #call} gives a write tool an operationKey of its own (OperationKeys.withKey), so a test of the call
     * that has none, or whose arguments the entrance refuses, calls through this one.
     */
    static JsonNode callAsSent(McpSyncClient client, String tool, Map<String, Object> arguments, Map<String, Object> meta) {
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).meta(meta).build());
        return MAPPER.readTree(((McpSchema.TextContent) result.content().getFirst()).text());
    }

    /** The arguments every request contract requires, plus these. */
    static Map<String, Object> arguments(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", "1.0.0");
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1]);
        }
        return map;
    }

    static String englishMessage(RunningServer server, String key, Map<String, Object> parameters) {
        return server.bean(CatalogMessageSource.class).getMessage(key, new Object[]{parameters}, Locale.ENGLISH);
    }

    static Map<String, Object> operationArguments(String goal, String target, String workspace, String skillId, String reason) {
        return Map.of("goal", goal, "target", target, "workspace", workspace,
                "skills", List.of(Map.of("skillId", skillId, "reason", reason)));
    }

    /** Builds a passing stage request with an uploaded attachment belonging to the same caller. */
    static Map<String, Object> passedStageArguments(RunningServer server, String host, String session, JsonNode run) {
        ToolServerSupport.Uploaded evidence = ToolServerSupport.uploadEvidence(server.port, (tool, args, caller) -> {
            try {
                return ChangeWorkflows.callAs(server, host, caller, tool, args);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }, session);
        JsonNode plan = run.path("plan");
        String stageId = plan.path("currentStageId").asString(plan.path("nextStageId").asString());
        return arguments("runId", run.path("runId").asString(), "expectedRevision", run.path("revision").asLong(), "stageId", stageId,
                "state", "PASSED", "output", arguments("kind", "output", "output", Map.of("status", "passed"), "artifacts", List.of(), "error", null),
                "evidence", List.of(ToolServerSupport.observedEvidence(evidence)),
                "findings", List.of(), "blockers", List.of(), "error", null);
    }

    /** Posts the recorded hook of this host and event with this session, changed by {@code edit}. */
    static RawHttp.Response hook(int port, String os, String host, String event, String module, String session, Consumer<ObjectNode> edit) throws IOException {
        return hookFrom(event, port, os, host, event, module, session, edit);
    }

    /** The same, for an event the probe has no recording of: the body of {@code fixture}, posted as {@code event}. */
    static RawHttp.Response hookFrom(String fixture, int port, String os, String host, String event, String module, String session, Consumer<ObjectNode> edit) throws IOException {
        ObjectNode body = (ObjectNode) MAPPER.readTree(new String(SessionServerSupport.hook(os, host, fixture, session, "none"), StandardCharsets.UTF_8));
        edit.accept(body);
        return SessionServerSupport.post(port, "/hooks/v1/" + host + "/" + event + (module == null ? "" : "/" + module), MAPPER.writeValueAsBytes(body));
    }

    /**
     * The main agent's {@code mark_turn_unchanged} of its current turn, called as the host calls it (the host's label and recorded {@code _meta}; Claude Code's call is bound by the row
     * its PreToolUse hook leaves). For a test whose subject is not the turn-end save check (AGS3-D-0156): its turn end then answers only for what the test is about.
     */
    static void markTurnUnchanged(RunningServer server, String host, String session) throws IOException {
        callAsMainAgent(server, host, session, "mark_turn_unchanged", arguments());
    }

    /**
     * The main agent's {@code update_session_status}: what the agent does when the turn-end check asks for the session's summary. The summary is written to the board row the hooks of
     * the session made, as the host calls the tool (see {@link #markTurnUnchanged}).
     */
    static void writeSessionSummary(RunningServer server, String host, String session, String summary) throws IOException {
        callAsMainAgent(server, host, session, "update_session_status", arguments("summary", summary));
    }

    private static void callAsMainAgent(RunningServer server, String host, String session, String tool, Map<String, Object> arguments) throws IOException {
        int port = server.port;
        String toolUse = "toolu_" + java.util.UUID.randomUUID();
        if (host.equals("agy")) {
            callAsAgy(server, session, session, null, tool, OperationKeys.withKey(tool, arguments));
            return;
        }
        if (host.equals("claude")) {
            String body = new String(SessionServerSupport.hook("windows", "claude", "PreToolUse", session, toolUse), StandardCharsets.UTF_8)
                    .replace("mcp__plugin_sennard-probe_probe__probe_echo", "mcp__plugin_sennard_sennard__" + tool);
            RawHttp.Response admitted = SessionServerSupport.post(port, "/hooks/v1/claude/PreToolUse/governance", body.getBytes(StandardCharsets.UTF_8));
            if (admitted.status() != 200) {
                throw new AssertionError("The admission of " + tool + " answered " + admitted.status() + ": " + text(admitted));
            }
        }
        try (McpSyncClient client = client(port, host)) {
            JsonNode answer = call(client, tool, arguments, SessionServerSupport.meta("windows", host, session, host.equals("claude") ? toolUse : "none"));
            if (!answer.path("ok").asBoolean()) {
                throw new AssertionError(tool + " was refused: " + answer);
            }
        }
    }

    static void observeAgyRoot(RunningServer server, String session) throws IOException {
        observeAgy(server, session, session, null);
    }

    /** Posts the hook of an ordinary read tool, then makes the MCP call with the supplied conversation metadata. */
    static void observeAgy(RunningServer server, String conversation, String root, String parent) throws IOException {
        callAsAgy(server, conversation, root, parent, "list_session_workflows", Map.of());
    }

    private static void callAsAgy(RunningServer server, String conversation, String root, String parent, String tool, Map<String, Object> args) throws IOException {
        RawHttp.Response admitted = hook(server.port, "windows", "agy", "PreToolUse", null, conversation, body -> {
            ObjectNode wrapped = body.putObject("toolCall").put("name", "call_mcp_tool").putObject("args");
            wrapped.put("ServerName", "sennard").put("ToolName", tool).set("Arguments", MAPPER.valueToTree(args));
        });
        if (admitted.status() != 200 || !"allow".equals(MAPPER.readTree(admitted.body()).path("decision").stringValue())) {
            throw new AssertionError("Antigravity hook was refused: " + text(admitted));
        }
        Map<String, Object> meta = new LinkedHashMap<>(SessionServerSupport.meta("windows", "agy", conversation, "none"));
        meta.put("antigravity.google/conversation_id", conversation);
        meta.put("antigravity.google/root_conversation_id", root);
        meta.remove("antigravity.google/parent_conversation_id");
        if (parent != null) {
            meta.put("antigravity.google/parent_conversation_id", parent);
        }
        try (McpSyncClient client = client(server.port, "agy")) {
            JsonNode answer = callAsSent(client, tool, args, meta);
            if (!answer.path("ok").asBoolean()) {
                throw new AssertionError(tool + " was refused: " + answer);
            }
        }
    }

    static Consumer<ObjectNode> tool(String name, ObjectNode input) {
        return body -> {
            body.put("tool_name", name);
            body.set("tool_input", input);
        };
    }

    static String text(RawHttp.Response response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }
}
