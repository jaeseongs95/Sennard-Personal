// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AGS3-D-0196 (review R1): a Codex shell call that names {@code cmd.exe} is read with Windows path rules, so the files {@code cmd.exe} really writes
 * with backslash paths are the files the edit gate counts. The commands are run by the real {@code cmd.exe}: a Windows shell is the thing under test,
 * and Java has no other way to run its command lines.
 */
@EnabledOnOs(OS.WINDOWS)
@Tag("business-db")
class CmdShellEditPathServerTest {
    private static RunningServer server;
    private static Path repo;

    @TempDir
    Path outside;

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources()); // its state directory is its own and is deleted when it closes (D-0194)
        // Inside this checkout (version control): the files the commands write are product files.
        repo = Files.createDirectories(Path.of("build", "cmd-path-" + UUID.randomUUID()).toAbsolutePath());
    }

    @AfterAll
    static void stop() throws IOException {
        server.close();
        try (Stream<Path> tree = Files.walk(repo)) {
            tree.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private static String session() throws Exception {
        String id = SessionServerSupport.uniqueSession();
        assertEquals(200, DomainServerSupport.hook(server.port, "windows", "codex", "SessionStart", null, id, body -> { }).status());
        return id;
    }

    /** The refusal text of the call, or null when it may run. */
    private static String refusal(String id, Path cwd, ObjectNode input) throws Exception {
        var response = DomainServerSupport.hook(server.port, "windows", "codex", "PreToolUse", "continuity", id, body -> {
            body.put("cwd", cwd.toString()).put("tool_use_id", UUID.randomUUID().toString());
            DomainServerSupport.tool("exec_command", input).accept(body);
        });
        assertEquals(200, response.status());
        JsonNode specific = DomainServerSupport.MAPPER.readTree(response.body()).path("hookSpecificOutput");
        return "deny".equals(specific.path("permissionDecision").asString()) ? specific.path("permissionDecisionReason").asString() : null;
    }

    /** A session of the request with a change workflow over a folder of the checkout that is its own (two workflows do not overlap), and that folder. */
    private static Path served(String id) throws Exception {
        Path folder = Files.createDirectories(repo.resolve("served-" + UUID.randomUUID()));
        ChangeWorkflows.open(server, id, folder, List.of("."));
        return folder;
    }

    /**
     * The gate judges the file the real cmd.exe writes: with no workflow the call is refused at the first file and the refusal names that file (not a write of an unknown file); in a change
     * workflow of the folder that holds the file the same command runs, and the same command writing a file of the checkout outside that folder is refused as outside its change plan.
     */
    private void assertTheGateJudgesTheFileCmdWrites(String label, java.util.function.Function<Path, String> command) throws Exception {
        String bare = session();
        String id = session();
        Path folder = served(id);
        for (int i = 0; i < 3; i++) {
            Path file = repo.resolve(label + "-" + i + ".txt");
            String line = command.apply(file);
            runCmd(line);
            assertTrue(Files.readString(file).contains("review"), "cmd.exe wrote the file the gate has to judge");
            ObjectNode input = DomainServerSupport.MAPPER.createObjectNode().put("cmd", line).put("shell", "cmd.exe");
            String refused = refusal(bare, outside, input);
            assertNotNull(refused, "no workflow: " + label + " " + i);
            assertTrue(refused.contains(file.getFileName().toString()) && !refused.contains("path unknown"), "the refusal names the file cmd.exe wrote: " + refused);
            String outsideThePlan = refusal(id, outside, input);
            assertNotNull(outsideThePlan, "the file is in the checkout, outside the folder of the workflow: " + label + " " + i);
            assertTrue(outsideThePlan.contains("change plan"), outsideThePlan);
            Path inside = folder.resolve(label + "-" + i + ".txt");
            String insideLine = command.apply(inside);
            runCmd(insideLine);
            assertNull(refusal(id, outside, DomainServerSupport.MAPPER.createObjectNode().put("cmd", insideLine).put("shell", "cmd.exe")), "inside the workflow's folder: " + label + " " + i);
        }
    }

    @Test
    void cmdBackslashAbsoluteWritesMustBeJudgedAsTheFilesCmdActuallyWrites() throws Exception {
        assertTheGateJudgesTheFileCmdWrites("cmd", file -> "echo review>" + file);
    }

    /** AGS3-D-0196: {@code copy} and {@code del} are built into cmd.exe, so the real cmd.exe writes the files the gate has to judge. */
    @Test
    void cmdBuiltinCopyAndDeleteMustBeJudgedAsTheFilesCmdActuallyChanges() throws Exception {
        Path source = Files.writeString(outside.resolve("source.txt"), "review");
        assertTheGateJudgesTheFileCmdWrites("copy", file -> "copy " + source + " " + file);
        // del: the files exist (made by the real cmd.exe copies above are removed by the real cmd.exe here).
        String bare = session();
        String id = session();
        Path folder = served(id);
        for (int i = 0; i < 3; i++) {
            Path file = repo.resolve("del-" + i + ".txt");
            Files.writeString(file, "review");
            Path inside = Files.writeString(folder.resolve("del-" + i + ".txt"), "review");
            String delete = "del " + file;
            ObjectNode input = DomainServerSupport.MAPPER.createObjectNode().put("cmd", delete).put("shell", "cmd.exe");
            String refused = refusal(bare, outside, input);
            assertNotNull(refused, "no workflow: cmd del " + i);
            assertTrue(refused.contains(file.getFileName().toString()) && !refused.contains("path unknown"), "the refusal names the file cmd.exe deletes: " + refused);
            assertNotNull(refusal(id, outside, input), "outside the folder of the workflow: cmd del " + i);
            runCmd(delete);
            assertTrue(Files.notExists(file), "cmd.exe deleted the file the gate judges");
            String deleteInside = "del " + inside;
            assertNull(refusal(id, outside, DomainServerSupport.MAPPER.createObjectNode().put("cmd", deleteInside).put("shell", "cmd.exe")), "inside the workflow's folder: cmd del " + i);
            runCmd(deleteInside);
            assertTrue(Files.notExists(inside));
        }
    }

    /** Runs the command line in the real cmd.exe and fails with its own words when it does not succeed. */
    private void runCmd(String command) throws Exception {
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", command).directory(outside.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
}
