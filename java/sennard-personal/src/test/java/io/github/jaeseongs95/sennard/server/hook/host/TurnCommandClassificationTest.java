// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TurnCommandClassificationTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '^', value = {"python app.py|python","pytest -q|pytest", "./run.sh|run.sh", "FOO=1 npm test|npm", "ls && pytest|pytest", "C:\\tools\\ctest.exe|ctest", "'python' x.py|python"})
    void aCommandThatRunsSomethingNamesIt(String command, String program) {
        assertEquals(program, TurnCommandClassification.runProgram(command));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '^', value = {"ls -la","cat a", "grep x a", "git status", "echo hi", "sed -i s/a/b/ f", "cat <<EOF", "get-content a", "cd x && ls"})
    void aCommandThatOnlyLooksIsNoRun(String command) {
        assertEquals("", TurnCommandClassification.runProgram(command));
    }

    @Test
    void aHeredocBodyIsNotReadForAProgram() {
        assertEquals("", TurnCommandClassification.runProgram("cat <<EOF\npytest\nEOF"));
    }

    @Test
    void aBlankCommandIsNoRun() {
        assertEquals("", TurnCommandClassification.runProgram(""));
        assertEquals("", TurnCommandClassification.runProgram("   "));
    }

    @Test
    void aCommandAfterInspectionCountsWithAnySupportedSeparator() {
        assertEquals("pytest", TurnCommandClassification.runProgram("ls || pytest"));
        assertEquals("pytest", TurnCommandClassification.runProgram("ls; pytest"));
        assertEquals("pytest", TurnCommandClassification.runProgram("ls | pytest"));
    }
}
