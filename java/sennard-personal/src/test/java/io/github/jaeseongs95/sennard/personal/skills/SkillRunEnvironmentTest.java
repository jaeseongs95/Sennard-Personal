// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.skills.SkillRunEnvironment;

/**
 * The policy for the environment of a run's child (case d of the run cases): only listed names of the server's, then the caller's. Which variables the child really gets is the
 * runner's work (the {@code Selected} case of {@code LocalProcessRunnerTest} and the environment case of {@code EngineeringRunTest}); the list and the words are the policy's.
 */
class SkillRunEnvironmentTest {
    private final SkillRunEnvironment environments = new SkillRunEnvironment();

    @Test
    void theListNamesWhatAProgramFailsWithoutAndNothingThatCarriesASecretByName() {
        assertTrue(SkillRunEnvironment.INHERITED_NAMES.containsAll(List.of("PATH", "JAVA_HOME", "TEMP", "TMP", "TMPDIR", "HOME", "USERPROFILE", "SystemRoot", "windir", "LANG", "LC_ALL")));
        assertEquals(Set.copyOf(SkillRunEnvironment.INHERITED_NAMES).size(), SkillRunEnvironment.INHERITED_NAMES.size(), "no name twice");
        assertTrue(SkillRunEnvironment.INHERITED_NAMES.stream().noneMatch(name -> name.matches("(?i).*(TOKEN|SECRET|PASSWORD|KEY|CREDENTIAL|AUTH).*")));
    }

    @Test
    void theDescriptionNamesTheListAndTheCallersVariablesSortedAndNeverAValue() {
        String none = environments.describe(Set.of());
        assertEquals("Child environment: emptied, then the server's variables " + String.join(", ", SkillRunEnvironment.INHERITED_NAMES)
                + " when the server has them, then the variables set by the caller (names only; values are not recorded): none. Undeclared environmental inputs are not captured.", none);
        String named = environments.describe(Set.of("ZED", "ALPHA"));
        assertTrue(named.contains("(names only; values are not recorded): ALPHA, ZED. Undeclared"), named);
    }
}
