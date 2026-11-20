// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Where the program of a test-engineering run is looked for. The JDK looks for a relative program against the server's own working directory (on Windows) or the child's
 * (elsewhere), neither of which is the root the run is about, so a program named with a path that is not absolute is looked for from the root. A program named without
 * a path is left to the operating system's own search (the server's {@code PATH}); the root is not searched for it.
 *
 * <p>"A path" is what {@link Path} reads as more than one name on this system: {@code ./gradlew} on every system, {@code .\gradlew.bat} only where the backslash separates
 * names. A program with a root of its own ({@code C:\tool.exe}, {@code \tool.exe}, {@code C:tool.exe}) is left as written.
 */
@Component
public class ProgramLocator {
    private static final Logger LOG = LoggerFactory.getLogger(ProgramLocator.class);

    /** What is handed to the operating system for {@code program}: the program from the root when it is a relative path, as written otherwise. */
    public String locate(String program, Path root) {
        Path path;
        try {
            path = Path.of(program);
        } catch (InvalidPathException notAPath) {
            LOG.debug("The program is not a path on this system; it is left to the operating system as written: {}", program, notAPath);
            return program;
        }
        if (path.getRoot() != null || path.getNameCount() < 2) {
            return program;
        }
        return root.resolve(path).normalize().toString();
    }
}
