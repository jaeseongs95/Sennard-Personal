// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.hook.ShellPaths;
import io.github.jaeseongs95.sennard.server.hook.ToolCall;

/** Antigravity: its file tools are Write, Edit and MultiEdit under their own names, and {@code run_command} is Bash whatever shell it runs in. */
@Component
final class AntigravityWorkFacts implements HostWorkFacts {
    private static final Set<String> EDIT_TOOLS = Set.of("Write", "Edit", "MultiEdit");
    private static final String TOOL_PREFIX = "default_api:";
    static final Pattern NATIVE_PEER_SEND = Pattern.compile("^(" + TOOL_PREFIX + ")?send_message$");
    static final String SERVER_NAME = HookDefinition.MCP_SERVER + "_" + HookDefinition.MCP_SERVER;
    private static final Map<String, String> NATIVE_NAMES = Map.of("run_command", "Bash", "write_file", "Write", "edit_file", "Edit", "write_to_file", "Write",
            "replace_file_content", "Edit", "multi_replace_file_content", "MultiEdit");

    private final SkillFileReads skills;
    private final Path geminiDirectory;
    private final ShellPaths shellPaths;

    AntigravityWorkFacts(SkillFileReads skills, SennardProperties properties, ShellPaths shellPaths) {
        this.skills = skills;
        this.geminiDirectory = properties.hosts().geminiConfig();
        this.shellPaths = shellPaths;
    }

    @Override
    public HostId host() {
        return AntigravityHost.ID;
    }

    /** Antigravity calls an MCP tool through its {@code call_mcp_tool} with the server's and the tool's names ({@code ServerName}, {@code ToolName}, {@code Arguments}); it prefixes the registered server name, so {@code ServerName} is {@link #SERVER_NAME}. */
    @Override
    public Optional<SennardToolName> sennardToolName(String tool) {
        return Optional.of(new SennardToolName(tool, "call_mcp_tool", SERVER_NAME, null, null));
    }


    @Override
    public boolean runsCommand(ToolCall tool) {
        String name = canonical(tool.name());
        return "Bash".equals(name) || "PowerShell".equals(name);
    }

    /** The recipient still has to be resolved in the sender's observed session tree. */
    @Override
    public Optional<NativeSend> nativeSend(ToolCall tool) {
        if (tool.name() == null || !NATIVE_PEER_SEND.matcher(tool.name()).matches()) {
            return Optional.empty();
        }
        return Optional.of(new NativeSend(HookJson.firstPresentOrNull(tool.input(), "Recipient"), false, false));
    }

    @Override
    public Optional<EditTargets> editTargets(ToolCall tool) {
        String name = canonical(tool.name());
        if (EDIT_TOOLS.contains(name)) {
            return Optional.of(new EditTargets(HookJson.present(tool.input(), "TargetFile").or(() -> HookJson.present(tool.input(), "Path")).map(List::of).orElse(List.of()), false));
        }
        ShellEditTargets.Dialect dialect = switch (name) {
            // run_command names no shell in its event (the hooks documentation calls it "a Bash command"), and agy runs it in whatever shell the machine has: both readings count.
            case "Bash" -> ShellEditTargets.Dialect.EITHER;
            case "PowerShell" -> ShellEditTargets.Dialect.POWERSHELL;
            default -> null;
        };
        if (dialect == null) {
            return Optional.empty();
        }
        ShellEditTargets.Writes writes = ShellEditTargets.of(command(tool), dialect, shellPaths);
        return Optional.ofNullable(writes).map(ShellEditTargets.Writes::editTargets);
    }

    @Override
    public List<String> skillLoads(ToolCall tool) {
        String name = canonical(tool.name());
        if ("Bash".equals(name) || "PowerShell".equals(name)) {
            return skills.readByCommand(AntigravityHost.ID, command(tool));
        }
        return skills.named(AntigravityHost.ID, HookJson.firstPresentOrNull(tool.input(), "Path", "AbsolutePath", "TargetFile", "file_path"));
    }

    private static String canonical(String name) {
        String local = name.startsWith(TOOL_PREFIX) ? name.substring(TOOL_PREFIX.length()) : name;
        return NATIVE_NAMES.getOrDefault(local, local);
    }

    @Override
    public boolean checksTurnWork() {
        return true;
    }

    @Override
    public Optional<TurnWork> turnWork(ToolCall tool, boolean failed) {
        return AntigravityTurnEvidence.classify(canonical(tool.name()), tool.input(), failed);
    }

    private static String command(ToolCall tool) {
        return HookJson.present(tool.input(), "CommandLine").orElseGet(() -> HookJson.text(tool.input(), "command"));
    }

    @Override
    public List<String> noteRoots() {
        return List.of(geminiDirectory.resolve("antigravity-cli").resolve("brain").toString(), geminiDirectory.resolve(AntigravityHost.APPLICATION_FOLDER).resolve("brain").toString());
    }

    @Override
    public Set<Injection> injections() {
        return Set.of(Injection.TOOL_BOUNDARY);
    }

    /** The host's turn-start message does not stay in the conversation. */
    @Override
    public boolean restoresAtTurnStart() {
        return true;
    }
}
