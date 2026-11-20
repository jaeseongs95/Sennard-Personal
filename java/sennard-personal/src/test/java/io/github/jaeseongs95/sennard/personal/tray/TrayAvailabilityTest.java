// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.tray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/**
 * The tray is shown only when the JDK says there is a desktop to show it on: not headless, and a system tray supported. The decision is checked here without AWT.
 *
 * <pre>
 * a1  headless      a headless JVM shows nothing, and the tray's support is not even asked
 * a2  no tray       a desktop without a system tray shows nothing
 * a3  tray          a desktop with a system tray shows the icon
 * a4  reason        every outcome that shows nothing says why in a sentence for the log
 * </pre>
 */
class TrayAvailabilityTest {
    @Test
    void headlessShowsNothingAndDoesNotAskForTheTray() {
        assertEquals(TrayAvailability.HEADLESS, TrayAvailability.of(() -> true, () -> fail("SystemTray.isSupported() is not asked on a headless JVM")));
    }

    @Test
    void aDesktopWithoutASystemTrayShowsNothing() {
        assertEquals(TrayAvailability.NO_SYSTEM_TRAY, TrayAvailability.of(() -> false, () -> false));
    }

    @Test
    void aDesktopWithASystemTrayShowsTheIcon() {
        assertEquals(TrayAvailability.AVAILABLE, TrayAvailability.of(() -> false, () -> true));
    }

    @Test
    void everyOutcomeSaysWhy() {
        for (TrayAvailability outcome : TrayAvailability.values()) {
            assertFalse(outcome.reason().isBlank(), outcome.name());
        }
    }
}
