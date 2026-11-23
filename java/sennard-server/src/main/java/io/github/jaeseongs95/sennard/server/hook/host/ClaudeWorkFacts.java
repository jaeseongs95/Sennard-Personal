// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.hook.ShellPaths;
import io.github.jaeseongs95.sennard.server.hook.ToolCall;

/** Claude Code: its file tools, its shells, the Skill tool, and the auto-memory directories it keeps per project. */
@Component
final class ClaudeWorkFacts implements HostWorkFacts {
    static final Set<String> EDIT_TOOLS = Set.of("Edit", "Write", "MultiEdit", "NotebookEdit");
    static final Pattern NATIVE_PEER_SEND = Pattern.compile("^(SendMessage|SubagentHandback)$");
    private static final String PARENT_ADDRESS = "main";
    private static final Pattern PLUGIN_SKILL = Pattern.compile("^sennard:([a-z0-9][a-z0-9-]*)$");

    private final Path configDirectory;
    private final ShellPaths shellPaths;

    ClaudeWorkFacts(SennardProperties properties, ShellPaths shellPaths) {
        this.configDirectory = properties.hosts().claudeConfig();
        this.shellPaths = shellPaths;
    }

    @Override
    public HostId host() {
        return ClaudeHost.ID;
    }

    /**
     * Claude Code names a plugin's tool {@code mcp__plugin_<plugin>_<server>__<tool>} (the plugin and the server are both Sennard's), and lists the MCP tools of a session by name
     * only until ToolSearch loads their schema ({@code select:<name>}); a subagent that was told only the tool's own name had to find that out first.
     */
    @Override
    public Optional<SennardToolName> sennardToolName(String tool) {
        String name = "mcp__plugin_" + HookDefinition.MCP_SERVER + "_" + HookDefinition.MCP_SERVER + "__" + tool;
        return Optional.of(new SennardToolName(name, null, null, "ToolSearch", "select:" + name));
    }


    @Override
    public Set<String> toolsBeforeWorkflow() {
        Set<String> names = new java.util.LinkedHashSet<>(HostWorkFacts.super.toolsBeforeWorkflow());
        names.add("Skill");
        return names;
    }

    @Override
    public boolean runsCommand(ToolCall tool) {
        return "Bash".equals(tool.name()) || "PowerShell".equals(tool.name());
    }

    @Override
    public boolean runsToolScript(ToolCall tool) {
        return "REPL".equals(tool.name());
    }

    @Override
    public boolean sendsNativePeerMessage(ToolCall tool) {
        return tool.name() != null && NATIVE_PEER_SEND.matcher(tool.name()).matches();
    }

    @Override
    public Optional<NativeRecipient> nativeRecipient(ToolCall tool) {
        if (!"SendMessage".equals(tool.name())) {
            return Optional.empty();
        }
        return HookJson.present(tool.input(), "to").map(address -> new NativeRecipient(address, PARENT_ADDRESS.equals(address)));
    }

    @Override
    public Set<String> subagentAddresses(HostEvent event) {
        return switch (event) {
            case HostEvent.SubagentStarted started ->
                HookJson.present(started.context().actor().agentKey()).map(Set::of).orElse(Set.of());
            case HostEvent.ToolFinished finished when !finished.reportedFailure() && "Agent".equals(finished.tool().name()) ->
                HookJson.present(finished.tool().input(), "name").map(Set::of).orElse(Set.of());
            default -> Set.of();
        };
    }

    @Override
    public Optional<String> skillInvocationMessage() {
        return Optional.of("hooks.skillTrigger.invocation");
    }

    @Override
    public Optional<EditTargets> editTargets(ToolCall tool) {
        if (EDIT_TOOLS.contains(tool.name())) {
            String key = "NotebookEdit".equals(tool.name()) ? "notebook_path" : "file_path";
            return Optional.of(new EditTargets(HookJson.present(tool.input(), key).map(List::of).orElse(List.of()), false));
        }
        if ("Bash".equals(tool.name()) || "PowerShell".equals(tool.name())) {
            ShellEditTargets.Writes writes = ShellEditTargets.of(HookJson.text(tool.input(), "command"), "PowerShell".equals(tool.name()) ? ShellEditTargets.Dialect.POWERSHELL : ShellEditTargets.Dialect.POSIX, shellPaths);
            return Optional.ofNullable(writes).map(ShellEditTargets.Writes::editTargets);
        }
        return Optional.empty();
    }

    @Override
    public List<String> skillLoads(ToolCall tool) {
        if (!"Skill".equals(tool.name())) {
            return List.of();
        }
        Matcher skill = PLUGIN_SKILL.matcher(HookJson.text(tool.input(), "skill"));
        return skill.matches() ? List.of(skill.group(1)) : List.of();
    }

    @Override
    public String skillLoadBasis() {
        return "skill-tool";
    }

    @Override
    public List<String> noteRoots() {
        // A "*" segment is no valid path, so this is a pattern and not a Path: the slash is a separator in the JDK's file systems on every platform.
        return List.of(configDirectory.resolve("projects") + "/*/memory");
    }

    @Override
    public Set<Injection> injections() {
        return Set.of(Injection.TOOL_BOUNDARY, Injection.TURN_END);
    }
}
