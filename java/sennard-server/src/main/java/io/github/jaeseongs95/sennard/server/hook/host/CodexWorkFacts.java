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

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.hook.ShellPaths;
import io.github.jaeseongs95.sennard.server.hook.ToolCall;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Codex: {@code apply_patch}, its shell tools (the one it opens a skill's file with), and the app's {@code wait_threads}. */
@Component
final class CodexWorkFacts implements HostWorkFacts {
    private static final Set<String> SHELL_TOOLS = Set.of("Bash", "exec_command", "shell_command", "shell");
    // Codex flattens the v1 namespace directly into its function name for the PreToolUse hook.
    static final Pattern NATIVE_PEER_SEND = Pattern.compile("^(multi_agent_v1send_input|send_message|followup_task)$");
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

    @Override
    public boolean sendsNativePeerMessage(ToolCall tool) {
        return tool.name() != null && NATIVE_PEER_SEND.matcher(tool.name()).matches();
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
