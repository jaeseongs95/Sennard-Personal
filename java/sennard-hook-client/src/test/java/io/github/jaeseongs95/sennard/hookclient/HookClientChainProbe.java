// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.io.ByteArrayInputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import io.github.jaeseongs95.sennard.hookclient.HookClientCall.FailureAnswer;

/**
 * A process of its own that does what the hook client does for a session start: it posts a hook that binds the host, as the real {@link HookClient} of this process, to the server
 * at the address that the file it is given announces, so that the test can read the {@code X-Sennard-Host-Pids} the server received. Started by {@code HookClientTest} (and relayed by
 * {@link HookClientChainRelay}); not a test itself.
 *
 * <p>Arguments: the address file of the server ({@link ServerAddress}), the host label, the event; anything after that is ignored (a test puts the words of a hook command line there to
 * make this process's own command line look like one).
 */
public final class HookClientChainProbe {
    private HookClientChainProbe() {
    }

    public static void main(String[] args) throws Exception {
        HookClientCall call = HookClientCall.of(args[1], args[2], FailureAnswer.FAIL).bindingHost().withTimeout(10);
        // The raw standard streams, as a hook's host reads them (what the client writes is bytes, not log lines).
        try (var stdout = new FileOutputStream(FileDescriptor.out); var stderr = new FileOutputStream(FileDescriptor.err)) {
            int exit = new HookClient(System.getenv(), ProcessHandle.current(), Path.of(args[0]), within -> {
                throw new IllegalStateException("this probe does not start a server");
            }).run(call, new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), stdout, stderr);
            System.exit(exit);
        }
    }
}
