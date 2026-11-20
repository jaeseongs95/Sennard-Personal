// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.identity;

import java.io.IOException;
import java.nio.file.Path;

/** A real process to watch: this class's main runs in its own JVM and sleeps until killed. */
public final class HostProcessFixture {
    private HostProcessFixture() {
    }

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(10 * 60_000L);
    }

    static Process start() throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return new ProcessBuilder(java.toString(), "-Xmx" + System.getProperty("sennard.test.childHeap"), "-cp", System.getProperty("java.class.path"), HostProcessFixture.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
