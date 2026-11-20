// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The entry point a host's command hook runs: {@code java <options> -cp <this module's jar> io.github.jaeseongs95.sennard.hookclient.HookClientMain hook <label> <event> [options]},
 * the user's own Java on this module's jar alone, not the release jar (which is a Spring Boot jar: its start is the cost this program exists to avoid). The words after the
 * class name are {@link HookClientCall#arguments()}, the installation's {@code --server-file=<path>} (where the server announces its address: the port is the operating system's
 * choice at each start) and {@code --jar=<path>} (the installed Sennard jar, which a session start runs when the server is down).
 *
 * <p>The streams are the process's raw standard streams, as a hook's host reads them: what is written is bytes, and no charset is chosen for it. Exit codes: what
 * {@link HookClient#run} returns; 2 for a command line that cannot be understood (its code on stderr).
 */
public final class HookClientMain {
    static final int USAGE_ERROR = 2;

    private HookClientMain() {
    }

    public static void main(String[] args) throws IOException {
        try (InputStream stdin = new FileInputStream(FileDescriptor.in); OutputStream stdout = new FileOutputStream(FileDescriptor.out);
                OutputStream stderr = new FileOutputStream(FileDescriptor.err)) {
            System.exit(run(List.of(args), System.getenv(), ProcessHandle.current(), stdin, stdout, stderr));
        }
    }

    /** Everything {@code main} does except the exit; the processes and the environment are given so that a test can say what they are. */
    static int run(List<String> words, Map<String, String> environment, ProcessHandle self, InputStream stdin, OutputStream stdout, OutputStream stderr) throws IOException {
        try {
            HookClientArguments.Parsed parsed = HookClientArguments.parse(words);
            HookClient.ServerStart start = parsed.jar() == null ? within -> {
                throw new IllegalStateException("no jar is given: " + HookClientCall.JAR);
            } : new JarServerStart(parsed.jar());
            HookClient client = new HookClient(environment, self, parsed.serverFile(), start, parsed.jar());
            if (parsed.warm() != null) {
                client.warm(parsed.warm());
            }
            return client.run(parsed.call(), stdin, stdout, stderr);
        } catch (HookOptionException invalid) {
            return refuse(invalid, stderr);
        }
    }

    /** A command line that cannot be understood: the exception is reported on stderr as it is (its message is its code, {@code HOOK_OPTION_INVALID <problem> <option>=<value>}) and the exit is 2. */
    private static int refuse(HookOptionException invalid, OutputStream stderr) throws IOException {
        stderr.write(("Sennard hook failed: " + invalid.getMessage()).getBytes(StandardCharsets.UTF_8));
        return USAGE_ERROR;
    }
}
