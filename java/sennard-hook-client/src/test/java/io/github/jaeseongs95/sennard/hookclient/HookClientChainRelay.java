// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A process between the host and the hook client, as a shell is when a host runs a hook through one: it starts {@link HookClientChainProbe} as its child with the arguments it was
 * given and ends with the child's exit code. Its own arguments are the child's, so a test that adds the words {@code hook <host> <event>} to them gives this process the command
 * line of a shell that runs that hook, and one that leaves them out gives it the command line of any other program. Started by {@code HookClientTest}; not a test itself.
 */
public final class HookClientChainRelay {
    private HookClientChainRelay() {
    }

    public static void main(String[] args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx" + System.getProperty("sennard.test.childHeap"), "-cp",
                System.getProperty("java.class.path"), HookClientChainProbe.class.getName()));
        command.addAll(List.of(args));
        Process child = new ProcessBuilder(command).inheritIO().start();
        System.exit(child.waitFor());
    }
}
