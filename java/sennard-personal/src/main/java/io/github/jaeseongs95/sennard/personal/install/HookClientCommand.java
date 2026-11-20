// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.jaeseongs95.sennard.hookclient.HookClientMain;
import io.github.jaeseongs95.sennard.hookclient.McpRelayMain;
import io.github.jaeseongs95.sennard.server.hook.HookInstallation;

/**
 * The two programs an installation's host definitions run, as the words the definitions name them by: the installed {@code java}, the client's JVM options (the small serial collector and
 * the AOT cache of {@link JvmOptions#hookClient}), the client's jar as the whole class path, the main class, and the two options that tell the client where the server is
 * ({@code --server-file}, the address file the server writes) and which jar starts it ({@code --jar}). The first program is a host's command hook ({@link HookClientMain}); the second is
 * the MCP server a host starts as a child process and talks to over its standard streams ({@link McpRelayMain}), which relays to the HTTP endpoint of the server that runs now.
 *
 * <p>Why a process of the JDK and not Java inside the server's JVM: a host starts a new process for every command hook, and one that starts only the client's few classes
 * is what keeps the hook's delay near an empty JVM's. The words are given one by one, so that a path with a space is one word whatever the host does with them (a host that takes
 * a command string has them quoted where it must be, see {@code HookInstallation#commandLine}). The definitions name the {@code java} by its absolute path because a host starts a command
 * without the user's shell, and the server file and jar by absolute paths because the port is not known beforehand: a hook finds the server through the file at each call.
 *
 * <p>The class path of the training run and of the run the host starts must be the same words, or the JVM refuses the cache; both are made here.
 */
final class HookClientCommand {
    private HookClientCommand() {
    }

    /** The programs and native platform of the installation whose host definitions are being written. */
    static HookInstallation installation(Path java, InstalledVersion version, Path serverFile) {
        String system = ManagementFactory.getOperatingSystemMXBean().getName();
        HookInstallation.Platform platform = system.startsWith("Windows") ? HookInstallation.Platform.WINDOWS : HookInstallation.Platform.POSIX;
        return new HookInstallation(hookProgram(java, version, serverFile), mcpProgram(java, version, serverFile), platform);
    }

    /** The words of a host's command hook before the call's own arguments. */
    static List<String> hookProgram(Path java, InstalledVersion version, Path serverFile) {
        return withLocations(withOptions(java, version, JvmOptions.hookClient(version), HookClientMain.class.getName()), version, serverFile);
    }

    /** The words of the MCP server a host starts, before the relay's own arguments ({@code --host}, {@code --start-server}). */
    static List<String> mcpProgram(Path java, InstalledVersion version, Path serverFile) {
        return withLocations(withOptions(java, version, JvmOptions.hookClient(version), McpRelayMain.class.getName()), version, serverFile);
    }

    /** The program with the JVM options given (the steps that make its AOT cache have their own) and no word after the main class. */
    static List<String> withOptions(Path java, InstalledVersion version, List<String> jvmOptions) {
        return withOptions(java, version, jvmOptions, HookClientMain.class.getName());
    }

    private static List<String> withOptions(Path java, InstalledVersion version, List<String> jvmOptions, String mainClass) {
        return JvmOptions.command(java, jvmOptions, version.hookClientJar(), mainClass);
    }

    private static List<String> withLocations(List<String> program, InstalledVersion version, Path serverFile) {
        List<String> words = new ArrayList<>(program);
        words.add("--server-file=" + serverFile);
        words.add("--jar=" + version.appJar());
        return List.copyOf(words);
    }
}
