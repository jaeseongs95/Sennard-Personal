// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.hook.HookInstallation;
import io.github.jaeseongs95.sennard.server.hook.HostDefinition;
import io.github.jaeseongs95.sennard.server.hook.HostDefinitions;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostRegistrationFile;
import io.github.jaeseongs95.sennard.server.hook.host.AntigravityHost;
import io.github.jaeseongs95.sennard.server.hook.host.ClaudeHost;
import io.github.jaeseongs95.sennard.server.hook.host.CodexHost;
import io.github.jaeseongs95.sennard.server.hook.host.GeminiHost;
import tools.jackson.databind.JsonNode;

/**
 * Where a host hook may be a command, in the definition an installation writes (a package ships none: {@link HostHookFiles}). The server's port is the operating system's choice at
 * each start, so no host's own HTTP hook can name the server, and a command (the Sennard hook client, which reads the port from the file where the server announces it) is the form
 * of every hook but the ones that the host's documentation gives another form. This is the contract of that choice: which hooks are a command, and which are the call of the MCP
 * server (Codex's {@code mcp_tool}, which reaches the server through the MCP relay). The architecture check {@code hook-command-not-java-client} is the other rule: a package defines nothing.
 *
 * <p>The documentation behind each place:
 * <ul>
 * <li>Claude Code {@code SessionStart}: "Only type: "command" and type: "mcp_tool" hooks are supported", and an mcp_tool hook is skipped on launch; the idle wake
 * is a command because {@code asyncRewake} is a field of a command hook (code.claude.com/docs/en/hooks). Every other hook of this host was an HTTP hook, whose {@code url} takes no
 * environment variable (the same page), and is now a command.</li>
 * <li>Codex {@code SessionEnd}: "doesn't support MCP tool hooks"; {@code SessionStart}: "can run before an MCP server is ready"
 * (developers.openai.com Codex hooks). Every event whose {@code agent_id} is optional: an {@code mcp_tool} placeholder of a field the event lacks fails the hook, with no default
 * or optional form (openai/codex codex-rs/hooks/src/engine/mcp_runner.rs: "hook input placeholder `${...}` was not found").</li>
 * <li>Antigravity and Gemini CLI: only {@code type: "command"} handlers exist (antigravity.google/docs/hooks, geminicli.com/docs/hooks).</li>
 * </ul>
 */
class HookCommandPlacesTest {
    private static final String MCP_TOOL = "mcp_tool";
    /** An installation as an installer writes it: the user's Java, the hook client's jar, and the installation's own options. */
    private static final HookInstallation INSTALLATION = new HookInstallation(
            List.of("/jdk/bin/java", "-cp", "/install/lib/sennard-hook-client.jar", "io.github.jaeseongs95.sennard.hookclient.HookClientMain", "--server-file=/state/server.properties",
                    "--jar=/install/sennard.jar"),
            List.of("/jdk/bin/java", "-cp", "/install/lib/sennard-hook-client.jar", "io.github.jaeseongs95.sennard.hookclient.McpRelayMain", "--server-file=/state/server.properties",
                    "--jar=/install/sennard.jar"));

    /** The host's hook definition as the installation writes it: the whole file named {@code hooks.json}. */
    private static JsonNode hookDefinition(HostId host) {
        HostDefinition definition = HostDefinitions.load().all().stream().filter(candidate -> candidate.host().equals(host)).findFirst()
                .orElseThrow(() -> new AssertionError("no definition of the host " + host));
        return definition.hookAdapter(Repo.JSON).registrationFiles(INSTALLATION).stream()
                .filter(file -> file instanceof HostRegistrationFile.WholeFile && file.relativePath().getFileName().toString().equals("hooks.json")).findFirst().orElseThrow().content();
    }

    /** The events of one file that run a command, and how many commands each has. */
    private static Map<String, Integer> commandsByEvent(JsonNode definition) {
        Map<String, Integer> found = new TreeMap<>();
        visit(definition, "", found, new TreeMap<>());
        return found;
    }

    private static Map<String, Integer> otherTypes(JsonNode definition) {
        Map<String, Integer> found = new TreeMap<>();
        visit(definition, "", new TreeMap<>(), found);
        return found;
    }

    /** An event is the name of an array that starts with a capital letter; a handler is an object with a {@code type}. */
    private static void visit(JsonNode node, String event, Map<String, Integer> commands, Map<String, Integer> others) {
        if (node.isObject() && node.has("type") && node.path("type").isString()) {
            String type = node.path("type").stringValue();
            (type.equals("command") ? commands : others).merge(type.equals("command") ? event : event + ":" + type, 1, Integer::sum);
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> visit(entry.getValue(), entry.getValue().isArray() && Character.isUpperCase(entry.getKey().charAt(0)) ? entry.getKey() : event, commands, others));
        } else if (node.isArray()) {
            node.forEach(item -> visit(item, event, commands, others));
        }
    }

    private static Map<String, Integer> once(String... events) {
        Map<String, Integer> expected = new TreeMap<>();
        for (String event : events) {
            expected.merge(event, 1, Integer::sum);
        }
        return expected;
    }

    @Test
    void antigravityRunsEveryHookAsACommand() {
        assertEquals(once("SessionStart", "PreInvocation", "PostInvocation", "Stop", "PreToolUse", "PreToolUse", "PostToolUse"), commandsByEvent(hookDefinition(AntigravityHost.ID)));
        assertEquals(Map.of(), otherTypes(hookDefinition(AntigravityHost.ID)));
    }

    @Test
    void codexRunsTheEventsWithAnOptionalAgentIdAsCommandsAndOnlyTheOthersAsMcpTools() {
        JsonNode definition = hookDefinition(CodexHost.ID);

        // An mcp_tool hook fails as a whole when a placeholder names a field the event lacks, and the host leaves agent_id out of the main session's events: so the events that may
        // carry one are commands (they read the whole event). Only SubagentStart and SubagentStop are tool calls. Stop can register a wake worker.
        assertEquals(Map.ofEntries(Map.entry("SessionStart", 1), Map.entry("SessionEnd", 1), Map.entry("UserPromptSubmit", 1), Map.entry("PreCompact", 1), Map.entry("PostCompact", 1),
                Map.entry("PreToolUse", 3), Map.entry("PostToolUse", 1), Map.entry("PreModelRequest", 1), Map.entry("PostModelRequest", 1), Map.entry("Stop", 1)),
                commandsByEvent(definition));
        Map<String, Integer> tools = otherTypes(definition);
        assertTrue(tools.keySet().stream().allMatch(key -> key.endsWith(":" + MCP_TOOL)), "every other Codex hook is an mcp_tool hook: " + tools);
        assertEquals(List.of("SubagentStart:mcp_tool", "SubagentStop:mcp_tool"), List.copyOf(tools.keySet()));
    }

    @Test
    void claudeRunsEveryHookAsACommandAndTakesNoHttpHook() {
        JsonNode definition = hookDefinition(ClaudeHost.ID);

        assertEquals(Map.ofEntries(Map.entry("SessionStart", 2), Map.entry("SubagentStart", 1), Map.entry("SubagentStop", 1), Map.entry("PreCompact", 1), Map.entry("PostCompact", 1),
                Map.entry("PostModelSwitch", 1), Map.entry("PreToolUse", 3), Map.entry("UserPromptSubmit", 1), Map.entry("PostToolUseFailure", 1), Map.entry("PostToolUse", 1),
                Map.entry("Stop", 2), Map.entry("SessionEnd", 1)), commandsByEvent(definition),
                "every event of the host that a hook may see, with the session start's wake and the wake of a turn's end");
        assertEquals(Map.of(), otherTypes(definition), "no HTTP hook: its url takes no environment variable, and the server's port changes at each start");
    }

    @Test
    void geminiRunsEveryHookAsACommand() {
        JsonNode definition = hookDefinition(GeminiHost.ID);

        Map<String, Integer> expected = once("SessionStart", "SessionEnd", "BeforeAgent", "AfterAgent", "BeforeModel", "BeforeTool", "AfterTool", "PreCompress");
        expected.put("AfterTool", 2);
        assertEquals(expected, commandsByEvent(definition));
        assertEquals(Map.of(), otherTypes(definition));
    }
}
