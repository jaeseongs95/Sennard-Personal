// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * With the heap ledger on, the tasks of the build that start a JVM reserve their heap: Test, JavaExec and Exec (SennardBuildLogicPlugin of java/build-logic). A process that a build script starts
 * in any other way is outside that, so there is none. That the ledger's tasks run without a deadlock is {@code HeapLedgerBuildTest}'s: it runs a build.
 *
 * <pre>
 * Case                         Input                                           Expected
 * no other process start       every build script of java/                     a process is started only by a task of type Test, JavaExec or Exec (no project.exec, no ExecOperations)
 * </pre>
 */
class HeapLedgerCoversJvmTasksTest {
    private static final Repo REPO = new Repo(Path.of(System.getProperty("sennard.repo.root")));

    @Test
    void noBuildScriptStartsAProcessOutsideTheTaskTypesTheLedgerCovers() {
        Pattern start = Pattern.compile("ProcessBuilder|ExecOperations|\\bproject\\.exec\\b|\\bexec\\s*\\{|\\bjavaexec\\s*\\{", Pattern.CASE_INSENSITIVE);
        List<String> found = new ArrayList<>();
        for (String path : REPO.trackedFiles()) {
            if (path.startsWith("java/") && path.endsWith(".gradle.kts") && !path.contains("/build/")) {
                String code = REPO.text(path).lines().filter(line -> !line.stripLeading().startsWith("//")).collect(java.util.stream.Collectors.joining("\n"));
                if (start.matcher(code).find()) {
                    found.add(path);
                }
            }
        }
        assertEquals(List.of(), found, "a build script starts a process that the heap ledger does not cover");
    }
}
