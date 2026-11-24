// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.hook.ShellPaths;
import io.github.jaeseongs95.sennard.server.hook.ToolCall;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Codex: {@code apply_patch}, its shell tools (the one it opens a skill's file with), and the app's {@code wait_threads}. */
@Component
final class CodexWorkFacts implements HostWorkFacts {
    private static final Logger LOG = LoggerFactory.getLogger(CodexWorkFacts.class);
    private static final Set<String> SHELL_TOOLS = Set.of("Bash", "exec_command", "shell_command", "shell");
    /**
     * One of Codex's send tools: the name its PreToolUse hook gives it, the field of the call's input that holds the recipient, and whether the host itself delivers the tool only inside
     * the agent tree of the sender.
     */
    private record SendTool(String hookName, String recipientField, boolean insideTree) {
    }

    /**
     * The send tools. Codex joins a tool's namespace to its name with nothing between ({@code flat_tool_name}) and gives an MCP tool {@code mcp__<server>__<tool>}; {@code spawn_agent}
     * alone is special-cased to its bare name. Version 2's {@code collaboration} is the default of {@code features.multi_agent_v2.tool_namespace}; its bare names are the hook's name when
     * no namespace is set. The task tools are those of the TUI (its MCP server {@code codex_tui}) and of the app ({@code codex_app}); both send a prompt to any thread of the app server.
     * The one table is where a call is classified and where the hook's matcher comes from ({@link #NATIVE_PEER_SEND}), so a tool cannot be matched and not judged, or judged and not matched.
     */
    private static final List<SendTool> SEND_TOOLS = List.of(
            new SendTool("multi_agent_v1send_input", "target", false),
            new SendTool("send_message", "target", true),
            new SendTool("followup_task", "target", true),
            new SendTool("collaborationsend_message", "target", true),
            new SendTool("collaborationfollowup_task", "target", true),
            new SendTool("mcp__codex_tui__send_message_to_thread", "threadId", false),
            new SendTool("mcp__codex_app__send_message_to_thread", "threadId", false),
            new SendTool("codex_appsend_message_to_thread", "threadId", false));
    static final Pattern NATIVE_PEER_SEND = Pattern.compile("^(" + SEND_TOOLS.stream().map(SendTool::hookName).collect(Collectors.joining("|")) + ")$");
    /** The path of the root agent of a tree, which the version-2 tools take as {@code target}. */
    private static final String ROOT_AGENT = "/root";
    /** The tool that starts a subagent: {@code spawn_agent} (version 1 is special-cased to the bare name) and the version-2 tool of the default namespace. */
    private static final Pattern STARTS_SUBAGENT = Pattern.compile("^(spawn_agent|collaborationspawn_agent)$");
    private static final Pattern PATCH_TARGET = Pattern.compile("^\\*\\*\\* (?:(?:Add|Update|Delete) File|Move to): (.+?)\\r?$");
    private static final Pattern POWERSHELL = Pattern.compile("(?:powershell|pwsh)(?:\\.exe)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CMD = Pattern.compile("(?:^|[\\\\/])cmd(?:\\.exe)?$", Pattern.CASE_INSENSITIVE);
    private static final String WAIT_TOOL = "mcp__codex_app__wait_threads";
    private static final Pattern THREAD_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$");
    private static final Duration DEFAULT_WAIT = Duration.ofMinutes(2);
    private static final Duration MAX_WAIT = Duration.ofHours(1);
    private static final int MAX_TARGETS = 8;

    private final SkillFileReads skills;
    private final JsonMapper json;
    private final ShellPaths shellPaths;

    CodexWorkFacts(SkillFileReads skills, JsonMapper json, ShellPaths shellPaths) {
        this.skills = skills;
        this.json = json;
        this.shellPaths = shellPaths;
    }

    @Override
    public HostId host() {
        return CodexHost.ID;
    }

    /** Codex names an MCP tool {@code mcp__<server>__<tool>} after the server the plugin registers (the same shape as its own {@code mcp__codex_app__wait_threads}). */
    @Override
    public Optional<SennardToolName> sennardToolName(String tool) {
        return Optional.of(new SennardToolName("mcp__" + HookDefinition.MCP_SERVER + "__" + tool, null, null, null, null));
    }


    @Override
    public boolean runsCommand(ToolCall tool) {
        return SHELL_TOOLS.contains(tool.name());
    }

    /**
     * What Codex 0.171.0 says of its send tools (codex-rs):
     * <ul>
     * <li>{@code send_message} and {@code followup_task} (version 2) name the recipient in {@code target}, a task name or a thread id. The host delivers only to an agent its registry of
     * this agent tree knows: {@code agent/control/api.rs} {@code send} ends the message path in {@code ensure_agent_known} ({@code runtime_context.rs}, {@code registry.rs}
     * {@code agent_metadata_for_thread}), and every root thread gets a registry of its own ({@code thread_manager.rs} {@code agent_control_for_config}). A thread id of another tree is
     * refused by the host, so the tool stays inside the delegation. {@code /root} is the tree's root ({@code protocol/src/agent_path.rs}).</li>
     * <li>{@code send_input} (version 1) takes a thread id that the host does not check against the tree: {@code tools/handlers/multi_agents/send_input.rs} reaches the thread through
     * {@code ThreadManagerState::get_thread}, which holds every thread of the process ({@code thread_manager.rs}). Only a recorded recipient can pass.</li>
     * <li>{@code mcp__codex_tui__send_message_to_thread}, the TUI's own task tool (its MCP server {@code codex_tui}, {@code tui/src/dynamic_tools_mcp.rs}; the hook's name for an MCP tool
     * is {@code mcp__<server>__<tool>}, {@code core/src/tools/handlers/mcp.rs}), sends a prompt to any thread of the app server by {@code threadId}
     * ({@code tui/src/dynamic_tools.rs}), another session included. Only a recorded recipient can pass. The app's tool of the same name in its {@code codex_app} namespace is the same
     * tool for the host ({@code core/src/agent/control/sender_context.rs}, {@code tui/src/dynamic_tools.rs} {@code parse_delegated_tool_output}); its input is not in the source, so it is
     * read by the same field and a call that does not carry it is refused. When the app registers {@code codex_app} as a dynamic tool namespace and not as an MCP server, the hook's
     * name is the namespace and the name joined with nothing between, {@code codex_appsend_message_to_thread} ({@code app-server/tests/suite/v2/dynamic_tools.rs},
     * {@code core/src/tools/handlers/dynamic.rs}, {@code core/src/tools/mod.rs} {@code flat_tool_name}); the source does not say which form the app uses, so both are classified.</li>
     * </ul>
     * The version-2 names carry their namespace: {@code core/src/tools/registry.rs} {@code function_hook_tool_name} and {@code core/src/tools/mod.rs} {@code flat_tool_name} join it to the
     * name with nothing between, so the default is {@code collaborationsend_message}. A namespace the user sets ({@code features.multi_agent_v2.tool_namespace}) gives a name that cannot
     * be told from a third-party tool's {@code <namespace><name>}, so it is not classified here.
     */
    @Override
    public Optional<NativeSend> nativeSend(ToolCall tool) {
        return SEND_TOOLS.stream().filter(send -> send.hookName().equals(tool.name())).findFirst().map(send -> {
            String recipient = HookJson.firstPresentOrNull(tool.input(), send.recipientField());
            return new NativeSend(recipient, send.insideTree() && ROOT_AGENT.equals(recipient), send.insideTree());
        });
    }

    /**
     * What {@code spawn_agent} gives a recipient to be called by: the {@code task_name} it was asked for (an agent is reached by it relative to its parent), and what it returned, the
     * {@code agent_id} of the version-1 tool or the canonical {@code task_name} of the version-2 one (multi_agents_spec.rs, the output schemas of {@code spawn_agent}).
     */
    @Override
    public Set<String> subagentAddresses(ToolCall tool, String result) {
        if (tool.name() == null || !STARTS_SUBAGENT.matcher(tool.name()).matches()) {
            return Set.of();
        }
        Set<String> addresses = new TreeSet<>();
        HookJson.present(tool.input(), "task_name").ifPresent(addresses::add);
        if (result != null) {
            try {
                JsonNode returned = json.readTree(result);
                if (returned.isArray() && returned.size() == 1 && "input_text".equals(HookJson.text(returned.get(0), "type"))) {
                    returned = json.readTree(HookJson.text(returned.get(0), "text"));
                }
                for (String field : List.of("agent_id", "task_name")) {
                    HookJson.present(returned, field).ifPresent(addresses::add);
                }
            } catch (JacksonException unreadable) {
                LOG.debug("The result of spawn_agent is not the JSON its output schema states; only the task name asked for is known", unreadable);
            }
        }
        return addresses;
    }

    @Override
    public Optional<EditTargets> editTargets(ToolCall tool) {
        if ("apply_patch".equals(tool.name())) {
            List<String> paths = new ArrayList<>();
            HookJson.text(tool.input(), "command").lines().forEach(line -> {
                Matcher target = PATCH_TARGET.matcher(line);
                if (target.find()) {
                    paths.add(target.group(1));
                }
            });
            return Optional.of(new EditTargets(paths, false));
        }
        if (!SHELL_TOOLS.contains(tool.name())) {
            return Optional.empty();
        }
        String shell = HookJson.text(tool.input(), "shell");
        // The shell is the one the event names; an event that names none (the hooks documentation gives a Bash call only "command") is read both ways.
        ShellEditTargets.Dialect dialect = shell.isEmpty() ? ShellEditTargets.Dialect.EITHER
                : POWERSHELL.matcher(shell).find() ? ShellEditTargets.Dialect.POWERSHELL
                : CMD.matcher(shell).find() ? ShellEditTargets.Dialect.CMD : ShellEditTargets.Dialect.POSIX;
        String command = HookJson.present(tool.input(), "cmd").orElseGet(() -> HookJson.text(tool.input(), "command"));
        ShellEditTargets.Writes writes = ShellEditTargets.of(command, dialect, shellPaths);
        return Optional.ofNullable(writes).map(ShellEditTargets.Writes::editTargets);
    }

    @Override
    public List<String> skillLoads(ToolCall tool) {
        return SHELL_TOOLS.contains(tool.name()) ? skills.readByCommand(CodexHost.ID, HookJson.text(tool.input(), "command")) : List.of();
    }

    @Override
    public Set<Injection> injections() {
        return Set.of(Injection.TOOL_BOUNDARY, Injection.TURN_END);
    }

    /** Only the observed, local wait surface is recognized; anything else about the call means this is not that wait. */
    @Override
    public Optional<PeerWait> peerWait(ToolCall tool) {
        if (!WAIT_TOOL.equals(tool.name())) {
            return Optional.empty();
        }
        JsonNode timeout = tool.input().path("timeoutMs");
        if (!timeout.isMissingNode() && !timeout.isIntegralNumber()) {
            return Optional.empty();
        }
        Duration wait = timeout.isMissingNode() ? DEFAULT_WAIT : Duration.ofMillis(timeout.asLong()); // the host's own field is a number of milliseconds
        JsonNode targets = tool.input().path("targets");
        if (wait.isNegative() || wait.compareTo(MAX_WAIT) > 0 || !targets.isArray() || targets.isEmpty() || targets.size() > MAX_TARGETS) {
            return Optional.empty();
        }
        TreeSet<String> sessions = new TreeSet<>();
        TreeSet<String> cursors = new TreeSet<>();
        for (JsonNode target : targets) {
            JsonNode thread = target.path("threadId");
            JsonNode hostId = target.path("hostId");
            JsonNode cursor = target.path("afterCursor");
            if (!target.isObject() || !thread.isString() || !THREAD_ID.matcher(thread.asString()).matches() || (!hostId.isMissingNode() && !"local".equals(hostId.asString(null)))
                    || (!cursor.isMissingNode() && !cursor.isString())) {
                return Optional.empty();
            }
            sessions.add(thread.asString());
            cursors.add(json.writeValueAsString(new Object[] {thread.asString(), cursor.isMissingNode() ? null : cursor.asString()}));
        }
        return Optional.of(new PeerWait(sessions.stream().map(session -> new PeerTarget(CodexHost.ID.sessionHost(), session)).toList(), wait, Digests.sha256Hex(json.writeValueAsString(cursors))));
    }
}
