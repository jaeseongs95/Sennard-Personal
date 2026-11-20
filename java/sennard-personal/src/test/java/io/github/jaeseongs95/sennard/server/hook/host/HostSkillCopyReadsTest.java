// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.hook.HostInstalledSkills;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.hook.ToolCall;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What each host's adapter reports as a skill loaded when the model reads a skill's file in the host's own installed copy of the plugin (the cause of the skill statistics showing
 * no Codex or agy use): the adapter passes only its host, and the common reader decides. Claude Code reports a skill from its Skill tool alone, so a read of its cache copy is not a
 * second report.
 */
class HostSkillCopyReadsTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final SennardProperties SETTINGS = new Binder(new MapConfigurationPropertySource(Map.of("sennard.hosts.claude-config-dir", "claude-config", "sennard.hosts.user-home", "home")))
            .bindOrCreate("sennard", SennardProperties.class);

    @TempDir
    Path home;

    private static ToolCall call(String tool, ObjectNode input) {
        return new ToolCall(null, tool, null, input);
    }

    private static ObjectNode input() {
        return MAPPER.createObjectNode();
    }

    private Path codexCopy() {
        return home.resolve(".codex").resolve("plugins").resolve("cache").resolve("market").resolve("sennard").resolve("6.0.0").resolve("skills");
    }

    private Path agyCopy() {
        return home.resolve(".gemini").resolve("config").resolve("plugins").resolve("sennard").resolve("skills");
    }

    /** Each host's copies as its package location reports them. */
    private SkillFileReads reads() {
        HostInstalledSkills copies = host -> host.equals(CodexHost.ID) ? List.of(codexCopy()) : host.equals(AntigravityHost.ID) ? List.of(agyCopy()) : List.of();
        return new SkillFileReads(home.resolve("installation").resolve("skills").toString(), copies);
    }

    @Test
    void codexReadingTheSkillInItsInstalledCopyIsThatSkillLoaded() {
        HostWorkFacts codex = new CodexWorkFacts(reads(), MAPPER, new MsysShellPaths());
        Path file = codexCopy().resolve("orchestrator").resolve("SKILL.md");
        assertEquals(List.of("orchestrator"), codex.skillLoads(call("exec_command", input().put("command", "cat '" + file + "'"))));
        assertEquals(List.of(), codex.skillLoads(call("exec_command", input().put("command", "cat '" + agyCopy().resolve("orchestrator").resolve("SKILL.md") + "'"))), "agy's copy is not Codex's");
        assertEquals(List.of(), codex.skillLoads(call("exec_command", input().put("command", "cat '" + home.resolve("other").resolve("orchestrator").resolve("SKILL.md") + "'"))), "an unrelated copy");
        assertEquals(List.of(), codex.skillLoads(call("exec_command", input().put("command", "ls '" + file + "'"))), "ls only looked at it");
    }

    /** Codex runs its commands in Git Bash on Windows: the file it names as {@code /c/Users/...} is the one named {@code C:\Users\...}. */
    @Test
    void codexReadingInGitBashSpellingIsCountedLikeTheDriveSpelling() {
        String drive = home.toString();
        org.junit.jupiter.api.Assumptions.assumeTrue(drive.length() > 2 && Character.isLetter(drive.charAt(0)) && drive.charAt(1) == ':', "this file system has no drive letters");
        HostWorkFacts codex = new CodexWorkFacts(reads(), MAPPER, new MsysShellPaths());
        String file = codexCopy().resolve("orchestrator").resolve("SKILL.md").toString();
        String gitBash = "/" + Character.toLowerCase(file.charAt(0)) + file.substring(2).replace('\\', '/');
        assertEquals(List.of("orchestrator"), codex.skillLoads(call("exec_command", input().put("command", "cat '" + file + "'"))));
        assertEquals(List.of("orchestrator"), codex.skillLoads(call("exec_command", input().put("command", "cat " + gitBash))));
    }

    @Test
    void antigravityReadingTheSkillInItsInstalledCopyIsThatSkillLoaded() {
        HostWorkFacts agy = new AntigravityWorkFacts(reads(), SETTINGS, new MsysShellPaths());
        Path file = agyCopy().resolve("orchestrator").resolve("SKILL.md");
        assertEquals(List.of("orchestrator"), agy.skillLoads(call("view_file", input().put("AbsolutePath", file.toString()))));
        assertEquals(List.of("orchestrator"), agy.skillLoads(call("run_command", input().put("CommandLine", "cat '" + file + "'"))));
        assertEquals(List.of(), agy.skillLoads(call("view_file", input().put("AbsolutePath", codexCopy().resolve("orchestrator").resolve("SKILL.md").toString()))), "Codex's copy is not agy's");
        assertEquals(List.of(), agy.skillLoads(call("view_file", input().put("AbsolutePath", home.resolve("other").resolve("orchestrator").resolve("SKILL.md").toString()))));
    }

    /** Claude Code's cache copy is read by the model through its Skill tool, which is the one report; a file read of the copy adds none. */
    @Test
    void claudeReportsOnlyItsSkillToolSoACacheCopyReadIsNotCountedTwice() {
        HostWorkFacts claude = new ClaudeWorkFacts(SETTINGS, new MsysShellPaths());
        Path file = home.resolve(".claude").resolve("plugins").resolve("cache").resolve("market").resolve("sennard").resolve("6.0.0").resolve("skills").resolve("orchestrator").resolve("SKILL.md");
        assertEquals(List.of(), claude.skillLoads(call("Read", input().put("file_path", file.toString()))));
        assertEquals(List.of(), claude.skillLoads(call("Bash", input().put("command", "cat '" + file + "'"))));
        assertEquals(List.of("orchestrator"), claude.skillLoads(call("Skill", input().put("skill", "sennard:orchestrator"))));
    }
}
