// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The host of {@code HookLauncherRealHostChainTest}: a program that runs the command it is given for a session start hook the way a host does (the command as its child, the
 * hook's JSON on its stdin) and reports what the command did on its own stdout, line by line ({@code hookExit=}, {@code hookStdout=}). The command is the installed hook
 * client's words (the user's {@code java}, the client's jar, its main class, the installation's {@code --server-file=} and {@code --jar=} and the call's arguments), or a shell that runs them
 * as one string, which is how a host that takes a command string runs a hook. Not a test itself.
 *
 * <p>Arguments: the command's words, program first.
 */
public final class HookLauncherHostStandIn {
    private HookLauncherHostStandIn() {
    }

    public static void main(String[] args) throws Exception {
        Process hook = new ProcessBuilder(Arrays.asList(args)).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var stdin = hook.getOutputStream()) {
            stdin.write("{\"session_id\":\"launcher-chain-1\",\"hook_event_name\":\"SessionStart\",\"source\":\"startup\"}".getBytes(StandardCharsets.UTF_8));
        }
        byte[] stdout = hook.getInputStream().readAllBytes();
        int exit = hook.waitFor();
        // The report is this program's output for the test to read, written to the raw standard stream.
        try (var report = new FileOutputStream(FileDescriptor.out)) {
            report.write(("hookExit=" + exit + "\nhookStdout=" + new String(stdout, StandardCharsets.UTF_8).strip() + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }
}
