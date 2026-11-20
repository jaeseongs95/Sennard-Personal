// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Where a run's program is looked for (case q of the run cases), read from the system's own idea of a path so it holds on every operating system. */
class ProgramLocatorTest {
    private final ProgramLocator locator = new ProgramLocator();

    @Test
    void aRelativePathIsLookedForFromTheRootAndNormalised(@TempDir Path root) {
        assertEquals(root.resolve("tools").resolve("run").toString(), locator.locate(Path.of("tools", "run").toString(), root));
        assertEquals(root.resolve("gradlew").toString(), locator.locate(Path.of(".", "gradlew").toString(), root), "./gradlew is the root's own gradlew");
        assertEquals(root.resolve("gradlew").toString(), locator.locate(Path.of("sub", "..", "gradlew").toString(), root), "dots in the middle are removed");
    }

    @Test
    void aSlashIsASeparatorOnEverySystemSoASlashPathIsFoundFromTheRoot(@TempDir Path root) {
        assertEquals(root.resolve("tools").resolve("run").toString(), locator.locate("tools/run", root));
        assertEquals(root.resolve("gradlew").toString(), locator.locate("./gradlew", root));
    }

    @Test
    void aNameWithoutAPathAndAProgramWithARootAreLeftAsWritten(@TempDir Path root) {
        assertEquals("gradlew", locator.locate("gradlew", root), "the operating system's own search, not the root");
        assertEquals("gradlew.bat", locator.locate("gradlew.bat", root));
        String absolute = root.resolve("tool").toString();
        assertEquals(absolute, locator.locate(absolute, root));
        assertEquals("", locator.locate("", root));
    }
}
