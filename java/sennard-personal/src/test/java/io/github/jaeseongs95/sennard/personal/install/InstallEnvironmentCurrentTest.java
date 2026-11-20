// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The real environment of the CLI process, taken through Spring's {@code StandardEnvironment} and not by {@code System.getenv} in the product's code: it is the same process
 * environment (every variable, with the JVM's own matching of names), the home folder is the JVM's {@code user.home} (not {@code USERPROFILE} or {@code HOME}), the encoding of the OS
 * is the JDK's {@code native.encoding}, and the {@code java} is the program this JVM was started from.
 */
class InstallEnvironmentCurrentTest {
    @Test
    void theEnvironmentIsTheProcessesTheHomeIsUserHomeAndTheEncodingIsTheOperatingSystems() {
        InstallEnvironment current = InstallEnvironment.current();
        Map<String, String> process = System.getenv();
        assertEquals(process, current.environment(), "the same variables");
        for (String name : process.keySet()) {
            assertEquals(process.get(name), current.environment().get(name), name);
        }
        assertEquals(Path.of(System.getProperty("user.home")), current.home(), "user.home, which the build points at the test run's own folder");
        assertEquals(Charset.forName(System.getProperty("native.encoding")), current.fileNameEncoding());
    }

    @Test
    void theJavaIsTheProgramThisJvmWasStartedFromByItsAbsolutePath() {
        Path java = InstallEnvironment.current().java().path();
        assertEquals(Path.of(ProcessHandle.current().info().command().orElseThrow()).toAbsolutePath(), java);
        assertTrue(java.isAbsolute(), "a host starts a command without the user's PATH: the definitions name the java by its absolute path");
    }

    @Test
    void theInstallRootOfTheCurrentEnvironmentIsTheOneTheBuildIsolatedThroughTheVariable() {
        InstallEnvironment current = InstallEnvironment.current();
        assertNotNull(System.getenv("SENNARD_INSTALL_ROOT"), "the build isolates the installation folder of a test run");
        assertEquals(Path.of(System.getenv("SENNARD_INSTALL_ROOT")), current.installRoot(), "the test run never reaches the real profile's installation");
    }
}
