// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The tests of the build logic (the heap ledger's HeapLedgerTest, which D-0185 and D-0188 rest on) run in the root build: the build logic is an included build that provides a plugin, and
 * the tests are compiled and run by this module (sennard-repo-tools), so that they are a Test task of the root build and take part in its limit on the tasks that reserve heap (D-0270). A Test
 * task of the included build would wait for the heap reservation of a Test of the root build outside that limit. buildSrc is not used: its tests run on no task of a build.
 *
 * <pre>
 * Case                         Input                                      Expected
 * no buildSrc                  every tracked path                         none under java/buildSrc/
 * the tests run in the root    the class path of this test                HeapLedgerTest of java/build-logic is on it, so this module's test task runs it
 * </pre>
 */
class BuildLogicWiringTest {
    private static final Repo REPO = new Repo(Path.of(System.getProperty("sennard.repo.root")));

    @Test
    void noTrackedFileIsInBuildSrc() {
        List<String> inBuildSrc = REPO.trackedFiles().stream().filter(path -> path.startsWith("java/buildSrc/")).toList();
        assertEquals(List.of(), inBuildSrc, "buildSrc tests run on no task of the build: the build logic is the included build java/build-logic");
    }

    @Test
    void theTestsOfTheBuildLogicAreOnTheClassPathOfThisModulesTestTask() {
        assertDoesNotThrow(() -> Class.forName("io.github.jaeseongs95.sennard.build.HeapLedgerTest", false, BuildLogicWiringTest.class.getClassLoader()),
                "the tests of java/build-logic are not compiled into this module's test source set, so no task of the root build runs them");
    }
}
