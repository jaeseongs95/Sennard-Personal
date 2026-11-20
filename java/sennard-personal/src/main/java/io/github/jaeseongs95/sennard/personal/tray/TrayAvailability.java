// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.tray;

import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.util.function.BooleanSupplier;

/**
 * Whether the server shows a tray icon. The JDK says it, not a guess about the operating system: the tray is shown when {@code !GraphicsEnvironment.isHeadless() && SystemTray.isSupported()},
 * and otherwise the server runs as it does without one. The two facts are passed in as suppliers so that the decision is checked without AWT, and the second is asked only when the
 * first allows it ({@code SystemTray.isSupported()} on a headless JVM is not a question the JDK means to be asked).
 */
enum TrayAvailability {
    AVAILABLE("a tray icon can be shown"),
    HEADLESS("the JVM is headless"),
    NO_SYSTEM_TRAY("the desktop has no system tray"),
    DISABLED("the setting " + ServerTray.ENABLED_SETTING + " is false");

    private final String reason;

    TrayAvailability(String reason) {
        this.reason = reason;
    }

    /** The one line the log says when there is no icon: why. */
    String reason() {
        return reason;
    }

    static TrayAvailability of(BooleanSupplier headless, BooleanSupplier systemTraySupported) {
        if (headless.getAsBoolean()) {
            return HEADLESS;
        }
        return systemTraySupported.getAsBoolean() ? AVAILABLE : NO_SYSTEM_TRAY;
    }

    /** What this JVM's AWT says. */
    static TrayAvailability current() {
        return of(GraphicsEnvironment::isHeadless, SystemTray::isSupported);
    }
}
