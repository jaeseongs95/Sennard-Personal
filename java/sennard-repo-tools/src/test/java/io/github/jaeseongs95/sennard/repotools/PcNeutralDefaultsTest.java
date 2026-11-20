// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The repository's build and test defaults name no computer: no database, secrets folder, worker count, processor count, heap or memory limit of one machine. A computer that
 * wants any of them states it itself (its own ~/.gradle/gradle.properties or the command line), and the heap ledger is off until it switches it on.
 *
 * <pre>
 * Case                                   Input                                                    Expected
 * database and secrets of a computer     every tracked text under java/, .github/, CONTRIBUTING*  no 127.0.0.1:55432, ags-test-secrets, postgresql-ags-dbms
 * account of the Linux test server       every tracked text under java/, .github/, CONTRIBUTING*  no mcpops
 * recorded Linux fixtures                the hook and MCP-metadata fixtures of Linux              no test server account, and no folder of its test runs
 * worker, daemon and JVM settings        java/gradle.properties                                  none of org.gradle.workers.max, .jvmargs, .daemon.idletimeout
 * processor count                        the root script                                          no fixed count; it is stated by -Psennard.test.cpus (the ledger: HeapLedgerBuildTest)
 * database tests                         the root script                                          test leaves the tag out, businessDbTest runs only it and requires the connection
 * </pre>
 */
class PcNeutralDefaultsTest {
    private static final Repo REPO = new Repo(Path.of(System.getProperty("sennard.repo.root")));
    private static final Pattern COMPUTER_VALUE = Pattern.compile("127\\.0\\.0\\.1:55432|ags-test-secrets|postgresql-ags-dbms");
    /** The account of the Linux test server, and the folders of its test runs (the Windows fixtures keep the paths their tests assert). */
    private static final Pattern TEST_SERVER_ACCOUNT = Pattern.compile("mcpops");
    private static final Pattern TEST_SERVER_VALUE = Pattern.compile("mcpops|/ags-tests/|-ags-tests-|hp-v600-probe");
    private static final String THIS_TEST ="java/sennard-repo-tools/src/test/java/io/github/jaeseongs95/sennard/repotools/PcNeutralDefaultsTest.java";

    private static boolean scanned(String path) {
        boolean inScope = path.startsWith("java/") || path.startsWith(".github/") || path.startsWith("CONTRIBUTING");
        boolean text = path.endsWith(".java") || path.endsWith(".kts") || path.endsWith(".properties") || path.endsWith(".yml") || path.endsWith(".md") || path.endsWith(".json")
                || path.endsWith(".yaml") || path.endsWith(".sql");
        return inScope && text && !path.equals(THIS_TEST) && !path.contains("/build/");
    }

    @Test
    void noTrackedFileNamesTheDatabaseOrTheSecretsFolderOfAComputer() {
        List<String> found = new ArrayList<>();
        for (String path : REPO.trackedFiles()) {
            if (scanned(path) && COMPUTER_VALUE.matcher(new String(REPO.bytes(path), StandardCharsets.UTF_8)).find()) {
                found.add(path);
            }
        }
        assertEquals(List.of(), found, "a tracked file names a database or a secrets folder of one computer");
    }

    /** D-0201: the recorded Linux hook bodies and MCP metadata held the account and folders of the Linux test server. They are fixed data, and read as JSON, not as source. */
    @Test
    void theRecordedLinuxFixturesNameNoAccountOrFolderOfTheTestServer() {
        List<String> found = new ArrayList<>();
        int checked = 0;
        for (String path : REPO.trackedFiles()) {
            if (path.startsWith("java/") && path.contains("/src/test/resources/") && (path.contains("/hook-fixtures/linux/") || path.contains("/mcp-meta-fixtures/linux/"))) {
                checked++;
                if (TEST_SERVER_VALUE.matcher(new String(REPO.bytes(path), StandardCharsets.UTF_8)).find()) {
                    found.add(path);
                }
            }
        }
        assertTrue(checked > 0, "the recorded Linux fixtures are found");
        assertEquals(List.of(), found, "a recorded fixture names the account or a folder of the Linux test server");
    }

    @Test
    void noTrackedFileOfTheProductNamesTheAccountOfTheTestServer() {
        List<String> found = new ArrayList<>();
        for (String path : REPO.trackedFiles()) {
            if (scanned(path) && TEST_SERVER_ACCOUNT.matcher(new String(REPO.bytes(path), StandardCharsets.UTF_8)).find()) {
                found.add(path);
            }
        }
        assertEquals(List.of(), found, "a tracked file names the account of the test server");
    }

    @Test
    void gradlePropertiesStatesNoWorkerDaemonOrJvmSetting() {
        String properties = REPO.text("java/gradle.properties");
        for (String key : List.of("org.gradle.workers.max", "org.gradle.jvmargs", "org.gradle.daemon.idletimeout")) {
            assertFalse(Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=").matcher(properties).find(), key + " is a computer's setting, not the repository's");
        }
    }

    @Test
    void theRootScriptStatesNoFixedProcessorCount() {
        String root = REPO.text("java/build.gradle.kts");
        assertFalse(root.contains("getOrElse(\"1\")"), "no default processor count");
        assertTrue(root.contains("sennard.test.cpus"), "the count is stated by -Psennard.test.cpus");
        // That the heap ledger is off unless sennard.heap.ledger is true, and that its limit has neither a default nor an upper bound, are judged by HeapLedgerBuildTest, which runs builds.
    }

    @Test
    void theDatabaseTestsAreTheirOwnTaskThatTheDefaultTestLeavesOutAndThatRequiresTheConnection() {
        String root = REPO.text("java/build.gradle.kts");
        assertTrue(root.contains("excludeTags(businessDbTag)"), "test leaves the tag out");
        assertTrue(root.contains("includeTags(businessDbTag)"), "businessDbTest runs only the tag");
        assertTrue(root.contains("doFirst(\"requireBusinessDb\")") && root.contains("throw GradleException("), "a request without the connection fails before the tests start");
        assertFalse(root.contains("sennard.test.businessDb\")"), "no switch that turns the database tests off and leaves some of them on");
    }
}
