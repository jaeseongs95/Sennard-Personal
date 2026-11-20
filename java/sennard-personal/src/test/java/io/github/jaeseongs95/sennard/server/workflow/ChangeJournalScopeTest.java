// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a run's files are, worked out from its writeTargets and its change plan (no file is read): the write roots, which a glob or a nested target does not widen or
 * repeat, and a plan file outside every root, which is the caller's input error.
 */
class ChangeJournalScopeTest {
    private final ChangeJournalScope scopes = new ChangeJournalScope();

    @TempDir
    Path workspace;

    @Test
    void aPlainTargetIsItsOwnRootAndADotIsTheWholeWorkspace() {
        assertEquals(List.of(workspace.resolve("src")), scopes.scope(workspace.toString(), List.of("src")).roots());
        assertEquals(List.of(workspace), scopes.scope(workspace.toString(), List.of(".")).roots());
        assertEquals(List.of(workspace.resolve("a.txt")), scopes.scope(workspace.toString(), List.of("./a.txt")).roots(), "a file may be a write target");
    }

    @Test
    void aGlobKeepsOnlyTheDirectoriesInFrontOfItsFirstGlobElement() {
        assertEquals(List.of(workspace.resolve("src").resolve("main")), scopes.scope(workspace.toString(), List.of("src/main/**/*.java")).roots());
        assertEquals(List.of(workspace), scopes.scope(workspace.toString(), List.of("**/*.java")).roots(), "a target that starts with a glob is the whole workspace");
    }

    @Test
    void aRootInsideAnotherIsNotKeptAndASameRootIsKeptOnce() {
        ChangeJournalScope.Scope scope = scopes.scope(workspace.toString(), List.of("src/a", "src", "docs", "docs"));
        assertEquals(List.of(workspace.resolve("src"), workspace.resolve("docs")), scope.roots());
    }

    @Test
    void aWorkspaceThatIsNotAbsoluteIsRefused() {
        WorkflowFailure refused = assertThrows(WorkflowFailure.class, () -> scopes.scope("relative/place", List.of("src")));
        assertEquals(WorkflowCode.INVALID_INPUT, refused.code());
    }

    @Test
    void aPlanFileInsideARootIsResolvedAgainstTheWorkspace() {
        ChangeJournalScope.Scope scope = scopes.scope(workspace.toString(), List.of("src"));
        Set<Path> plan = scopes.plan(scope, List.of("src/a.ts", "src/../src/b.ts", workspace.resolve("src").resolve("c.ts").toString()), "changePlan");
        assertEquals(Set.of(workspace.resolve("src/a.ts"), workspace.resolve("src/b.ts"), workspace.resolve("src/c.ts")), plan);
    }

    @Test
    void aPlanFileOutsideEveryRootIsTheCallersInputErrorAndNamesItsField() {
        ChangeJournalScope.Scope scope = scopes.scope(workspace.toString(), List.of("src"));
        for (String outside : List.of("other/a.ts", "src/../a.ts", "../escape.ts")) {
            WorkflowFailure refused = assertThrows(WorkflowFailure.class, () -> scopes.plan(scope, List.of("src/ok.ts", outside), "files"), outside);
            assertEquals(WorkflowCode.INVALID_INPUT, refused.code(), outside);
            assertEquals("files", refused.details().get("field"), outside);
            assertEquals("PLAN_OUTSIDE_WRITE_TARGETS", refused.details().get("reason"), outside);
            assertEquals(outside, refused.details().get("path"), outside);
        }
    }
}
