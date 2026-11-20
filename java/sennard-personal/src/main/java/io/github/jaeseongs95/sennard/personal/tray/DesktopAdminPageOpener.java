// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.tray;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

/** The JDK's desktop integration: {@code Desktop.browse}, when the desktop says it supports it. */
final class DesktopAdminPageOpener implements AdminPageOpener {
    @Override
    public boolean supported() {
        return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE);
    }

    @Override
    public void open(URI address) throws IOException {
        Desktop.getDesktop().browse(address);
    }
}
