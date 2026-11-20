// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AGS3-D-0197 (review R3): a write that reaches a repository file through a link kept outside the repository is a write of that product file. The gate keys a
 * file by its real place, so whether the file is under version control is asked of that real place too, not of the spelling that went through the link.
 */
@Tag("business-db")
class LinkedProductEditServerTest {
    private static RunningServer server;
    private static Path repo;

    /**
     * The folder that keeps the link. The build isolates the test JVM's temporary folder inside this checkout, which is under version control, so the folder is made
     * beside the checkout, in its parent; where that parent is itself in a repository the fixture says so and fails.
     */
    private Path outside;

    @BeforeEach
    void outsideFolder() throws IOException {
        Path checkout = repo.toAbsolutePath();
        while (!Files.isDirectory(checkout.resolve("contracts")) || !Files.isDirectory(checkout.resolve("skills"))) {
            checkout = checkout.getParent();
        }
        outside = Files.createTempDirectory(checkout.getParent(), "sennard-link-outside-");
    }

    @AfterEach
    void removeOutsideFolder() throws IOException {
        // The link first: removing it removes the link, not what it points at.
        Files.deleteIfExists(outside.resolve("alias"));
        Files.deleteIfExists(outside);
    }

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources()); // its state directory is its own and is deleted when it closes (D-0194)
        // Inside this checkout (version control): the files behind the link are product files.
        repo = Files.createDirectories(Path.of("build", "linked-product-" + UUID.randomUUID()).toAbsolutePath());
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
        assertEquals(200, DomainServerSupport.hook(server.port, "windows", "claude", "SessionStart", null, id, body -> { }).status());
        return id;
    }

    private static boolean denied(String id, String command) throws Exception {
        var response = DomainServerSupport.hook(server.port, "windows", "claude", "PreToolUse", "continuity", id, body -> {
            body.put("cwd", repo.toString()).put("tool_use_id", UUID.randomUUID().toString());
            ObjectNode input = DomainServerSupport.MAPPER.createObjectNode().put("command", command);
            DomainServerSupport.tool("Bash", input).accept(body);
        });
        assertEquals(200, response.status());
        JsonNode result = DomainServerSupport.MAPPER.readTree(response.body());
        return "deny".equals(result.path("hookSpecificOutput").path("permissionDecision").asString());
    }

    private void assertNoVersionControlAbove(Path folder) {
        for (Path place = folder; place != null; place = place.getParent()) {
            assertFalse(Files.exists(place.resolve(".git")), "the link must be kept outside a checkout: " + place);
        }
    }

    /**
     * Writes through the link to real files of the checkout are writes of those files: with no workflow each is refused at the first call (a product file, though the link that spells it is
     * kept outside any repository); in a change workflow whose folder is the real folder, the same writes run, which they could not if the gate took the link's own place for the file.
     */
    private void writesThrough(Path link, Path target, String shellSpelling) throws Exception {
        String bare = session();
        String served = SessionServerSupport.uniqueSession();
        ChangeWorkflows.open(server, served, target, List.of("."));
        for (int i = 0; i < 3; i++) {
            Path file = Files.writeString(target.resolve("link-" + i + ".txt"), "review");
            Path alias = link.resolve(file.getFileName());
            assertTrue(Files.isSameFile(alias, file));
            String spelled = alias.toString().replace('\\', '/');
            String path = shellSpelling.equals("msys") ? "/" + Character.toLowerCase(spelled.charAt(0)) + spelled.substring(2) : spelled;
            assertTrue(denied(bare, "echo review > '" + path + "'"), "linked file " + i + " with no workflow is a product file");
            assertFalse(denied(served, "echo review > '" + path + "'"), "linked file " + i + " is a file of the workflow whose folder is the real one");
        }
    }

    /**
     * An NTFS junction is made by {@code mklink /J}, which Java has no call for (Files.createSymbolicLink needs a privilege an ordinary Windows account lacks and
     * makes another kind of link), so cmd.exe makes it.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void msysJunctionIntoRepositorySubdirectoryMustBeJudgedAsPhysicalProductFiles() throws Exception {
        Path target = Files.createDirectories(repo.resolve("linked-source"));
        assertNoVersionControlAbove(outside);
        Path junction = outside.resolve("alias");
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", junction.toString(), target.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        assertTrue(Files.isSameFile(junction, target));
        writesThrough(junction, target, "msys");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void symbolicLinkIntoRepositorySubdirectoryMustBeJudgedAsPhysicalProductFiles() throws Exception {
        Path target = Files.createDirectories(repo.resolve("linked-source"));
        assertNoVersionControlAbove(outside);
        Path link = Files.createSymbolicLink(outside.resolve("alias"), target);
        assertTrue(Files.isSameFile(link, target));
        writesThrough(link, target, "posix");
    }
}
