// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts.EditTargets;
import io.github.jaeseongs95.sennard.server.hook.ToolCall;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The shell a command is read in comes from the host's event, never from the server's operating system. A host that names its shell gets that shell's reading
 * (Codex's {@code shell} field, Claude Code's tool name); a host whose event names none gets both. The commands are chosen so that the two readings differ:
 * {@code Set-Content} writes a file only in PowerShell, and {@code nul} is the null device only there.
 */
class HostShellDialectTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final SennardProperties SETTINGS = new Binder(new MapConfigurationPropertySource(Map.of("sennard.hosts.claude-config-dir", "claude-config", "sennard.hosts.user-home", "home")))
            .bindOrCreate("sennard", SennardProperties.class);
    private final HostWorkFacts claude = new ClaudeWorkFacts(SETTINGS, new MsysShellPaths());
    private final HostWorkFacts codex = new CodexWorkFacts(new SkillFileReads(""), MAPPER, new MsysShellPaths());
    private final HostWorkFacts antigravity = new AntigravityWorkFacts(new SkillFileReads(""), SETTINGS, new MsysShellPaths());

    private static ObjectNode input() {
        return MAPPER.createObjectNode();
    }

    private static ToolCall call(String tool, ObjectNode input) {
        return new ToolCall(null, tool, null, input);
    }

    private static List<String> written(HostWorkFacts host, ToolCall call) {
        return host.editTargets(call).map(EditTargets::paths).orElse(List.of());
    }

    @Test
    void codexReadsACommandInTheShellItsEventNames() {
        assertEquals(List.of("a.txt"), written(codex, call("Bash", input().put("command", "Set-Content a.txt x").put("shell", "powershell.exe"))));
        assertEquals(List.of("a.txt"), written(codex, call("shell_command", input().put("command", "Set-Content a.txt x").put("shell", "pwsh"))));
        assertEquals(List.of(), written(codex, call("Bash", input().put("command", "Set-Content a.txt x").put("shell", "/bin/bash"))), "bash does not write a file with Set-Content");
        assertEquals(List.of("b.txt"), written(codex, call("exec_command", input().put("cmd", "echo x > b.txt").put("shell", "/bin/bash"))));
        assertEquals(List.of(), written(codex, call("Bash", input().put("command", "echo x > nul").put("shell", "powershell"))), "nul is PowerShell's null device");
        assertEquals(List.of("nul"), written(codex, call("Bash", input().put("command", "echo x > nul").put("shell", "bash"))), "nul is a file name to bash");
    }

    /** AGS3-D-0196: a shell that is cmd.exe keeps the backslashes of the paths it is given. */
    @Test
    void codexReadsACmdCommandWithWindowsPathRules() {
        for (String shell : List.of("cmd.exe", "cmd", "CMD.EXE", "C:\\Windows\\System32\\cmd.exe")) {
            assertEquals(List.of("D:\\repo\\a.txt"), written(codex, call("exec_command", input().put("cmd", "echo x>D:\\repo\\a.txt").put("shell", shell))), shell);
        }
        assertEquals(List.of("D:\\repo\\a.txt"), written(codex, call("shell_command", input().put("command", "echo x>D:\\repo\\a.txt").put("shell", "cmd.exe"))));
        assertEquals(List.of(), written(codex, call("exec_command", input().put("cmd", "echo x > nul").put("shell", "cmd.exe"))), "nul is cmd's null device");
    }

    private static boolean unreadWrite(HostWorkFacts host, ToolCall call) {
        return host.editTargets(call).map(EditTargets::incomplete).orElse(false);
    }

    /** AGS3-D-0005: running a script file is a write the hook cannot read, on every host that reads a shell command. */
    @Test
    void everyHostCountsTheRunOfAScriptFileAsAnUnreadWrite() {
        assertTrue(unreadWrite(codex, call("exec_command", input().put("cmd", "node ./write-three.mjs").put("shell", "/bin/bash"))));
        assertTrue(unreadWrite(codex, call("Bash", input().put("command", "python write.py"))));
        assertTrue(unreadWrite(claude, call("Bash", input().put("command", "node ./write-three.mjs"))));
        assertTrue(unreadWrite(claude, call("PowerShell", input().put("command", "python write.py"))));
        assertTrue(unreadWrite(antigravity, call("run_command", input().put("CommandLine", "node ./write-three.mjs"))));
        assertFalse(unreadWrite(codex, call("exec_command", input().put("cmd", "node --version").put("shell", "/bin/bash"))));
    }

    @Test
    void codexReadsACommandBothWaysWhenItsEventNamesNoShell() {
        assertEquals(List.of("a.txt"), written(codex, call("Bash", input().put("command", "Set-Content a.txt x"))));
        assertEquals(List.of("b.txt"), written(codex, call("Bash", input().put("command", "echo x > b.txt"))));
        assertEquals(List.of("b.txt", "a.txt"), written(codex, call("Bash", input().put("command", "Set-Content a.txt x; echo y > b.txt"))));
        assertEquals(List.of("b.txt"), written(codex, call("Bash", input().put("command", "cat > b.txt <<EOF\nSet-Content a.txt x\nEOF"))), "a heredoc is POSIX only");
    }

    @Test
    void claudeCodeReadsABashCallAsPosixAndAPowerShellCallAsPowerShell() {
        assertEquals(List.of(), written(claude, call("Bash", input().put("command", "Set-Content a.txt x"))));
        assertEquals(List.of("a.txt"), written(claude, call("PowerShell", input().put("command", "Set-Content a.txt x"))));
        assertEquals(List.of("b.txt"), written(claude, call("Bash", input().put("command", "echo x > b.txt"))));
        assertEquals(List.of(), written(claude, call("PowerShell", input().put("command", "echo x > nul"))));
        assertEquals(List.of("nul"), written(claude, call("Bash", input().put("command", "echo x > nul"))));
    }

    @Test
    void antigravityReadsItsRunCommandBothWaysAndItsPowerShellToolAsPowerShell() {
        assertEquals(List.of("a.txt"), written(antigravity, call("run_command", input().put("CommandLine", "Set-Content a.txt x"))));
        assertEquals(List.of("b.txt"), written(antigravity, call("default_api:run_command", input().put("CommandLine", "echo x > b.txt"))));
        assertEquals(List.of("a.txt"), written(antigravity, call("PowerShell", input().put("CommandLine", "Set-Content a.txt x"))));
        assertEquals(List.of(), written(antigravity, call("PowerShell", input().put("CommandLine", "echo x > nul"))));
        assertEquals(List.of("b.txt"), written(antigravity, call("Bash", input().put("command", "cat > b.txt <<EOF\nSet-Content a.txt x\nEOF"))), "a heredoc is POSIX only");
    }
}
