// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The edit gate's exception paths are the user's: signed with a passkey on the admin page, or the environment list alone. Each case runs a real
 * server, signs the list the way the page does and sends the hook a Bash write as a host sends it. Inside one case a write is a different file each
 * time; with no workflow in the request a file that is not exempt is refused at the first write.
 */
@Tag("business-db")
class EditGateExceptionPathsServerTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static ToolServerSupport server;
    private static ToolServerSupport.Passkey key;
    private static Path base;

    @BeforeAll
    static void start() throws Exception {
        server = ToolServerSupport.start();
        key = server.owner();
        // Under this checkout, so that the files are under version control; the OS temporary directory is exempt on its own.
        base = Path.of("build", "edit-exempt-it", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(base);
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static void declare(ToolServerSupport on, ToolServerSupport.Passkey signer, String... paths) throws Exception {
        ObjectNode change = MAPPER.createObjectNode().put("op", "EDIT_EXEMPT_SET");
        var array = change.putArray("paths");
        for (String path : paths) {
            array.add(path);
        }
        on.sign(signer, change);
    }

    private static Path directory(String name) throws Exception {
        return Files.createDirectories(base.resolve(name + "-" + UUID.randomUUID()));
    }

    private static String slashes(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static byte[] hookBody(String session, Path cwd, String command) {
        ObjectNode body = (ObjectNode) MAPPER.readTree(HookTestSupport.fixtureText("windows", "claude", "PreToolUse"));
        body.put("session_id", session).put("cwd", cwd.toString()).put("tool_name", "Bash").put("tool_use_id", "call-" + UUID.randomUUID());
        body.set("tool_input", MAPPER.createObjectNode().put("command", command));
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The refusal text of the gate for this Bash command, or null when the command may run. */
    private static String refusal(ToolServerSupport on, String session, Path cwd, String command) throws Exception {
        RawHttp.Response response = SessionServerSupport.post(on.server.port, "/hooks/v1/claude/PreToolUse/continuity", hookBody(session, cwd, command));
        assertEquals(200, response.status(), () -> new String(response.body(), StandardCharsets.UTF_8));
        JsonNode specific = SessionServerSupport.json(response).path("hookSpecificOutput");
        return specific.path("permissionDecision").asString("").equals("deny") ? specific.path("permissionDecisionReason").asString("") : null;
    }

    /** Writes this many different files named {@code under}/name and returns the first refusal, or null when none was refused. */
    private static String writes(ToolServerSupport on, int count, String under) throws Exception {
        String session = SessionServerSupport.uniqueSession();
        for (int index = 0; index < count; index++) {
            String refused = refusal(on, session, base, "echo x > " + under + "/file-" + index + ".txt");
            if (refused != null) {
                return refused;
            }
        }
        return null;
    }

    @Test
    void withNoExceptionListAFileUnderVersionControlIsRefusedAtTheFirstWrite() throws Exception {
        declare(server, key);
        assertNotNull(writes(server, 1, slashes(directory("plain"))));
    }

    @Test
    void aSignedDirectoryIsNotCountedAndTheFilesNextToItAre() throws Exception {
        Path exempt = directory("exempt");
        Path sibling = base.resolve(exempt.getFileName() + "x");
        Files.createDirectories(sibling);
        Path other = directory("other");
        declare(server, key, exempt.toString());
        assertNull(writes(server, 6, slashes(exempt)), "under the directory");
        assertNull(writes(server, 6, slashes(exempt.resolve("deeper"))), "deeper under it");
        assertNotNull(writes(server, 1, slashes(sibling)), "a name that only starts like it is another directory");
        assertNotNull(writes(server, 1, slashes(exempt) + "/../" + other.getFileName()), "climbing out of it lands where it lands");
        assertNotNull(writes(server, 1, slashes(other)), "another directory");
    }

    @Test
    void aSignedDirectoryInTheOtherSpellingOfTheSameOsIsTakenAsItIs() throws Exception {
        Path exempt = directory("spelling");
        declare(server, key, slashes(exempt));
        assertNull(writes(server, 5, slashes(exempt)));
    }

    @Test
    void aPathOfGitBashsOwnRootIsDeclaredAsTheTextTheUserWritesAndMatchedOnWholeSegments() throws Exception {
        declare(server, key, "/tmp");
        assertNull(writes(server, 5, "/tmp/scratch"), "under /tmp");
        if (WINDOWS) {
            assertNotNull(writes(server, 1, "/tmpx/scratch"), "a name that only starts like /tmp");
            assertNotNull(writes(server, 1, "/tmp/../etc"), "climbing out of /tmp");
            assertNotNull(writes(server, 1, "/usr/local"), "another directory of that root");
            declare(server, key);
            assertNotNull(writes(server, 1, "/tmp/scratch"), "with nothing declared a path of that root has no file to look for and is refused");
        }
    }

    @Test
    void anEntryOfGitBashsDriveSpellingIsTheDriveItNames() throws Exception {
        Path exempt = directory("drive");
        String given = WINDOWS && exempt.toString().matches("^[A-Za-z]:.*") ? "/" + Character.toLowerCase(exempt.toString().charAt(0)) + slashes(exempt).substring(2) : slashes(exempt);
        declare(server, key, given);
        assertNull(writes(server, 5, slashes(exempt)));
    }

    @Test
    void aSettingOfTheEnvironmentOrTheCommandLineMakesNoExceptionAndTheSignedListIsTheOnlySource() throws Exception {
        Path named = directory("environment");
        Path signed = directory("signed");
        try (ToolServerSupport configured = ToolServerSupport.startWith("--SENNARD_PHASE_EXEMPT_PATHS=" + named, "--sennard.edit-gate.exempt-paths=" + named)) {
            assertNotNull(writes(configured, 1, slashes(named)), "a path named by a setting is not an exception");
            // The signed log is the class's one (the business database), while the public keys are each server's own settings database (AGS3-D-0254): the second server is the same
            // installation, so it is given the keys of the first one's registry, and the class's key is the page's key here too (the second server reads the same signed list).
            configured.adoptPasskeysOf(server);
            declare(configured, key, signed.toString());
            assertNull(writes(configured, 5, slashes(signed)), "the signed list alone is read");
            assertNotNull(writes(configured, 1, slashes(named)), "and the setting still adds nothing next to it");
        }
    }

    @Test
    void noToolOfTheServerChangesTheList() throws Exception {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + server.server.port).endpoint("/mcp");
        transport.requestBuilder(HttpRequest.newBuilder().header("Sennard-Host-Label", "codex"));
        List<String> names = new ArrayList<>();
        try (McpSyncClient client = McpClient.sync(transport.build()).requestTimeout(Duration.ofSeconds(60)).build()) {
            client.initialize();
            client.listTools().tools().forEach(tool -> names.add(tool.name().toLowerCase(Locale.ROOT)));
        }
        assertFalse(names.isEmpty());
        for (String name : names) {
            assertFalse(name.contains("exempt") || name.contains("edit_gate") || name.contains("setting"), name);
        }
    }

    @Test
    void anUnsignedChangeOfTheListIsRefusedAndTheListStaysAsItWas() throws Exception {
        Path exempt = directory("kept");
        declare(server, key, exempt.toString());
        ObjectNode change = MAPPER.createObjectNode().put("op", "EDIT_EXEMPT_SET");
        change.putArray("paths").add(slashes(base));
        JsonNode prepared = server.prepare(change, false);
        RawHttp.Response committed = server.commit(prepared.path("intent").asString(), MAPPER.createObjectNode(), UUID.randomUUID().toString(), null, null);
        assertTrue(committed.status() >= 400, committed.text());
        assertNull(writes(server, 4, slashes(exempt)), "the list the user signed still holds");
        assertNotNull(writes(server, 1, slashes(directory("elsewhere"))), "and the refused change widened nothing");
    }
}
