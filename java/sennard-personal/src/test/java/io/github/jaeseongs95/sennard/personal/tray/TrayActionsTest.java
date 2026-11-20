// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.tray;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * What the tray's menu does, with a desktop that records instead of opening a browser.
 *
 * <pre>
 * b1  open          the entry opens the address the server is listening on
 * b2  unsupported   a desktop that cannot open a browser: the entry is disabled and nothing is opened
 * b3  refused       a browser that cannot be started is a log line, not an exception into the AWT thread
 * b4  stop          the entry runs the server's shutdown, once
 * </pre>
 */
class TrayActionsTest {
    private static final String PAGE = "http://localhost:52531";

    private static final class Desktop implements AdminPageOpener {
        private final boolean supported;
        private final IOException failure;
        private final List<URI> opened = new ArrayList<>();

        Desktop(boolean supported, IOException failure) {
            this.supported = supported;
            this.failure = failure;
        }

        @Override
        public boolean supported() {
            return supported;
        }

        @Override
        public void open(URI address) throws IOException {
            opened.add(address);
            if (failure != null) {
                throw failure;
            }
        }
    }

    @Test
    void theEntryOpensTheAddressTheServerListensOn() {
        Desktop desktop = new Desktop(true, null);
        TrayActions actions = new TrayActions(desktop, () -> {
        }, PAGE);
        assertTrue(actions.canOpenAdminPage());
        actions.openAdminPage();
        assertEquals(List.of(URI.create(PAGE)), desktop.opened);
    }

    @Test
    void aDesktopThatCannotOpenABrowserDisablesTheEntryAndOpensNothing() {
        Desktop desktop = new Desktop(false, null);
        TrayActions actions = new TrayActions(desktop, () -> {
        }, PAGE);
        assertFalse(actions.canOpenAdminPage());
        assertDoesNotThrow(actions::openAdminPage);
        assertTrue(desktop.opened.isEmpty());
    }

    @Test
    void aBrowserThatCannotBeStartedDoesNotThrowIntoTheAwtThread() {
        Desktop desktop = new Desktop(true, new IOException("no browser"));
        TrayActions actions = new TrayActions(desktop, () -> {
        }, PAGE);
        assertDoesNotThrow(actions::openAdminPage);
        assertEquals(1, desktop.opened.size());
    }

    @Test
    void theStopEntryRunsTheServersShutdownOnce() {
        int[] stops = {0};
        TrayActions actions = new TrayActions(new Desktop(true, null), () -> stops[0]++, PAGE);
        actions.stopServer();
        assertEquals(1, stops[0]);
    }
}
