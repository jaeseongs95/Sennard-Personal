// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.server.hook.HookInstallation;

/**
 * What a test in another package needs of the installation's two programs: {@link HookClientCommand} is the install package's own (package-private), and the process tests of the
 * hook definitions ({@code HookLauncherProcessTest}, {@code HookLauncherRealHostChainTest}) must run the very words an installation's definitions name, not words they made up. Not a
 * test itself.
 */
public final class InstalledHookCommand {
    private InstalledHookCommand() {
    }

    /**
     * The words an installation's hook definitions name before the call's arguments ({@link HookClientCommand#hookProgram}): the {@code java}, the client's JVM options, {@code -cp}, the
     * client's jar, its main class, {@code --server-file=} and {@code --jar=}.
     */
    public static List<String> hookProgram(Path java, InstalledVersion version, Path serverFile) {
        return HookClientCommand.hookProgram(java, version, serverFile);
    }

    /** The words an installation's MCP registrations name before the relay's own arguments ({@link HookClientCommand#mcpProgram}). */
    public static List<String> mcpProgram(Path java, InstalledVersion version, Path serverFile) {
        return HookClientCommand.mcpProgram(java, version, serverFile);
    }

    /** What an installation's {@code hooks} command gives the adapters: the two programs of {@code version}, which find the server through {@code serverFile}. */
    public static HookInstallation installation(Path java, InstalledVersion version, Path serverFile) {
        return HookClientCommand.installation(java, version, serverFile);
    }

    /**
     * A version folder with the hook client's jar and nothing else: {@code <installRoot>/versions/<version>/lib/sennard-hook-client-<version>.jar}, copied from the extracted release.
     * The definitions of such a version run the client, and the application jar they name is not there, so a session start that finds no server cannot start one: it ends as the
     * installation's start ends when the jar it names has been removed. That is what a case about a server that is down wants (it must never start a real one), and what a case about
     * an installation that is gone wants.
     */
    public static InstalledVersion hookClientOnly(Path installRoot) throws IOException {
        Path jar = new InstalledVersion(ReleaseJarFixture.extracted()).hookClientJar();
        Path lib = Files.createDirectories(installRoot.resolve(InstalledVersion.VERSIONS).resolve(ReleaseJarFixture.version()).resolve("lib"));
        Files.copy(jar, lib.resolve(jar.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        return new InstalledVersion(lib.getParent());
    }

    /**
     * Announces a server that is this test's own process, listening at {@code port} (a stand-in server or an in-process one), the way the real server announces itself: through
     * {@link ServerAddress#writeAtomically}, with this process's id and start, so that {@link ServerAddress#live()} holds for it. The port is the one the operating system gave the
     * stand-in; no test chooses one.
     */
    public static void announceThisProcess(Path file, int port) throws IOException {
        ProcessHandle self = ProcessHandle.current();
        new ServerAddress(port, self.pid(), self.info().startInstant().orElseThrow(() -> new IllegalStateException("The JVM does not tell when this test's process started"))).writeAtomically(file);
    }

    /**
     * A command string split the way a host that takes a string and runs no shell splits it: at spaces, a word in double quotes (a path with a space) being one word without
     * its quotes.
     */
    public static List<String> split(String commandString) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean quoted = false;
        for (char each : commandString.toCharArray()) {
            if (each == '"') {
                quoted = !quoted;
            } else if (each == ' ' && !quoted) {
                if (!word.isEmpty()) {
                    words.add(word.toString());
                    word.setLength(0);
                }
            } else {
                word.append(each);
            }
        }
        if (!word.isEmpty()) {
            words.add(word.toString());
        }
        return words;
    }
}
