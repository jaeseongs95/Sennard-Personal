// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The program of the jar that {@link JarServerStartTest} makes in place of the Sennard jar: it writes the arguments it got into a file beside the jar, and ends with exit code 3
 * when a file {@code fail} is there too. It is what {@code java -jar <jar> start --within=<seconds>} runs in the test, so the test sees the command the client really ran. Not a test
 * itself.
 */
public final class StartRecorder {
    private StartRecorder() {
    }

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(StartRecorder.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.writeString(jar.resolveSibling("start-arguments.txt"), String.join("\n", args), StandardCharsets.UTF_8);
        if (Files.exists(jar.resolveSibling("fail"))) {
            System.exit(3);
        }
    }
}
