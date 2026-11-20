// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.modelcontextprotocol.client.McpSyncClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a gate test needs to give a session the instance of a change workflow that serves its request: the checkout's folders (which {@link SessionServerSupport#started} gives every
 * server, so a start can read the skill registry), and the start itself, made as the host's model makes it (through the MCP tool, which attributes the opened instance to the session's request). The edit gate holds every file of a request that no
 * instance serves, so a test of what the gate lets through starts one first.
 */
final class ChangeWorkflows {
    private ChangeWorkflows() {
    }

    /** The checkout's root: where the {@code skills} tree and the contracts are. */
    static Path repository() {
        Path repository = Path.of("").toAbsolutePath();
        while (repository != null && (!Files.isDirectory(repository.resolve("contracts")) || !Files.isDirectory(repository.resolve("skills")))) {
            repository = repository.getParent();
        }
        if (repository == null) {
            throw new IllegalStateException("The checkout (a folder with contracts and skills) is not above " + Path.of("").toAbsolutePath());
        }
        return repository;
    }

    /**
     * The settings that point a server at the checkout's trees, for the servers of {@link SessionServerSupport#started}: {@code sennard.content-root} (the skill registry and the contract
     * files every workflow start reads; without it they are looked for under the server's home and the start is refused as unreadable) and {@code sennard.skills-root} (the skill
     * inventory). A setting the test gave is its own, and so are both when it gave a {@code sennard.home} (they follow it); no test repeats these two.
     */
    static List<String> checkoutSettings(String... given) {
        List<String> settings = new ArrayList<>();
        for (String key : List.of("--sennard.content-root=", "--sennard.skills-root=")) {
            boolean own = Arrays.stream(given).anyMatch(argument -> argument.startsWith(key) || argument.startsWith("--sennard.home="));
            if (!own) {
                settings.add(key + repository());
            }
        }
        return settings;
    }

    /**
     * A Sennard tool called as the main agent of this host calls it, with the execution observation that host gives the call. A start of any kind needs the observation (the model the
     * host reports for the call, which the server never takes from the caller), and an observation is consumed once, so each call is its own host call:
     * <ul>
     * <li>codex: the model is in the call's {@code _meta}, and the call id there is made new for each call;
     * <li>claude: the PreToolUse hook of the call's tool-use id leaves the row that binds the call, and the model is read from the assistant line of the session's transcript that issued
     * that tool use (the hook's {@code transcript_path} names a file this writes, as the host does before it makes the call);
     * <li>agy: the PreToolUse hook of the same tool with the same arguments leaves the row that holds the model, and the call is matched to it by the conversation, the tool and the arguments.
     * </ul>
     * The result is the tool's envelope, whether the call succeeded or not.
     */
    static JsonNode callAs(RunningServer server, String host, String session, String tool, Map<String, Object> arguments) throws IOException {
        return callAs(server, host, session, tool, arguments, null);
    }

    /**
     * The same call as a Claude Code subagent makes it when {@code agentId} is given: its PreToolUse hook carries {@code agent_id} and {@code agent_type}, and the assistant line of the
     * tool use is the subagent's own (a sidechain line with its {@code agentId}). A host other than Claude Code takes no agent id here.
     */
    static JsonNode callAs(RunningServer server, String host, String session, String tool, Map<String, Object> arguments, String agentId) throws IOException {
        if (agentId != null && !host.equals("claude")) {
            throw new IllegalArgumentException("A subagent's call is made as Claude Code's: " + host);
        }
        Map<String, Object> keyed = OperationKeys.withKey(tool, arguments);
        String toolUse = "toolu_" + UUID.randomUUID();
        Map<String, Object> meta = new HashMap<>(SessionServerSupport.meta("windows", host, session, host.equals("claude") ? toolUse : "none"));
        switch (host) {
            case "codex" -> meta.put("callId", "exec-" + UUID.randomUUID());
            case "claude" -> admitClaude(server, session, tool, toolUse, agentId);
            case "agy" -> admitAgy(server, session, tool, keyed);
            default -> throw new IllegalArgumentException("No host " + host);
        }
        try (McpSyncClient client = DomainServerSupport.client(server.port, host)) {
            return DomainServerSupport.call(client, tool, keyed, meta);
        }
    }

    static JsonNode callAsAgyChild(RunningServer server, String root, String child, String tool, Map<String, Object> arguments) throws IOException {
        Map<String, Object> keyed = OperationKeys.withKey(tool, arguments);
        admitAgy(server, child, tool, keyed);
        Map<String, Object> meta = new HashMap<>(SessionServerSupport.meta("windows", "agy", child, "none"));
        meta.put("antigravity.google/root_conversation_id", root);
        meta.put("antigravity.google/parent_conversation_id", root);
        try (McpSyncClient client = DomainServerSupport.client(server.port, "agy")) {
            return DomainServerSupport.call(client, tool, keyed, meta);
        }
    }

    /** Claude Code's PreToolUse hook of the call, whose transcript holds the assistant message that issued the tool use, with the model that wrote it. */
    private static void admitClaude(RunningServer server, String session, String tool, String toolUse, String agentId) throws IOException {
        String sennardTool = "mcp__plugin_sennard_sennard__" + tool;
        Path directory = Path.of("build", "host-transcripts").toAbsolutePath();
        Files.createDirectories(directory);
        Path transcript = directory.resolve(session + ".jsonl");
        Map<String, Object> issued = new LinkedHashMap<>();
        issued.put("type", "assistant");
        issued.put("uuid", "uuid-" + toolUse);
        issued.put("sessionId", session);
        issued.put("timestamp", Instant.now().toString());
        issued.put("isSidechain", agentId != null);
        if (agentId != null) {
            issued.put("agentId", agentId);
        }
        issued.put("message", Map.of("role", "assistant", "model", "a-model", "content", List.of(Map.of("type", "tool_use", "id", toolUse, "name", sennardTool, "input", Map.of()))));
        Files.writeString(transcript, DomainServerSupport.MAPPER.writeValueAsString(issued) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        ObjectNode body = (ObjectNode) DomainServerSupport.MAPPER
                .readTree(new String(SessionServerSupport.hook("windows", "claude", "PreToolUse", session, toolUse), StandardCharsets.UTF_8).replace("mcp__plugin_sennard-probe_probe__probe_echo", sennardTool));
        body.put("transcript_path", transcript.toString());
        if (agentId != null) {
            body.put("agent_id", agentId).put("agent_type", "general-purpose");
        }
        RawHttp.Response admitted = SessionServerSupport.post(server.port, "/hooks/v1/claude/PreToolUse/governance", DomainServerSupport.MAPPER.writeValueAsBytes(body));
        assertEquals(200, admitted.status(), DomainServerSupport.text(admitted));
    }

    /** Antigravity's PreToolUse hook of the call: the host's wrapper tool naming the Sennard server, the tool and the arguments the call will be made with. */
    private static void admitAgy(RunningServer server, String session, String tool, Map<String, Object> arguments) throws IOException {
        ObjectNode body = (ObjectNode) DomainServerSupport.MAPPER.readTree(new String(SessionServerSupport.hook("windows", "agy", "PreToolUse", session, "none"), StandardCharsets.UTF_8));
        ObjectNode wrapped = body.putObject("toolCall").put("name", "call_mcp_tool").putObject("args");
        wrapped.put("ServerName", "sennard").put("ToolName", tool).set("Arguments", DomainServerSupport.MAPPER.valueToTree(arguments));
        RawHttp.Response admitted = SessionServerSupport.post(server.port, "/hooks/v1/agy/PreToolUse", DomainServerSupport.MAPPER.writeValueAsBytes(body));
        assertEquals(200, admitted.status(), DomainServerSupport.text(admitted));
    }

    /**
     * Opens a change workflow for the session's request, whose writeTargets are these paths of the workspace and whose change plan is the first of them (the run executes at once).
     * Returns the result's data; fails the test when the start is refused.
     */
    static JsonNode open(RunningServer server, String session, Path workspace, List<String> writeTargets) throws Exception {
        return open(server, "codex", session, workspace, writeTargets);
    }

    static JsonNode open(RunningServer server, String host, String session, Path workspace, List<String> writeTargets) throws Exception {
        Files.createDirectories(workspace);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("goal", "Change " + writeTargets + " in " + workspace.getFileName());
        arguments.put("stages", List.of("minimal-implementation"));
        arguments.put("skills", List.of(Map.of("skillId", "minimal-implementation", "reason", "the change of this test")));
        arguments.put("acceptanceCriteria", List.of("The change works."));
        arguments.put("workspace", workspace.toString());
        arguments.put("writeTargets", writeTargets);
        arguments.put("changePlan", List.of(".".equals(writeTargets.getFirst()) ? "plan.txt" : writeTargets.getFirst()));
        JsonNode result = callAs(server, host, session, "start_workflow", arguments);
        assertTrue(result.path("ok").asBoolean(false), result.toString());
        return result.path("data");
    }
}
