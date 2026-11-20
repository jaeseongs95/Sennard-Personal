// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The environment of a run's child (case d of the run cases): only listed names of the server's, then the caller's. The values here are made up for the case. */
class RunEnvironmentTest {
    private final RunEnvironment environments = new RunEnvironment();

    @Test
    void aVariableOfTheServerThatIsNotListedIsNotPassedAndAListedOneThatTheServerLacksIsSkipped() {
        Map<String, String> server = new HashMap<>();
        server.put("PATH", "/server/bin");
        server.put("JAVA_HOME", "/server/jdk");
        server.put("OTHER_SESSION_ONLY_VARIABLE", "other-session-value");
        server.put("SENNARD_TEST_SERVER_ONLY", "server-only-value");

        Map<String, String> child = environments.build(server, Map.of());

        assertEquals(Map.of("PATH", "/server/bin", "JAVA_HOME", "/server/jdk"), child);
        assertFalse(child.containsKey("OTHER_SESSION_ONLY_VARIABLE"));
        assertFalse(child.containsKey("TEMP"), "a listed name the server does not have is skipped, not made up");
    }

    @Test
    void theCallersVariablesAreAddedAndWinOverAListedNameOfTheSameSpelling() {
        Map<String, String> child = environments.build(Map.of("PATH", "/server/bin", "HOME", "/home/server"), Map.of("SENNARD_CALLER_ONE", "one", "PATH", "/caller/bin"));

        assertEquals(Map.of("PATH", "/caller/bin", "HOME", "/home/server", "SENNARD_CALLER_ONE", "one"), child);
    }

    @Test
    void theListNamesWhatAProgramFailsWithoutAndNothingThatCarriesASecretByName() {
        assertTrue(RunEnvironment.INHERITED_NAMES.containsAll(List.of("PATH", "JAVA_HOME", "TEMP", "TMP", "TMPDIR", "HOME", "USERPROFILE", "SystemRoot", "windir", "LANG", "LC_ALL")));
        assertEquals(Set.copyOf(RunEnvironment.INHERITED_NAMES).size(), RunEnvironment.INHERITED_NAMES.size(), "no name twice");
        assertTrue(RunEnvironment.INHERITED_NAMES.stream().noneMatch(name -> name.matches("(?i).*(TOKEN|SECRET|PASSWORD|KEY|CREDENTIAL|AUTH).*")));
    }

    @Test
    void theDescriptionNamesTheListAndTheCallersVariablesSortedAndNeverAValue() {
        String none = environments.describe(Set.of());
        assertEquals("Child environment: emptied, then the server's variables " + String.join(", ", RunEnvironment.INHERITED_NAMES)
                + " when the server has them, then the variables set by the caller (names only; values are not recorded): none. Undeclared environmental inputs are not captured.", none);
        String named = environments.describe(Set.of("ZED", "ALPHA"));
        assertTrue(named.contains("(names only; values are not recorded): ALPHA, ZED. Undeclared"), named);
    }
}
