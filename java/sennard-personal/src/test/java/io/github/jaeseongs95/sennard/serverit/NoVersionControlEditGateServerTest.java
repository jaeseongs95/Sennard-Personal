// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

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
 * The edit gate does not ask whether a folder is under version control: a file of a folder with no .git, .hg, .svn or .jj in it or above it is judged like any other file that is not
 * exempt (it needs a workflow that covers it), and it is a file the call names outside the running workflow, so it also keeps a shell write whose
 * path cannot be read from being taken as inside a workflow that covers the whole workspace (AGS3-D-0148).
 *
 * <p>The folder without a marker is made beside the checkout, in its parent folder, and removed at the end; where that parent is itself in a repository the
 * fixture says so and fails, because then the test would not show that the marker is not asked.
 */
@Tag("business-db")
class NoVersionControlEditGateServerTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Map<String, String> UNREAD = Map.of("command", "cat > \"$SCRATCH/x.py\"");

    private static RunningServer server;
    private static Path repository;
    private static Path productBase;
    private static final List<Path> FIXTURES = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        repository = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(repository.resolve("contracts")) || !Files.isDirectory(repository.resolve("skills"))) {
            repository = repository.getParent();
        }
        productBase = Path.of("build", "unversioned-outside-it", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(productBase);
        FIXTURES.add(productBase);
        // Its state directory is its own and is deleted when it closes (D-0194).
        server = SessionServerSupport.started(SessionServerSupport.sources(), "--sennard.content-root=" + repository, "--sennard.skills-dir=" + repository.resolve("skills"));
    }

    @AfterAll
    static void stop() throws IOException {
        server.close();
        for (Path fixture : FIXTURES) {
            try (Stream<Path> tree = Files.walk(fixture)) {
                tree.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static Path product() throws Exception {
        return Files.createDirectories(productBase.resolve(UUID.randomUUID().toString()));
    }

    /** A folder that is neither exempt nor under version control: nothing named .git or the like in it or above it. */
    private static Path unversionedFolder() throws Exception {
        Path folder = Files.createTempDirectory(repository.getParent(), "sennard-unversioned-outside-");
        FIXTURES.add(folder);
        for (Path place = folder; place != null; place = place.getParent()) {
            for (String marker : List.of(".git", ".hg", ".svn", ".jj")) {
                assertFalse(Files.exists(place.resolve(marker)), "the fixture folder must be outside version control: " + place);
            }
        }
        return folder;
    }

    private static JsonNode startAll(Path workspace, String session) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("goal", "Change everything in " + workspace.getFileName());
        arguments.put("stages", List.of("minimal-implementation"));
        arguments.put("skills", List.of(Map.of("skillId", "minimal-implementation", "reason", "test")));
        arguments.put("acceptanceCriteria", List.of("The change works."));
        arguments.put("workspace", workspace.toString());
        arguments.put("writeTargets", List.of("."));
        // With a change plan the run starts in its executing phase; without one it starts in its planning phase, which refuses every write inside its write surface.
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
            return body.get("data");
        }
    }

    /** The refusal text of a Bash call of the Claude host, or null when it may run. */
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

    @Test
    void reviewAFileOutsideTheWorkflowMustKeepTheUnknownWriteRefused() throws Exception {
        Path workspace = product();
        Path elsewhere = unversionedFolder();
        String owner = UUID.randomUUID().toString();
        startAll(workspace, owner);
        String mixed = "printf a > " + shellPath(elsewhere.resolve("a.txt")) + "; " + UNREAD.get("command");
        // The call writes two things: the named file, outside the workflow, and a write whose file cannot be read. Were the unread write taken as inside the whole-workspace workflow
        // because the call's only named file is outside it, the call would be refused for that file alone; both are refused, the refusal naming the file and the unknown write.
        String refused = bash(owner, workspace, mixed);
        assertNotNull(refused, "the file named outside the workflow must keep the unread write from being taken as inside the whole-workspace workflow");
        assertTrue(refused.contains("a.txt") && refused.contains("path unknown") && refused.contains("cannot be read"), refused);
        // The same unread write with the named file inside the workflow is wholly inside it.
        assertNull(bash(owner, workspace, "printf a > " + shellPath(workspace.resolve("a.txt")) + "; " + UNREAD.get("command")));
    }

    @Test
    void aFileOfAFolderWithoutVersionControlIsJudgedLikeAnyOtherFile() throws Exception {
        Path workspace = product();
        Path elsewhere = unversionedFolder();
        String owner = UUID.randomUUID().toString();
        startAll(workspace, owner);
        // Nothing exempts the folder for having no .git: the file is outside the workflow of the request, and is refused at the first call and every call after it.
        for (int call = 0; call < 3; call++) {
            String refused = bash(owner, workspace, "printf a > " + shellPath(elsewhere.resolve("n" + call + ".txt")));
            assertNotNull(refused, "call " + call);
            assertTrue(refused.contains("n" + call + ".txt") && refused.contains("change plan"), refused);
        }
        // With no workflow at all it is refused as well; and a workflow whose folder is that one takes its files in: the folder is judged by the workflow that covers it, not by its marker.
        assertNotNull(bash(UUID.randomUUID().toString(), workspace, "printf a > " + shellPath(elsewhere.resolve("alone.txt"))));
        String served = UUID.randomUUID().toString();
        startAll(elsewhere, served);
        assertNull(bash(served, workspace, "printf a > " + shellPath(elsewhere.resolve("kept.txt"))));
    }

    @Test
    void anUnreadWriteAloneInAWholeWorkspaceWorkflowIsStillNeverRefused() throws Exception {
        Path workspace = product();
        String owner = UUID.randomUUID().toString();
        startAll(workspace, owner);
        for (int call = 0; call < 5; call++) {
            assertEquals(null, bash(owner, workspace, UNREAD.get("command")), "call " + call);
        }
    }
}
