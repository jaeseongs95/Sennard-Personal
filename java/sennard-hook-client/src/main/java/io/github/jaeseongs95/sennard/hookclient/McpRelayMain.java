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
 * The entry point a host runs as its stdio MCP server: {@code java <options> -cp <this module's jar> io.github.jaeseongs95.sennard.hookclient.McpRelayMain --host=<label>
 * --server-file=<path> --jar=<path> --start-server=<seconds>} ({@link McpRelayArguments}). What it does is {@link McpRelay}'s: the host's stdio MCP messages go to the Sennard
 * server at the port it announced in the file. Standard output is the MCP channel and carries nothing else; the relay's own words go to standard error.
 *
 * <p>The streams are the process's raw standard streams, as the host reads them. Exit codes: 0 when stdin ended; 2 for a command line that cannot be understood (its code on stderr).
 */
public final class McpRelayMain {
    static final int USAGE_ERROR = 2;

    private McpRelayMain() {
    }

    public static void main(String[] args) throws IOException {
        try (InputStream stdin = new FileInputStream(FileDescriptor.in); OutputStream stdout = new FileOutputStream(FileDescriptor.out);
                OutputStream stderr = new FileOutputStream(FileDescriptor.err)) {
            System.exit(run(List.of(args), System.getenv(), stdin, stdout, stderr));
        }
    }

    /** Everything {@code main} does except the exit; the environment is given so that a test can say what it is. */
    static int run(List<String> words, Map<String, String> environment, InputStream stdin, OutputStream stdout, OutputStream stderr) throws IOException {
        McpRelayArguments.Parsed parsed;
        try {
            parsed = McpRelayArguments.parse(words);
        } catch (HookOptionException invalid) {
            return refuse(invalid, stderr);
        }
        HookClient.ServerStart start = parsed.jar() == null ? within -> {
            throw new IllegalStateException("no jar is given: " + HookClientCall.JAR);
        } : new JarServerStart(parsed.jar());
        return new McpRelay(environment, parsed, start).run(stdin, stdout, stderr);
    }

    /** A command line that cannot be understood: the exception is reported on stderr as it is (its message is its code, {@code HOOK_OPTION_INVALID <problem> <option>=<value>}) and the exit is 2. */
    private static int refuse(HookOptionException invalid, OutputStream stderr) throws IOException {
        stderr.write(("Sennard relay failed: " + invalid.getMessage()).getBytes(StandardCharsets.UTF_8));
        return USAGE_ERROR;
    }
}
