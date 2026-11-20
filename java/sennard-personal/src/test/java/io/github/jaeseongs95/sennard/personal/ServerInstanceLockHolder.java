// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * A process of its own that holds the lock of a state folder, as a running server does: it takes {@link ServerInstanceLock}, tells that it holds it (one line on stdout), and holds it until
 * its stdin ends. Started by {@code ServerInstanceLockTest}, which is how a lock held by another process (not another thread or another channel of its own JVM) is refused. Not a test itself.
 *
 * <p>Argument: the state folder.
 */
public final class ServerInstanceLockHolder {
    /** What the process writes once it holds the lock. */
    static final String HELD = "held";

    private ServerInstanceLockHolder() {
    }

    public static void main(String[] args) throws Exception {
        try (ServerInstanceLock held = ServerInstanceLockTest.lockOn(Path.of(args[0])); InputStream stdin = new FileInputStream(FileDescriptor.in);
                OutputStream stdout = new FileOutputStream(FileDescriptor.out)) {
            stdout.write((HELD + " " + held.folder().getFileName() + "\n").getBytes(StandardCharsets.UTF_8));
            stdout.flush();
            stdin.readAllBytes(); // until the test closes it
        }
    }
}
