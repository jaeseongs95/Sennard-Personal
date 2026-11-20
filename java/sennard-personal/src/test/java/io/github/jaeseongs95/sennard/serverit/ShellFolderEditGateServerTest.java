// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A shell call that writes what the hook cannot read (a script file, a build run by path) is a write inside a running workflow when the folder the shell followed to ({@code cd B && ...})
 * is covered by the workflow's writeTargets, not when only the session's working directory is. A folder the shell could not be followed to opens nothing. The hook input is the host's
 * own JSON (the Claude Code {@code PreToolUse} fixture of the Windows host) and each case runs against a real server with a real workflow.
 *
 * <p>The session's working directory is {@code A}; the workflow's workspace is {@code B}, with {@code writeTargets ["."]}. An unread write that no workflow covers is refused at the first call, as a write of a file outside the
 * workflow's change plan is (no file is free of a workflow); one the workflow covers is never refused.
 */
@Tag("business-db")
class ShellFolderEditGateServerTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int CALLS = 5;

    private static RunningServer server;
    private static Path base;

    @BeforeAll
    static void start() throws Exception {
        Path repository = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(repository.resolve("contracts")) || !Files.isDirectory(repository.resolve("skills"))) {
            repository = repository.getParent();
        }
        base = Files.createDirectories(Path.of("build", "shell-folder-gate-it", UUID.randomUUID().toString()).toAbsolutePath());
        // Its state directory is its own and is deleted when it closes (D-0194).
        server = SessionServerSupport.started(SessionServerSupport.sources(), "--sennard.content-root=" + repository, "--sennard.skills-dir=" + repository.resolve("skills"));
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static Path folder(String name) throws Exception {
        return Files.createDirectories(base.resolve(name + "-" + UUID.randomUUID()));
    }

    /** A running workflow of the session over everything under {@code workspace}; with a change plan, so that it is executing. */
    private static void startAll(Path workspace, String session) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("goal", "Change everything in " + workspace.getFileName());
        arguments.put("stages", List.of("minimal-implementation"));
        arguments.put("skills", List.of(Map.of("skillId", "minimal-implementation", "reason", "test")));
        arguments.put("acceptanceCriteria", List.of("The change works."));
        arguments.put("workspace", workspace.toString());
        arguments.put("writeTargets", List.of("."));
        arguments.put("changePlan", List.of("plan.txt"));
        Map<String, Object> meta = new HashMap<>(SessionServerSupport.meta("windows", "codex", session, "none"));
        meta.put("callId", "exec-" + UUID.randomUUID());
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + server.port).endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Sennard-Host-Label", "codex"));
        try (McpSyncClient client = McpClient.sync(transport.build()).requestTimeout(Duration.ofSeconds(120)).build()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("start_workflow").arguments(OperationKeys.withKey("start_workflow", arguments)).meta(meta).build());
            JsonNode body = MAPPER.readTree(((McpSchema.TextContent) result.content().getFirst()).text());
            assertTrue(body.path("ok").asBoolean(false), body.toString());
        }
    }

    /** The refusal text of a Bash call of the Claude host run in {@code cwd}, or null when it may run. */
    private static String bash(String session, Path cwd, String command) throws Exception {
        ObjectNode body = (ObjectNode) MAPPER.readTree(HookTestSupport.fixtureText("windows", "claude", "PreToolUse"));
        body.put("session_id", session).put("cwd", cwd.toString()).put("tool_name", "Bash").put("tool_use_id", "call-" + UUID.randomUUID());
        body.set("tool_input", MAPPER.valueToTree(Map.of("command", command)));
        RawHttp.Response response = SessionServerSupport.post(server.port, "/hooks/v1/claude/PreToolUse/continuity", body.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(200, response.status(), () -> new String(response.body(), StandardCharsets.UTF_8));
        JsonNode specific = SessionServerSupport.json(response).path("hookSpecificOutput");
        return specific.path("permissionDecision").asString("").equals("deny") ? specific.path("permissionDecisionReason").asString("") : null;
    }

    private static String shellPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    /** Runs {@code command} (where {n} is the call number) {@link #CALLS} times and returns the number of the first refused call, or -1 when none was refused. */
    private static int firstRefusal(String session, Path cwd, String command) throws Exception {
        for (int call = 0; call < CALLS; call++) {
            if (bash(session, cwd, command.replace("{n}", Integer.toString(call))) != null) {
                return call;
            }
        }
        return -1;
    }

    @Test
    void aBuildRunInTheWorkflowsFolderIsInsideItAndTheSameBuildRunWithoutTheFolderChangeIsRefused() throws Exception {
        Path sessionFolder = folder("session");
        Path workflowFolder = folder("workflow");
        String owner = UUID.randomUUID().toString();
        startAll(workflowFolder, owner);
        String build = "./gradlew build > " + shellPath(workflowFolder.resolve("build.log"));
        assertEquals(-1, firstRefusal(owner, sessionFolder, "cd " + shellPath(workflowFolder) + " && " + build), "after cd into the workflow's folder");
        assertEquals(0, firstRefusal(owner, sessionFolder, "./gradlew build"), "the same run with no cd, in a folder no workflow covers: it is refused at the first call");
    }

    @Test
    void aRelativeLocationChangeIsFollowedFromTheSessionsFolder() throws Exception {
        Path workflowFolder = folder("workflow");
        Path inside = Files.createDirectories(workflowFolder.resolve("java"));
        String owner = UUID.randomUUID().toString();
        startAll(workflowFolder, owner);
        assertEquals(-1, firstRefusal(owner, workflowFolder, "cd java && ./gradlew build"), "a relative folder of the session's own folder, which the workflow covers");
        assertEquals(-1, firstRefusal(owner, workflowFolder, "cd " + shellPath(inside) + " && ./gradlew build"), "a folder below the workflow's");
    }

    @Test
    void aNamedPathIsReadFromTheFolderTheShellFollowedTo() throws Exception {
        Path sessionFolder = folder("session");
        Path workflowFolder = folder("workflow");
        String owner = UUID.randomUUID().toString();
        startAll(workflowFolder, owner);
        assertEquals(-1, firstRefusal(owner, sessionFolder, "cd " + shellPath(workflowFolder) + " && sed -i s/a/b/ rel/file-{n}.txt"), "a relative file of the folder the shell is in");
        assertEquals(0, firstRefusal(owner, sessionFolder, "sed -i s/a/b/ rel/file-{n}.txt"), "the same files in the session's folder, which no workflow covers: refused at the first call");
    }

    @Test
    void aLocationChangeThatIsNotOneLiteralFolderOpensNothing() throws Exception {
        Path workflowFolder = folder("workflow");
        String own = running(workflowFolder);
        assertEquals(-1, firstRefusal(own, workflowFolder, "./gradlew build"), "the session's own folder is the workflow's: an unread write there is never refused");
        // Each case has a session and a workflow folder of its own: a workflow
        // whose folder is another running workflow's is refused as overlapping it.
        Path variableFolder = folder("workflow");
        String variable = running(variableFolder);
        assertEquals(0, firstRefusal(variable, variableFolder, "cd \"$TARGET\" && ./gradlew build"), "a folder named by a variable is not known, so the write is outside every workflow: refused at the first call");
        Path laterFolder = folder("workflow");
        String later = running(laterFolder);
        assertEquals(0, firstRefusal(later, laterFolder, "cd " + shellPath(laterFolder) + " && cd \"$TARGET\" && ./gradlew build"), "a literal folder first does not make a later unknown one known");
    }

    /** A new session with a running workflow over {@code workspace}. */
    private static String running(Path workspace) {
        String session = UUID.randomUUID().toString();
        startAll(workspace, session);
        return session;
    }

    @Test
    void anUnreadWriteInAFolderOutsideTheWorkflowIsRefusedWhateverTheSessionsFolderIs() throws Exception {
        Path workflowFolder = folder("workflow");
        Path elsewhere = folder("elsewhere");
        String owner = UUID.randomUUID().toString();
        startAll(workflowFolder, owner);
        assertEquals(0, firstRefusal(owner, workflowFolder, "cd " + shellPath(elsewhere) + " && ./gradlew build"), "the session's folder is the workflow's, but the shell is in a folder that is not: refused at the first call");
        assertNull(bash(owner, workflowFolder, "cd " + shellPath(workflowFolder) + " && ./gradlew build"), "a call in the workflow's folder is still the workflow's after that");
        assertNotNull(bash(owner, workflowFolder, "cd " + shellPath(elsewhere) + " && ./gradlew build"), "and the folder outside it stays refused");
    }
}
