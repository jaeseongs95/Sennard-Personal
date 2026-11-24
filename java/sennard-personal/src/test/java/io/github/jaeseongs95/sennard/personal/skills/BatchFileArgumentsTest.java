// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.skills;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.personal.BatchFileArguments;
import io.github.jaeseongs95.sennard.server.execution.Refused.Reason;
import io.github.jaeseongs95.sennard.server.execution.Refused;

/**
 * The judgment of which command lines may reach a Windows batch file (case e of the run cases). It reads text only, so it is checked on every operating system; the
 * facts it rests on (what JDK 25 does with such arguments) are in the class's own documentation and in {@code EngineeringRunTest}'s Windows-only case.
 */
class BatchFileArgumentsTest {
    private final BatchFileArguments guard = new BatchFileArguments();

    private Refused refusal(List<String> argv) {
        return assertThrows(Refused.class, () -> guard.requireSafe(argv));
    }

    @Test
    void aProgramNamedAsABatchFileIsOneInAnyCaseAndWithTheDotsAndSpacesWindowsIgnores() {
        for (String program : List.of("gradlew.bat", "C:\\work\\gradlew.bat", "/work/gradlew.BAT", "build.cmd", "Build.Cmd", "a b\\run.cmd", "gradlew.bat.", "gradlew.bat ", "gradlew.bat. .")) {
            assertTrue(guard.isBatchFile(program), program);
        }
        for (String program : List.of("java", "java.exe", "gradlew", "gradlew.bat.exe", "bat", ".bat.txt", "cmd.exe", "run.batch", "", " ", ".")) {
            assertFalse(guard.isBatchFile(program), "'" + program + "'");
        }
    }

    @Test
    void eachCharacterTheInterpreterReadsAsSyntaxMakesAnArgumentOfABatchFileUnsafe() {
        for (String syntax : List.of("\"", "&", "|", "<", ">", "^", "%", "!", "\r", "\n")) {
            Refused refused = refusal(List.of("gradlew.bat", "build", "a" + syntax + "b"));
            assertEquals(Reason.UNSAFE_ARGUMENT, refused.reason());
            assertEquals(2, refused.detail(), () -> "argument 2 holds " + syntax.codePointAt(0));
        }
        assertEquals(0, refusal(List.of("C:\\a&b\\run.cmd")).detail(), "the program's own name is an item too");
        assertEquals(1, refusal(List.of("run.cmd", "x%PATH%", "y&z")).detail(), "the first one is named");
    }

    @Test
    void theUsualArgumentsOfAWrapperAreNotRefused() {
        assertDoesNotThrow(() -> guard.requireSafe(List.of("D:\\work\\gradlew.bat", "--no-daemon", "--console=plain", "--max-workers=1", "-Psennard.test.forks=1", ":sennard-personal:test",
                "--tests", "io.github.jaeseongs95.sennard.personal.skills.EngineeringRunTest*", "-Dkey=value", "path with spaces", "a,b;c", "(x)", "'quoted'", "\u00e9\uc548")));
        assertDoesNotThrow(() -> guard.requireSafe(List.of("gradlew.bat")));
    }

    @Test
    void aProgramThatIsNotABatchFileIsNotJudged() {
        assertDoesNotThrow(() -> guard.requireSafe(List.of("java", "-cp", "a&b", "\"x\"", "%PATH%", ">out")));
        assertDoesNotThrow(() -> guard.requireSafe(List.of()));
    }
}
