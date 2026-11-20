// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * What the build's Test configuration (java/build.gradle.kts) promises every test JVM: no variable of the host session that runs Gradle, and
 * a home, application-data and temporary folder under the module's own build folder, so that tests neither reach this session's MCP and relay
 * nor share files with a test run of another working copy, and the heap the build states. The two variables the build itself gives every test JVM for the places of the product
 * (SENNARD_INSTALL_ROOT, SENNARD_STATE_DIR) are not the host session's: they are told apart by their value, which names a folder of this test run. The cases hold on any machine; they prove the isolation when the shell that started
 * Gradle had such variables set.
 */
class TestIsolationTest {
    /** The prefixes the host agents put in their session environment. */
    private static final Pattern HOST_SESSION = Pattern.compile("^(SENNARD|CLAUDE|CODEX|GEMINI|ANTHROPIC|OPENAI).*", Pattern.CASE_INSENSITIVE);

    private static final Path MODULE = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    /** The two variables the build sets (java/build.gradle.kts, isolateFromHost) so that a child JVM of a test takes its places from the test run's folders. */
    private static final Set<String> GIVEN_BY_THE_BUILD = Set.of("SENNARD_INSTALL_ROOT", "SENNARD_STATE_DIR");
    private static final String TRAY_SWITCH = "SENNARD_TRAY_ENABLED";
    private static final Path ISOLATED = MODULE.resolve("build").resolve("isolated");

    @Test
    void noHostSessionVariableReachesTheTestJvm() {
        List<String> found = System.getenv().entrySet().stream()
                .filter(variable -> HOST_SESSION.matcher(variable.getKey()).matches())
                .filter(variable -> !givenByTheBuild(variable))
                .map(Map.Entry::getKey).sorted().toList();
        assertEquals(List.of(), found);
    }

    /** A variable the build gave this JVM: one of its two names, with a value that is a folder of this test run (a host session's variable of that name would name another place). */
    private static boolean givenByTheBuild(Map.Entry<String, String> variable) {
        if (TRAY_SWITCH.equalsIgnoreCase(variable.getKey())) {
            return "false".equals(variable.getValue()); // the build's value; a host session that wanted the icon would say true
        }
        return GIVEN_BY_THE_BUILD.stream().anyMatch(name -> name.equalsIgnoreCase(variable.getKey())) && Path.of(variable.getValue()).toAbsolutePath().normalize().startsWith(ISOLATED);
    }

    /** A test run uses no screen: its JVM is headless, and the servers and commands it starts inherit the variable that switches the product's tray icon off (java/build.gradle.kts, isolateFromHost). */
    @Test
    void theTestJvmIsHeadlessAndItsChildrenShowNoTrayIcon() {
        assertEquals("true", System.getProperty("java.awt.headless"));
        assertTrue(java.awt.GraphicsEnvironment.isHeadless());
        assertEquals("false", System.getenv(TRAY_SWITCH));
    }

    @Test
    void homeInstallStateAndTemporaryFoldersAreUnderTheModuleBuildFolder() {
        Path build = MODULE.resolve("build");
        // user.home and java.io.tmpdir are the system properties of this JVM; SENNARD_INSTALL_ROOT and SENNARD_STATE_DIR are what a child JVM takes its places from.
        List<String> locations = List.of(System.getProperty("user.home"), System.getProperty("java.io.tmpdir"), System.getenv("HOME"),
                System.getenv("USERPROFILE"), String.valueOf(System.getenv("SENNARD_INSTALL_ROOT")), String.valueOf(System.getenv("SENNARD_STATE_DIR")),
                System.getenv("TEMP"), System.getenv("TMP"), System.getenv("TMPDIR"));
        for (String location : locations) {
            assertTrue(location != null && Path.of(location).toAbsolutePath().normalize().startsWith(build), location + " is not under " + build);
        }
    }

    @Test
    void theHeapOfTheTestJvmIsTheOneTheBuildStates() {
        long maxHeap = Runtime.getRuntime().maxMemory();
        // sennard.test.statedHeap is the -Psennard.test.heap value the build gave maxHeapSize (java/build.gradle.kts); the option is optional.
        String stated = System.getProperty("sennard.test.statedHeap");
        if (stated != null && !stated.isBlank()) {
            assertTrue(maxHeap <= bytesOf(stated), "max heap " + maxHeap + " bytes, stated " + stated);
            return;
        }
        // Not stated: Gradle's own heap for a test JVM applies, not the JVM default of a quarter of the physical memory.
        long physical = ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
        assertTrue(maxHeap < physical / 4, "max heap " + maxHeap + " bytes is not below a quarter of the physical memory " + physical);
    }

    /** A JVM heap size as Gradle's maxHeapSize and -Xmx read it: digits with an optional k, m or g (any case) unit; no unit means bytes. */
    private static long bytesOf(String size) {
        String text = size.strip();
        char unit = Character.toLowerCase(text.charAt(text.length() - 1));
        long factor = switch (unit) {
            case 'k' -> 1024L;
            case 'm' -> 1024L * 1024;
            case 'g' -> 1024L * 1024 * 1024;
            default -> 1L;
        };
        String digits = factor == 1L ? text : text.substring(0, text.length() - 1);
        return Long.parseLong(digits) * factor;
    }
}
