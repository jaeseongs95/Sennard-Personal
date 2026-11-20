// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.github.jaeseongs95.sennard.hookclient.HookClientCall;
import io.github.jaeseongs95.sennard.hookclient.McpRelayArguments;
import io.github.jaeseongs95.sennard.server.hook.HookInstallation;

/**
 * The records a host's hook definition and MCP registration files are written from. Each adapter fills the ones its host's file has and the adapter's mapper writes them: the
 * order of a record's keys is part of the file (the package's empty files are generated and compared byte for byte, and the installation's files are fingerprinted), so each says it.
 */
public final class HookDefinition {
    /**
     * The name every host registers the Sennard MCP server under. It is part of what the hosts show the model (Claude Code names a tool {@code mcp__plugin_sennard_sennard__<tool>}
     * after the plugin and this name) and what Codex's {@code mcp_tool} hooks call, so it is one constant.
     */
    public static final String MCP_SERVER = "sennard";

    private HookDefinition() {
    }

    /**
     * The registration of the Sennard MCP server that every host takes as a command and its arguments: the installation's relay, told which host it serves and how long a session's
     * {@code initialize} waits for a server it starts. A host's configuration cannot name the server's address (it changes at each start): the relay finds it.
     */
    static McpServers sennardServer(HookInstallation installation, String hostLabel) {
        return new McpServers(Map.of(MCP_SERVER,
                new McpServer(installation.mcpExecutable(), installation.mcpArguments(McpRelayArguments.arguments(hostLabel, HookClientCall.START_SERVER_SECONDS)))));
    }

    /** The registration with no server: what a plugin package ships. */
    static McpServers noServers() {
        return new McpServers(Map.of());
    }

    /** The hook definition with no hook: what a plugin package ships for the hosts whose file is {@link ByEvent}. */
    static ByEvent noHooks() {
        return new ByEvent(Map.of());
    }

    /** The file of the hosts whose hooks are listed under their event: {@code {"hooks": {<event>: [<entry>...]}}} (Claude Code, Codex, Gemini CLI). */
    public record ByEvent(Map<String, List<Entry>> hooks) {
    }

    /** What one registration under an event is: the handlers that run, for the calls that match, or for every call when there is no matcher. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"matcher", "hooks"})
    public record Entry(String matcher, List<Handler> hooks) {
        public static Entry of(String matcher, Handler handler) {
            return new Entry(matcher, List.of(handler));
        }
    }

    /** What a host runs for a hook. */
    public sealed interface Handler {
    }

    /**
     * A command the host starts: the Sennard hook client.
     *
     * @param onFailure what the host does with a hook that failed, or null for the host's own default; Claude Code's {@code "block"} makes a failed hook of a gate stop the tool
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"name", "type", "command", "args", "timeout", "asyncRewake", "additionalContextLimit", "onFailure"})
    public record Command(String name, String command, List<String> args, int timeout, Boolean asyncRewake, Integer additionalContextLimit, String onFailure) implements Handler {
        @JsonProperty("type")
        public String type() {
            return "command";
        }
    }

    /** The file of the hosts that register an MCP server in a file of its own, or as the one member of one: {@code {"mcpServers": {<name>: <server>}}}. */
    public record McpServers(Map<String, McpServer> mcpServers) {
    }

    /** An MCP server the host starts itself and talks to over its standard streams: the Sennard MCP relay. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"command", "args"})
    public record McpServer(String command, List<String> args) {
    }

    /**
     * A call of a tool of an MCP server the host already runs.
     *
     * @param input the tool's input: {@code event} and {@code module} say which endpoint it is, every other key is a field of the host's event, which the host
     *     fills from the event
     */
    @JsonPropertyOrder({"type", "server", "tool", "input", "timeout"})
    public record McpTool(String server, String tool, Map<String, String> input, int timeout) implements Handler {
        @JsonProperty("type")
        public String type() {
            return "mcp_tool";
        }
    }

    /** Antigravity's file: one group of hooks per lifecycle point and one for the tool boundary. */
    public record AntigravityFile(@JsonProperty("sennard-session-lifecycle") Lifecycle lifecycle, @JsonProperty("sennard-tool-boundary") Boundary boundary) {
    }

    @JsonPropertyOrder({"enabled", "SessionStart", "PreInvocation", "PostInvocation", "Stop"})
    public record Lifecycle(boolean enabled, @JsonProperty("SessionStart") List<Command> sessionStart, @JsonProperty("PreInvocation") List<Command> preInvocation,
            @JsonProperty("PostInvocation") List<Command> postInvocation, @JsonProperty("Stop") List<Command> stop) {
    }

    @JsonPropertyOrder({"enabled", "PreToolUse", "PostToolUse"})
    public record Boundary(boolean enabled, @JsonProperty("PreToolUse") List<Entry> preToolUse, @JsonProperty("PostToolUse") List<Entry> postToolUse) {
    }
}
