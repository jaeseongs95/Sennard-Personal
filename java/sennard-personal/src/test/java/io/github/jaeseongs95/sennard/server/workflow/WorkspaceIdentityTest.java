// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.server.workflow.WorkspaceIdentity.Identity;
import io.github.jaeseongs95.sennard.server.workflow.WorkspaceIdentity.Surface;
import tools.jackson.databind.json.JsonMapper;

/**
 * The cases of the file-system identity of a root's write surface. Every location is a {@link Path}, so the same cases hold on any file system: how two
 * locations compare is the file system's own rule, not a string this code folds. No case here depends on a version control system: the identity is the
 * physical place and nothing else.
 */
class WorkspaceIdentityTest {
    private static final JsonMapper MAPPER = JsonMapper.shared();

    @TempDir
    Path tmp;

    private Path real(Path path) throws IOException {
        return path.toRealPath();
    }

    @Test
    void aGlobEntryIsResolvedByItsLiteralPrefixAndALeadingGlobByTheWorkspace() throws IOException {
        Path workspace = Files.createDirectories(tmp.resolve("ws"));
        Files.createDirectories(workspace.resolve("src"));
        Identity identity = WorkspaceIdentity.resolveRootIdentity(workspace.toString(), List.of("src/**/*.java", "docs/readme.md", "*.txt"), false);
        assertEquals(real(workspace), identity.workspacePhysical());
        Surface glob = identity.surfaces().get(0);
        assertEquals(real(workspace).resolve("src"), glob.physical());
        assertEquals("glob-prefix", glob.conservative());
        Surface plain = identity.surfaces().get(1);
        assertEquals(real(workspace).resolve("docs").resolve("readme.md"), plain.physical());
        assertNull(plain.conservative());
        Surface leading = identity.surfaces().get(2);
        assertEquals(real(workspace), leading.physical(), "a leading glob stands for the whole workspace, whether or not a repository is above it");
        assertEquals("leading-glob", leading.conservative());
    }

    /** D-0154: where a glob entry's literal prefix ends does not depend on the operating system: a backslash ends a segment like a slash, so a leading glob is told from a prefixed one alike everywhere. */
    @Test
    void aBackslashEndsTheLiteralPrefixOfAGlobEntryOnEveryOperatingSystem() throws IOException {
        Path workspace = Files.createDirectories(tmp.resolve("ws-backslash"));
        Identity identity = WorkspaceIdentity.resolveRootIdentity(workspace.toString(), List.of("src\\**\\*.java", "src/**/*.java", "*.txt"), true);
        assertEquals("glob-prefix", identity.surfaces().get(0).conservative());
        assertEquals("glob-prefix", identity.surfaces().get(1).conservative());
        assertEquals("leading-glob", identity.surfaces().get(2).conservative());
    }

    /** The place of a path inside a folder that holds a {@code .git} entry is the same as anywhere else: the entry is not read. */
    @Test
    void aGitEntryIsNotReadAndEvenAPointerThatPointsNowhereChangesNothing() throws IOException {
        Path broken = Files.createDirectories(tmp.resolve("broken"));
        Files.writeString(broken.resolve(".git"), "not a pointer\n");
        Identity identity = WorkspaceIdentity.resolveRootIdentity(broken.toString(), List.of("src/a.txt"), false);
        assertEquals(real(broken), identity.workspacePhysical());
        assertEquals(real(broken).resolve("src").resolve("a.txt"), identity.surfaces().get(0).physical());
        assertEquals(real(broken).resolve("a.txt"), WorkspaceIdentity.identify(broken.resolve("a.txt").toString()));
    }

    @Test
    void aPathThatDoesNotExistYetIsKeyedByItsNearestExistingAncestor() throws IOException {
        Path folder = Files.createDirectories(tmp.resolve("folder"));
        assertEquals(real(folder).resolve("new").resolve("deeper").resolve("file.txt"), WorkspaceIdentity.identify(folder.resolve("new/deeper/file.txt").toString()));
    }

    @Test
    void containmentFollowsPathComponentsNotTextPrefixes() {
        Path parent = tmp.resolve("repo");
        assertTrue(WorkspaceIdentity.pathWithin(parent.resolve("a").resolve("b"), parent));
        assertTrue(WorkspaceIdentity.pathWithin(parent, parent));
        assertFalse(WorkspaceIdentity.pathWithin(tmp.resolve("repo2"), parent), "repo2 is not below repo");
    }

    @Test
    void aUriOrAControlCharacterIsNotAScopeEntry() {
        for (String entry : List.of("https://example.com/x", "file://host/share", "src/\u0007bell")) {
            WorkflowFailure refused = assertThrows(WorkflowFailure.class, () -> WorkspaceIdentity.assertSupportedScopeEntry(entry), entry);
            assertEquals(WorkflowCode.INVALID_INPUT, refused.code());
        }
        WorkspaceIdentity.assertSupportedScopeEntry("C:/work/src");
        WorkspaceIdentity.assertSupportedScopeEntry("src/**/*.java");
    }

    @Test
    void aStoredIdentityReadsBackAsItWasWritten() throws IOException {
        Path root = real(Files.createDirectories(tmp.resolve("r")));
        Identity identity = new Identity("host-1", root, List.of(new Surface("sub/*.java", root.resolve("sub"), "glob-prefix"), new Surface("x", root.resolve("x"), null)));
        String json = ConvergenceLogic.identityJson(MAPPER, identity);
        ConvergenceLogic.Stored stored = ConvergenceLogic.storedIdentity(MAPPER, json, "digest");
        assertEquals(identity, stored.identity());
        assertEquals("digest", stored.surfaceDigest());
        assertTrue(MAPPER.readTree(json).has("host"));
        assertFalse(MAPPER.readTree(json).has("git"), "no repository is recorded");
        String hostless = ConvergenceLogic.identityJson(MAPPER, identity.withHost(null));
        assertFalse(MAPPER.readTree(hostless).has("host"), "an identity without a host does not write one");
        assertNull(ConvergenceLogic.storedIdentity(MAPPER, hostless, "d").identity().host());
    }
}
