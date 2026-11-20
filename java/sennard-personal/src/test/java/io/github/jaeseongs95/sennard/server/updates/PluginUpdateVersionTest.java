// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.updates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * How the installed version is ordered against a release (the case table of the version value): numbers by value, not as text; a prerelease before its own release and
 * after the release before it; a version that is not {@code major.minor.patch} is refused, not guessed.
 */
class PluginUpdateVersionTest {
    @Test
    void equalVersionsAreEqual() {
        assertEquals(0, PluginUpdateService.compareInstalled("6.0.0", "6.0.0"));
        assertEquals(0, PluginUpdateService.compareInstalled("0.1.0", "0.1.0"));
    }

    @Test
    void numbersAreComparedByValue() {
        assertTrue(PluginUpdateService.compareInstalled("5.9.9", "6.0.0") < 0);
        assertTrue(PluginUpdateService.compareInstalled("6.0.1", "6.0.0") > 0);
        assertTrue(PluginUpdateService.compareInstalled("10.0.0", "9.9.9") > 0, "10 is more than 9, though \"10\" sorts before \"9\" as text");
        assertTrue(PluginUpdateService.compareInstalled("1.0.0", "99999999999999999999.0.0") < 0, "a part larger than a long is still a number");
    }

    @Test
    void aPrereleaseIsBeforeItsReleaseAndAfterTheReleaseBefore() {
        assertEquals(-1, PluginUpdateService.compareInstalled("6.0.0-rc.1", "6.0.0"));
        assertTrue(PluginUpdateService.compareInstalled("6.0.0-rc.1", "5.9.9") > 0);
    }

    @Test
    void aVersionThatIsNotThreeNumbersIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PluginUpdateService.compareInstalled("6.0", "6.0.0"));
        assertThrows(IllegalArgumentException.class, () -> PluginUpdateService.compareInstalled("6.0.0", "06.0.0"));
        assertThrows(IllegalArgumentException.class, () -> PluginUpdateService.compareInstalled("six", "6.0.0"));
    }
}
