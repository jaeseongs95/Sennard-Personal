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
 * D-0194: a test run leaves nothing in the temporary folder (the folder the build isolates for the test JVM grew by every run). These server tests made a folder or a file there
 * that nothing deleted. They now get the server's own state directory (deleted when the server closes) or a JUnit {@code @TempDir}; a call that makes a folder or a file in the
 * default temporary folder, with only a name prefix, is the form that was left behind.
 *
 * <pre>
 * Case                         Input                                                       Expected
 * default temporary folder     the five server tests named in D-0194                       no Files.createTempDirectory("..") or Files.createTempFile("..", ..)
 * </pre>
 */
class ServerTestTempFoldersTest {
    private static final Repo REPO = new Repo(Path.of(System.getProperty("sennard.repo.root")));
    private static final String SERVERIT = "java/sennard-personal/src/test/java/io/github/jaeseongs95/sennard/serverit/";
    private static final List<String> TESTS = List.of("CmdShellEditPathServerTest", "LinkedProductEditServerTest", "ScriptRunEditGateServerTest", "NoVersionControlEditGateServerTest",
            "ConvergenceLimitsServerTest");
    private static final Pattern DEFAULT_TEMPORARY_FOLDER = Pattern.compile("createTemp(?:Directory|File)\\(\\s*\"");

    @Test
    void theFiveServerTestsMakeNothingInTheDefaultTemporaryFolder() {
        List<String> found = new ArrayList<>();
        for (String test : TESTS) {
            String text = REPO.text(SERVERIT + test + ".java");
            var matcher = DEFAULT_TEMPORARY_FOLDER.matcher(text);
            while (matcher.find()) {
                found.add(test + " line " + (text.substring(0, matcher.start()).split("\n", -1).length));
            }
        }
        assertEquals(List.of(), found, "a folder or file in the default temporary folder that nothing deletes");
    }
}
