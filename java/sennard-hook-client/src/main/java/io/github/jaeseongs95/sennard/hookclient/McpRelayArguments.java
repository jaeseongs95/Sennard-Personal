// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.jaeseongs95.sennard.hookclient.HookOptionException.Problem;

/**
 * The words of the MCP relay's command line: {@code --host=<label> --server-file=<path> --jar=<path> --start-server=<seconds>}, all options, in any place, each given once. The
 * definition of an MCP server in a host's file is written from {@link #arguments} (an adapter in the server), and {@link #parse} reads it back, so the words cannot drift apart.
 * {@code --server-file} and {@code --jar} are the installation's, as they are for a hook ({@link HookClientCall#SERVER_FILE}, {@link HookClientCall#JAR}); {@code --start-server}
 * is the wait for a server that a session's {@code initialize} starts when none is running (the same option as a hook's session start, {@link HookClientCall#START_SERVER}).
 */
public final class McpRelayArguments {
    /** {@code --host=<label>}: the host's label, which the relay sends as the {@code Sennard-Host-Label} header of every request, as the host's own HTTP MCP configuration did. */
    public static final String HOST = "--host";
    private static final Set<String> VALUED = Set.of(HOST, HookClientCall.SERVER_FILE, HookClientCall.JAR, HookClientCall.START_SERVER);

    /**
     * What a command line asks for.
     *
     * @param host the host's label
     * @param serverFile where the server announces its address
     * @param jar the installed jar that starts the server, or null when the relay starts none
     * @param startServerSeconds how long an {@code initialize} waits for a server it started: 0 starts nothing
     */
    public record Parsed(String host, Path serverFile, Path jar, int startServerSeconds) {
    }

    private McpRelayArguments() {
    }

    /** The call-specific words of an MCP registration: the host's label and the wait for the server's start. The installation's own words ({@code --server-file}, {@code --jar}) are its program's. */
    public static List<String> arguments(String host, int startServerSeconds) {
        return List.of(HOST + "=" + host, HookClientCall.START_SERVER + "=" + startServerSeconds);
    }

    /** @throws HookOptionException when the words are not the relay's command line */
    public static Parsed parse(List<String> words) {
        Map<String, String> options = new HashMap<>();
        for (String word : words) {
            int equals = word.indexOf('=');
            String name = equals < 0 ? word : word.substring(0, equals);
            String value = equals < 0 ? "" : word.substring(equals + 1);
            if (!VALUED.contains(name) || equals < 0 || options.put(name, value) != null) {
                throw new HookOptionException(Problem.OTHER, name, value);
            }
        }
        String host = options.get(HOST);
        if (host == null || host.isEmpty()) {
            throw new HookOptionException(Problem.OTHER, HOST, "");
        }
        int start = HookClientArguments.number(options, HookClientCall.START_SERVER, 0);
        Path jar = options.containsKey(HookClientCall.JAR) ? HookClientArguments.path(options, HookClientCall.JAR) : null;
        if (start > 0 && jar == null) {
            throw new HookOptionException(Problem.OTHER, HookClientCall.JAR, ""); // a relay that starts the server needs the jar that does
        }
        return new Parsed(host, HookClientArguments.required(options, HookClientCall.SERVER_FILE), jar, start);
    }
}
