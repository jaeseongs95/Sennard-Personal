// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.server.workflow.WorkspaceIdentity.Identity;
import io.github.jaeseongs95.sennard.service.store.RootState;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which active root blocks a new one: only a root whose physical write location overlaps the new root's, on the same host. No version control system is
 * consulted, so a working copy, a clone or a worktree decides nothing; a link to the same physical place does.
 */
class ConvergenceLogicTest {
    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static final String HOST = "host-1";
    /** The rules are tested against the real file system of a temporary folder, so the resolver is the entry's own. */
    private static final ConvergenceLogic.IdentityResolver RESOLVER = WorkspaceIdentity::resolveRootIdentity;

    private final DocumentDigests digests = new DocumentDigests(MAPPER);

    @TempDir
    Path tmp;

    private ConvergenceRoot root(String rootId, String parentRootId, RootState state, Path workspace, String... targets) {
        TaskEnvelope base = RootSamples.task("task-" + rootId, false);
        TaskEnvelope task = new TaskEnvelope(base.schemaVersion(), base.taskId(), base.objective(), new TaskEnvelope.Scope(List.of(targets), List.of()), base.acceptanceCriteria(), base.riskLevel(),
                List.of(), null, base.requiredCapabilities(), base.constraints(), base.authorization(), base.decision(), base.orchestration());
        ConvergenceFrame template = RootSamples.frame(workspace.toString());
        ConvergenceFrame frame = new ConvergenceFrame(template.schemaVersion(), template.workspace(), template.controlArtifacts(), List.of(), template.operationalSettings());
        return new ConvergenceRoot("1.0.0", rootId, parentRootId, 0, state, 1, task, frame, "d", "d", "d", "d", "d", "d", List.of(), RootSamples.CREATED, RootSamples.CREATED, null, null);
    }

    private ConvergenceLogic.Plan plan(ConvergenceRoot candidate, ConvergenceRoot... actives) {
        List<ConvergenceLogic.Identified> identified = new ArrayList<>();
        for (ConvergenceRoot active : actives) {
            identified.add(ConvergenceLogic.activeRootIdentity(digests, active, null, RESOLVER));
        }
        return ConvergenceLogic.planRootInsertion(digests, candidate, identified, HOST, RESOLVER);
    }

    private Path directory(String relative) throws IOException {
        return Files.createDirectories(tmp.resolve(relative));
    }

    @Test
    void overlappingPhysicalPathsConflictInEitherDirection() throws IOException {
        Path ws = directory("ws");
        ConvergenceRoot outer = root("outer", null, RootState.OPEN, ws, "src");
        ConvergenceRoot inner = root("inner", null, RootState.OPEN, ws, "src/inner/File.java");

        ConvergenceLogic.Conflict innerBlocked = plan(inner, outer).conflict();
        assertNotNull(innerBlocked);
        assertEquals("outer", innerBlocked.root().rootId());
        assertEquals("src/inner/File.java", innerBlocked.requested().entry());
        assertEquals("src", innerBlocked.existing().entry());
        assertEquals(ws.toRealPath().resolve("src").toString(), ConvergenceLogic.conflictDetails(innerBlocked).get("existingPath"));

        ConvergenceLogic.Conflict outerBlocked = plan(outer, inner).conflict();
        assertNotNull(outerBlocked);
        assertEquals("inner", outerBlocked.root().rootId());
        assertEquals(ws.toRealPath().resolve("src").resolve("inner").resolve("File.java").toString(), ConvergenceLogic.conflictDetails(outerBlocked).get("existingPath"));
    }

    @Test
    void theConflictDetailsNameNoLineageKind() throws IOException {
        Path ws = directory("ws");
        Map<String, Object> details = ConvergenceLogic.conflictDetails(plan(root("b", null, RootState.OPEN, ws, "src"), root("a", null, RootState.NEEDS_USER, ws, "src")).conflict());
        assertEquals("a", details.get("rootId"));
        assertFalse(details.containsKey("conflictKind"));
        assertFalse(details.containsKey("reason"));
    }

    @Test
    void pathsThatDoNotOverlapDoNotConflictAndANameIsComparedWholeNotAsText() throws IOException {
        Path ws = directory("ws");
        assertNull(plan(root("b", null, RootState.OPEN, ws, "beta"), root("a", null, RootState.OPEN, ws, "alpha")).conflict());
        assertNull(plan(root("srcx", null, RootState.OPEN, ws, "srcx"), root("src", null, RootState.OPEN, ws, "src")).conflict(), "srcx is not below src");
    }

    @Test
    void aGatedRootBlocksOnlyThroughAnOverlapToo() throws IOException {
        Path ws = directory("ws");
        ConvergenceRoot gated = root("gated", null, RootState.NEEDS_REVIEW, ws, "alpha");
        assertNull(plan(root("other", null, RootState.OPEN, ws, "beta"), gated).conflict());
        assertNotNull(plan(root("same", null, RootState.OPEN, ws, "alpha/x"), gated).conflict());
    }

    /** Two working copies of one repository (a main checkout and a linked worktree, both with their Git entries) are two places: the same relative path in each does not conflict. */
    @Test
    void differentWorkingCopiesOfOneRepositoryDoNotConflictWhenTheirPathsDoNotOverlap() throws IOException {
        Path main = directory("main");
        Path commonDir = directory("main/.git");
        Path registration = directory("main/.git/worktrees/wt1");
        Path worktree = directory("wt1");
        Files.writeString(registration.resolve("gitdir"), worktree.resolve(".git") + "\n");
        Files.writeString(registration.resolve("commondir"), "../..\n");
        Files.writeString(worktree.resolve(".git"), "gitdir: " + registration + "\n");
        assertTrue(Files.isDirectory(commonDir));

        for (RootState state : List.of(RootState.OPEN, RootState.NEEDS_REVIEW, RootState.NEEDS_USER)) {
            ConvergenceRoot inMain = root("main-" + state, null, state, main, "src/App.java");
            assertNull(plan(root("wt-" + state, null, RootState.OPEN, worktree, "src/App.java"), inMain).conflict(), "an active root in " + state + " blocks nothing in the other working copy");
        }
    }

    /** Two folders that are not working copies of anything behave the same: the verdict is the physical paths. */
    @Test
    void differentWorkspacesWithTheSameRelativePathDoNotConflict() throws IOException {
        ConvergenceRoot first = root("first", null, RootState.NEEDS_USER, directory("copy-1"), "src");
        assertNull(plan(root("second", null, RootState.OPEN, directory("copy-2"), "src"), first).conflict());
    }

    @Test
    void theSameWorkspaceSpelledTwoWaysIsOnePhysicalPlace() throws IOException {
        Path ws = directory("ws");
        directory("ws/lib");
        ConvergenceRoot first = root("first", null, RootState.OPEN, ws, "src");
        ConvergenceLogic.Conflict conflict = plan(root("second", null, RootState.OPEN, ws.resolve("lib").resolve(".."), "./src"), first).conflict();
        assertNotNull(conflict);
        assertEquals("first", conflict.root().rootId());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aSymbolicLinkToTheSamePhysicalPathConflicts() throws IOException {
        Path real = directory("ws/src");
        Path elsewhere = directory("elsewhere");
        Files.createSymbolicLink(elsewhere.resolve("alias"), real);
        assertLinkConflicts(directory("ws"), elsewhere);
    }

    /**
     * An NTFS junction is made by {@code mklink /J}, which Java has no call for (Files.createSymbolicLink needs a privilege an ordinary Windows account lacks and makes
     * another kind of link), so cmd.exe makes it in this test, as in the other link tests.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aJunctionToTheSamePhysicalPathConflicts() throws Exception {
        Path real = directory("ws/src");
        Path elsewhere = directory("elsewhere");
        Path junction = elsewhere.resolve("alias");
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", junction.toString(), real.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        try {
            assertLinkConflicts(directory("ws"), elsewhere);
        } finally {
            Files.deleteIfExists(junction); // the junction itself, before the temporary folder's clean-up walks through it
        }
    }

    private void assertLinkConflicts(Path ws, Path elsewhere) throws IOException {
        ConvergenceRoot first = root("first", null, RootState.OPEN, ws, "src");
        ConvergenceRoot throughLink = root("second", null, RootState.OPEN, elsewhere, "alias/File.java");
        ConvergenceLogic.Conflict conflict = plan(throughLink, first).conflict();
        assertNotNull(conflict, "a path reached through a link is the physical path it points at");
        assertEquals("first", conflict.root().rootId());
        assertEquals(ws.toRealPath().resolve("src").resolve("File.java"), conflict.requested().physical());
    }

    private String key(ConvergenceRoot root) {
        return plan(root).surfaceKey();
    }

    /** The unique key is of where a root writes, not of how the entries were written down: their order and repeats change nothing, a different place does. */
    @Test
    void theSurfaceKeyDoesNotDependOnTheOrderOrRepeatsOfTheEntries() throws IOException {
        Path ws = directory("ws");
        directory("ws/src");
        directory("ws/lib");
        directory("ws/docs");
        String key = key(root("first", null, RootState.OPEN, ws, "src", "lib"));
        assertEquals(key, key(root("reordered", null, RootState.OPEN, ws, "lib", "src")));
        assertEquals(key, key(root("repeated", null, RootState.OPEN, ws, "src", "lib", "src")));
        assertNotEquals(key, key(root("other", null, RootState.OPEN, ws, "src", "docs")));
        assertNotEquals(key, key(root("fewer", null, RootState.OPEN, ws, "src")));
    }

    /** An existing path is its real path, so a leading ./, doubled separators and a detour through .. spell the same place. */
    @Test
    void theSurfaceKeyIsOfThePhysicalPathWhateverTheSpelling() throws IOException {
        Path ws = directory("ws");
        directory("ws/src/inner");
        directory("ws/lib");
        String key = key(root("plain", null, RootState.OPEN, ws, "src/inner"));
        assertEquals(key, key(root("dot", null, RootState.OPEN, ws, "./src/inner")));
        assertEquals(key, key(root("separators", null, RootState.OPEN, ws, "src//inner")));
        assertEquals(key, key(root("detour", null, RootState.OPEN, ws, "lib/../src/inner")));
        assertEquals(key, key(root("absolute", null, RootState.OPEN, ws, ws.resolve("src").resolve("inner").toString())));
        assertEquals(key, key(root("workspaceSpelled", null, RootState.OPEN, ws.resolve("lib").resolve(".."), "src/inner")));
    }

    /** A path that does not exist yet is the real path of its nearest existing ancestor and the rest of its name. */
    @Test
    void theSurfaceKeyOfAPathNotCreatedYetIsItsAncestorsRealPathAndTheRest() throws IOException {
        Path ws = directory("ws");
        directory("ws/lib");
        String key = key(root("plain", null, RootState.OPEN, ws, "new/File.java"));
        assertEquals(key, key(root("spelled", null, RootState.OPEN, ws, "./lib/../new//File.java")));
        assertNotEquals(key, key(root("elsewhere", null, RootState.OPEN, ws, "new/Other.java")));
    }

    /**
     * Two spellings that differ in letter case are one place only on a file system that folds case; the test asks the file system (the same file, or not) and judges by that,
     * without naming an operating system.
     */
    @Test
    void theSurfaceKeyFollowsTheFileSystemOnLetterCase() throws IOException {
        Path ws = directory("ws");
        Path lower = directory("ws/src");
        Path upper = ws.resolve("SRC");
        boolean foldsCase = Files.exists(upper) && Files.isSameFile(lower, upper);
        String lowerKey = key(root("lower", null, RootState.OPEN, ws, "src"));
        String upperKey = key(root("upper", null, RootState.OPEN, ws, "SRC"));
        if (foldsCase) {
            assertEquals(lowerKey, upperKey, "this file system holds src and SRC to be one place");
        } else {
            assertNotEquals(lowerKey, upperKey, "this file system holds src and SRC to be two places");
        }
    }

    @Test
    void aRootOfAnotherHostBlocksNothingHere() throws IOException {
        Path ws = directory("ws");
        ConvergenceRoot active = root("active", null, RootState.OPEN, ws, "src");
        Identity derived = WorkspaceIdentity.resolveRootIdentity(ws.toString(), List.of("src"), false);
        String digest = ConvergenceLogic.surfaceDigest(digests, active);
        ConvergenceRoot candidate = root("candidate", null, RootState.OPEN, ws, "src");

        ConvergenceLogic.Identified elsewhere = ConvergenceLogic.activeRootIdentity(digests, active, new ConvergenceLogic.Stored(derived.withHost("host-2"), digest), RESOLVER);
        assertNull(ConvergenceLogic.planRootInsertion(digests, candidate, List.of(elsewhere), HOST, RESOLVER).conflict());
        ConvergenceLogic.Identified here = ConvergenceLogic.activeRootIdentity(digests, active, new ConvergenceLogic.Stored(derived.withHost(HOST), digest), RESOLVER);
        assertNotNull(ConvergenceLogic.planRootInsertion(digests, candidate, List.of(here), HOST, RESOLVER).conflict());
        ConvergenceLogic.Identified hostless = ConvergenceLogic.activeRootIdentity(digests, active, new ConvergenceLogic.Stored(derived.withHost(null), digest), RESOLVER);
        assertNotNull(ConvergenceLogic.planRootInsertion(digests, candidate, List.of(hostless), HOST, RESOLVER).conflict(), "a root with no recorded host is taken as this host's");
    }

    /** The check of overlapping roots looks at one host; the key is of the host too, so the same place on another host is another key and the unique column refuses nothing the check allows. */
    @Test
    void theSurfaceKeyIsOfTheHostToo() throws IOException {
        Path ws = directory("ws");
        directory("ws/src");
        ConvergenceRoot candidate = root("candidate", null, RootState.OPEN, ws, "src");
        Identity place = WorkspaceIdentity.resolveRootIdentity(ws.toString(), List.of("src"), false);
        String here = ConvergenceLogic.surfaceKey(digests, place.withHost(HOST));
        assertEquals(here, ConvergenceLogic.surfaceKey(digests, place.withHost(HOST)), "the same place on the same host is the same key");
        assertNotEquals(here, ConvergenceLogic.surfaceKey(digests, place.withHost("host-2")), "the same place on another host is another key");
        assertNotEquals(here, ConvergenceLogic.surfaceKey(digests, place.withHost(null)), "a root with no host holds a key of its own");
        assertEquals(ConvergenceLogic.surfaceKey(digests, place.withHost(null)), ConvergenceLogic.surfaceKey(digests, place.withHost(null)));
        assertEquals(here, plan(candidate).surfaceKey(), "the plan of an insertion on this host is the key of this host");
    }

    @Test
    void aStoredIdentityIsKeptForTheSameInputsAndDerivedAgainWithItsHostForOthers() throws IOException {
        Path ws = directory("ws");
        ConvergenceRoot active = root("active", null, RootState.OPEN, ws, "src");
        Identity derived = WorkspaceIdentity.resolveRootIdentity(ws.toString(), List.of("src"), false).withHost("host-2");

        ConvergenceLogic.Identified kept = ConvergenceLogic.activeRootIdentity(digests, active, new ConvergenceLogic.Stored(derived, ConvergenceLogic.surfaceDigest(digests, active)), RESOLVER);
        assertFalse(kept.fresh());
        assertSame(derived, kept.identity());

        ConvergenceLogic.Identified again = ConvergenceLogic.activeRootIdentity(digests, active, new ConvergenceLogic.Stored(derived, "digest of other inputs"), RESOLVER);
        assertTrue(again.fresh());
        assertEquals(derived, again.identity(), "derived from the same place again, and the host the root was stored with is kept");

        ConvergenceLogic.Identified first = ConvergenceLogic.activeRootIdentity(digests, active, null, RESOLVER);
        assertTrue(first.fresh());
        assertNull(first.identity().host());
    }

    @Test
    void aReplacementIsNeverBlockedByItsParentAndStillByEveryOtherRoot() throws IOException {
        Path ws = directory("ws");
        ConvergenceRoot parent = root("parent", null, RootState.NEEDS_USER, ws, "src");
        ConvergenceRoot replacement = root("replacement", "parent", RootState.OPEN, ws, "src", "lib");
        assertNull(plan(replacement, parent).conflict());

        ConvergenceRoot other = root("other", null, RootState.OPEN, ws, "lib");
        ConvergenceLogic.Conflict conflict = plan(replacement, parent, other).conflict();
        assertNotNull(conflict);
        assertEquals("other", conflict.root().rootId());
    }

    @Test
    void aReplacementMustStayInThePhysicalWorkspaceOfItsParent() throws IOException {
        ConvergenceRoot parent = root("parent", null, RootState.NEEDS_USER, directory("ws"), "src");
        ConvergenceRoot moved = root("moved", "parent", RootState.OPEN, directory("other"), "src");
        WorkflowFailure refused = assertThrows(WorkflowFailure.class, () -> plan(moved, parent));
        assertEquals(WorkflowCode.INVALID_INPUT, refused.code());
        assertEquals("parent", refused.details().get("parentRootId"));
        assertFalse(refused.details().containsKey("reason"));
    }

    @Test
    void onlyAGatedRootIsReplaced() throws IOException {
        Path ws = directory("ws");
        ConvergenceRoot open = root("open", null, RootState.OPEN, ws, "src");
        WorkflowFailure notGated = assertThrows(WorkflowFailure.class, () -> plan(root("r", "open", RootState.OPEN, ws, "src"), open));
        assertEquals(WorkflowCode.INVALID_TRANSITION, notGated.code());
        WorkflowFailure missing = assertThrows(WorkflowFailure.class, () -> plan(root("r", "gone", RootState.OPEN, ws, "src")));
        assertEquals(WorkflowCode.INVALID_TRANSITION, missing.code());
    }
}
