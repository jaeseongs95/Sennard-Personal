// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AGS3-D-0005: a shell call that runs a script file (or a command that rewrites files without naming them) is a write whose files the hook cannot read. Every file of a request is changed
 * inside a change workflow that took it in its change plan, so with no workflow such a call is refused at the first call, as a call that writes a file by name is; and with a change workflow
 * that covers the folder the shell is in, the call is a write inside it and runs. A shell call that runs no script file is no write.
 */
@Tag("business-db")
class ScriptRunEditGateServerTest {
    private static final String EXECUTION_GUIDANCE = "start_operation_workflow";
    private static final String CHANGE_GUIDANCE = "no workflow handles this request";
    private static RunningServer server;

    @BeforeAll
    static void start() throws Exception {
        // its state directory is its own and is deleted when it closes (D-0194)
        server = SessionServerSupport.started(SessionServerSupport.sources());
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static String session(String host) throws Exception {
        return session(host, Path.of("build", "script-run").toAbsolutePath());
    }

    private static String session(String host, Path workspace) throws Exception {
        String id = SessionServerSupport.uniqueSession();
        assertEquals(200, DomainServerSupport.hook(server.port, "windows", host, "SessionStart", null, id, body -> body.put("cwd", workspace.toString())).status());
        return id;
    }

    /** A folder of its own for a test that opens a workflow on it (two workflows do not overlap on one folder). */
    private static Path workspace() {
        return Path.of("build", "script-run", UUID.randomUUID().toString()).toAbsolutePath();
    }

    /** The refusal text of the call made in this folder, or null when it runs. */
    private static String refusal(String host, String id, Path cwd, String tool, ObjectNode input) throws Exception {
        var response = DomainServerSupport.hook(server.port, "windows", host, "PreToolUse", "continuity", id, body -> {
            body.put("cwd", cwd.toString()).put("tool_use_id", UUID.randomUUID().toString());
            DomainServerSupport.tool(tool, input).accept(body);
        });
        assertEquals(200, response.status());
        JsonNode result = DomainServerSupport.MAPPER.readTree(response.body());
        JsonNode specific = result.path("hookSpecificOutput");
        return "deny".equals(specific.path("permissionDecision").asString()) ? specific.path("permissionDecisionReason").asString() : null;
    }

    private static String refusal(String host, String id, String tool, ObjectNode input) throws Exception {
        return refusal(host, id, Path.of("build", "script-run").toAbsolutePath(), tool, input);
    }

    /** A call with unobservable effects and no workflow is refused with guidance for the corresponding workflow kind. */
    private static void assertRefusedWithoutWorkflow(String refused, String guidance, String label) {
        assertNotNull(refused, label);
        assertTrue(refused.contains("path unknown") && refused.contains(guidance), label + ": " + refused);
    }

    @Test
    void aShellCallThatRunsAScriptFileWithNoWorkflowIsRefusedAtTheFirstCallAndEveryOneAfterItOnClaudeCode() throws Exception {
        String id = session("claude");
        for (int call = 0; call < 3; call++) {
            ObjectNode input = DomainServerSupport.MAPPER.createObjectNode().put("command", "node ./write-three-" + call + ".mjs");
            assertRefusedWithoutWorkflow(refusal("claude", id, "Bash", input), EXECUTION_GUIDANCE, "script run " + call);
        }
    }

    @Test
    void aShellCallThatRunsAScriptFileWithNoWorkflowIsRefusedAtTheFirstCallAndEveryOneAfterItOnCodex() throws Exception {
        String id = session("codex");
        for (int call = 0; call < 3; call++) {
            ObjectNode input = DomainServerSupport.MAPPER.createObjectNode().put("cmd", "python write-" + call + ".py").put("shell", "/bin/bash");
            assertRefusedWithoutWorkflow(refusal("codex", id, "exec_command", input), EXECUTION_GUIDANCE, "script run " + call);
        }
    }

    /** The same script runs, with a change workflow of the request that covers the folder the shell is in: the write is inside it. */
    @Test
    void aShellCallThatRunsAScriptFileRunsInAChangeWorkflowThatCoversTheFolderOfTheShell() throws Exception {
        Path workspace = workspace();
        String id = session("claude", workspace);
        ChangeWorkflows.open(server, "claude", id, workspace, List.of("."));
        for (int call = 0; call < 3; call++) {
            ObjectNode input = DomainServerSupport.MAPPER.createObjectNode().put("command", "node ./write-three-" + call + ".mjs");
            assertNull(refusal("claude", id, workspace, "Bash", input), "script run " + call);
        }
    }

    @Test
    void shellCallsThatRunNoScriptFileAreNeverRefused() throws Exception {
        String id = session("claude");
        for (int call = 0; call < 5; call++) {
            ObjectNode input = DomainServerSupport.MAPPER.createObjectNode().put("command", call % 2 == 0 ? "node --version" : "python -m pytest -q");
            assertNull(refusal("claude", id, "Bash", input), "call " + call);
        }
    }

    private static ObjectNode shellInput(String field, String shell, String command) {
        ObjectNode input = DomainServerSupport.MAPPER.createObjectNode().put(field, command);
        return shell == null ? input : input.put("shell", shell);
    }

    /** One shell call of a host: the tool, the field that holds the command line, the shell the event names (null when it names none) and the command line, where {n} is the call number. */
    static Stream<Arguments> unreadWrites() {
        return Stream.of(
                Arguments.of("bash script file", "claude", "Bash", "command", null, "bash ./run-{n}.sh", EXECUTION_GUIDANCE),
                Arguments.of("sh script file", "claude", "Bash", "command", null, "sh run-{n}.sh", EXECUTION_GUIDANCE),
                Arguments.of("started by path", "claude", "Bash", "command", null, "./run-{n}.sh", EXECUTION_GUIDANCE),
                Arguments.of("perl script file", "claude", "Bash", "command", null, "perl run-{n}.pl", EXECUTION_GUIDANCE),
                Arguments.of("php script file", "claude", "Bash", "command", null, "php run-{n}.php", EXECUTION_GUIDANCE),
                Arguments.of("deno run", "claude", "Bash", "command", null, "deno run run-{n}.ts", EXECUTION_GUIDANCE),
                Arguments.of("git pull", "claude", "Bash", "command", null, "git pull", CHANGE_GUIDANCE),
                Arguments.of("git merge", "claude", "Bash", "command", null, "git merge topic-{n}", CHANGE_GUIDANCE),
                Arguments.of("git cherry-pick", "claude", "Bash", "command", null, "git cherry-pick abc{n}", CHANGE_GUIDANCE),
                Arguments.of("git rebase", "claude", "Bash", "command", null, "git rebase main", CHANGE_GUIDANCE),
                Arguments.of("git stash pop", "claude", "Bash", "command", null, "git stash pop", CHANGE_GUIDANCE),
                Arguments.of("git reset --hard", "claude", "Bash", "command", null, "git reset --hard", CHANGE_GUIDANCE),
                Arguments.of("git checkout of a branch", "claude", "Bash", "command", null, "git checkout main", CHANGE_GUIDANCE),
                Arguments.of("git switch", "claude", "Bash", "command", null, "git switch main", CHANGE_GUIDANCE),
                Arguments.of("pwsh -File", "claude", "PowerShell", "command", null, "pwsh -File run-{n}.ps1", EXECUTION_GUIDANCE),
                Arguments.of("pwsh script file", "claude", "PowerShell", "command", null, "pwsh run-{n}.ps1", EXECUTION_GUIDANCE),
                Arguments.of("PowerShell script by path", "claude", "PowerShell", "command", null, ".\\run-{n}.ps1", EXECUTION_GUIDANCE),
                Arguments.of("codex bash script file", "codex", "exec_command", "cmd", "/bin/bash", "bash run-{n}.sh", EXECUTION_GUIDANCE),
                Arguments.of("codex git pull", "codex", "exec_command", "cmd", "/bin/bash", "git pull", CHANGE_GUIDANCE),
                Arguments.of("codex cmd.exe batch file", "codex", "exec_command", "cmd", "cmd.exe", "run-{n}.bat", EXECUTION_GUIDANCE));
    }

    /**
     * Each command here changes files the hook cannot read (a script it does not run, a git command that rewrites the working tree): with
     * no workflow it is refused at the first call, and at every call after it, as a write of no known file, whichever host and shell tool says it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadWrites")
    void shellCallsThatRunCodeTheHookCannotReadAreRefusedWithNoWorkflowAtTheFirstCall(String label, String host, String tool, String field, String shell, String command,
            String guidance) throws Exception {
        String id = session(host);
        for (int call = 0; call < 3; call++) {
            ObjectNode input = shellInput(field, shell, command.replace("{n}", Integer.toString(call)));
            assertRefusedWithoutWorkflow(refusal(host, id, tool, input), guidance, label + " call " + call);
        }
    }

    /** The same commands in the session of a change workflow that covers the folder the shell is in (the whole workspace): they run, the writes being inside the workflow. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadWrites")
    void shellCallsThatRunCodeTheHookCannotReadRunInAChangeWorkflowThatCoversTheFolderOfTheShell(String label, String host, String tool, String field, String shell, String command,
            String guidance) throws Exception {
        Path workspace = workspace();
        String id = session(host, workspace);
        ChangeWorkflows.open(server, host, id, workspace, List.of("."));
        for (int call = 0; call < 3; call++) {
            ObjectNode input = shellInput(field, shell, command.replace("{n}", Integer.toString(call)));
            assertNull(refusal(host, id, workspace, tool, input), label + " call " + call);
        }
    }

    /** Redirects through a nested cmd.exe: the hook follows them (ShellEditTargetsTest), so the file is read and the call is a write of that file, not of an unknown one. */
    static Stream<Arguments> redirectsThroughANestedCmd() {
        return Stream.of(
                Arguments.of("cmd /c with a redirect", "claude", "Bash", "command", null, "cmd /c \"echo x > out-{n}.txt\""),
                Arguments.of("codex cmd /c with a redirect", "codex", "exec_command", "cmd", "powershell.exe", "cmd /c \"echo x > out-{n}.txt\""));
    }

    /** With no workflow the call is refused at the first call and at every one after it, and the refusal names the file the redirect writes (it is no write of an unknown path). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("redirectsThroughANestedCmd")
    void aRedirectThroughANestedCmdWithNoWorkflowIsRefusedAtTheFirstCallAndNamesTheFile(String label, String host, String tool, String field, String shell, String command) throws Exception {
        String id = session(host);
        for (int call = 0; call < 3; call++) {
            String refused = refusal(host, id, tool, shellInput(field, shell, command.replace("{n}", Integer.toString(call))));
            assertNotNull(refused, label + " call " + call);
            assertTrue(refused.contains("out-" + call + ".txt") && refused.contains("no workflow handles this request") && !refused.contains("path unknown"), label + " call " + call + ": " + refused);
        }
    }

    /** The same redirect in the session of a change workflow that covers the folder the shell is in: the file is inside it and the call runs. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("redirectsThroughANestedCmd")
    void aRedirectThroughANestedCmdRunsInAChangeWorkflowThatCoversTheFolderOfTheShell(String label, String host, String tool, String field, String shell, String command) throws Exception {
        Path workspace = workspace();
        String id = SessionServerSupport.uniqueSession();
        ChangeWorkflows.open(server, id, workspace, List.of("."));
        for (int call = 0; call < 3; call++) {
            assertNull(refusal(host, id, workspace, tool, shellInput(field, shell, command.replace("{n}", Integer.toString(call)))), label + " call " + call);
        }
    }

    /**
     * AGS3-D-0196: the file commands built into cmd.exe as Codex names it. The files they name are the files they write (the refusal names each of them), and one given with a glob is an unread
     * write. {@code names} are the files the refusal has to name, separated by {@code |} ({n} is the call number), or {@code path unknown} for a command that names none.
     */
    static Stream<Arguments> cmdBuiltins() {
        return Stream.of(
                Arguments.of("del", "del out-{n}.txt", "out-{n}.txt"),
                Arguments.of("erase", "erase out-{n}.txt", "out-{n}.txt"),
                Arguments.of("del of a glob", "del *.tmp", "path unknown"),
                Arguments.of("copy", "copy src.txt out-{n}.txt", "out-{n}.txt"),
                Arguments.of("xcopy", "xcopy src out-{n} /E /I", "out-{n}"),
                Arguments.of("robocopy", "robocopy src out-{n} /MIR", "out-{n}"),
                Arguments.of("mklink", "mklink out-{n} target", "out-{n}"),
                Arguments.of("md", "md out-{n}", "out-{n}"),
                Arguments.of("mkdir", "mkdir out-{n}", "out-{n}"),
                Arguments.of("rd", "rd /s /q out-{n}", "out-{n}"),
                Arguments.of("rmdir", "rmdir out-{n}", "out-{n}"),
                Arguments.of("del in a nested cmd", "cmd /c \"del out-{n}.txt\"", "out-{n}.txt"),
                // Two files a call: the source and the destination.
                Arguments.of("move", "move old-{n}.txt new-{n}.txt", "old-{n}.txt|new-{n}.txt"),
                Arguments.of("ren", "ren old-{n}.txt new-{n}.txt", "old-{n}.txt|new-{n}.txt"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cmdBuiltins")
    void cmdBuiltinCallsThatWriteFilesWithNoWorkflowAreRefusedAtTheFirstCallAndNameTheFilesTheyWrite(String label, String command, String names) throws Exception {
        String id = session("codex");
        for (int call = 0; call < 2; call++) {
            String refused = refusal("codex", id, "exec_command", shellInput("cmd", "cmd.exe", command.replace("{n}", Integer.toString(call))));
            assertNotNull(refused, label + " call " + call);
            for (String name : names.split("\\|")) {
                assertTrue(refused.contains(name.replace("{n}", Integer.toString(call))), label + " call " + call + " names " + name + ": " + refused);
            }
        }
    }

    /**
     * A command that writes files is a write inside a change workflow that covers them, and a write outside one whose writeTargets do not: what it names (or, for a glob, what it cannot name) is
     * what the instance of the request is asked about.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cmdBuiltins")
    void cmdBuiltinCallsThatWriteFilesRunInAChangeWorkflowThatCoversThemAndAreRefusedOutsideOne(String label, String command, String names) throws Exception {
        // The workflow covers the whole workspace the shell is in: the files named, and a glob the hook cannot read, are inside it.
        Path workspace = workspace();
        String id = SessionServerSupport.uniqueSession();
        ChangeWorkflows.open(server, id, workspace, List.of("."));
        assertNull(refusal("codex", id, workspace, "exec_command", shellInput("cmd", "cmd.exe", command.replace("{n}", "0"))), label);
        // A workflow of the folder "kept": the files the command names are not in it, and a glob it cannot read is no file of it.
        Path narrow = workspace();
        String other = SessionServerSupport.uniqueSession();
        ChangeWorkflows.open(server, other, narrow, List.of("kept"));
        String refused = refusal("codex", other, narrow, "exec_command", shellInput("cmd", "cmd.exe", command.replace("{n}", "0")));
        assertNotNull(refused, label + " is outside a workflow whose writeTargets are kept");
        assertTrue(refused.contains("change plan") || refused.contains("cannot be read"), refused);
    }

    @Test
    void cmdCallsThatOnlyReadAreNeverRefused() throws Exception {
        String id = session("codex");
        for (String command : new String[] {"dir", "dir /s /b", "type a.txt", "echo hi"}) {
            for (int call = 0; call < 2; call++) {
                assertNull(refusal("codex", id, "exec_command", shellInput("cmd", "cmd.exe", command)), command + " call " + call);
            }
        }
    }

    static Stream<Arguments> readOnly() {
        return Stream.of(
                Arguments.of("git status", "claude", "Bash", "command", null, "git status"),
                Arguments.of("git log", "claude", "Bash", "command", null, "git log --oneline -5"),
                Arguments.of("git diff", "claude", "Bash", "command", null, "git diff HEAD~1"),
                Arguments.of("git show", "claude", "Bash", "command", null, "git show HEAD"),
                Arguments.of("git branch", "claude", "Bash", "command", null, "git branch -a"),
                Arguments.of("bash version", "claude", "Bash", "command", null, "bash --version"),
                Arguments.of("pwsh command", "claude", "PowerShell", "command", null, "pwsh -NoProfile -Command Get-ChildItem"),
                Arguments.of("cmd /c dir", "claude", "Bash", "command", null, "cmd /c dir"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("readOnly")
    void shellCallsThatOnlyReadAreNeverRefused(String label, String host, String tool, String field, String shell, String command) throws Exception {
        String id = session(host);
        for (int call = 0; call < 5; call++) {
            assertNull(refusal(host, id, tool, shellInput(field, shell, command)), label + " call " + call);
        }
    }
}
