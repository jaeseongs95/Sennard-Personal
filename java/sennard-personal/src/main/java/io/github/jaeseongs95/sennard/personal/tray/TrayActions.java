// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.tray;

import java.io.IOException;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the tray's menu does, without AWT: opening the admin page at the address the server is listening on, and stopping the server. An action that cannot be done says why in the log
 * (the user clicked on something and nothing happened), and it never throws into the AWT thread.
 */
final class TrayActions {
    private static final Logger LOG = LoggerFactory.getLogger(TrayActions.class);

    private final AdminPageOpener opener;
    private final Runnable stopServer;
    private final URI adminPage;

    /**
     * @param stopServer the server's own shutdown ({@code ShutdownEndpoint}, the one {@code sennard stop} reaches over HTTP), not a way of ending the process of its own
     * @param adminPage the origin of the admin page ({@code AdminPageAddress#origin()})
     */
    TrayActions(AdminPageOpener opener, Runnable stopServer, String adminPage) {
        this.opener = opener;
        this.stopServer = stopServer;
        this.adminPage = URI.create(adminPage);
    }

    /** Whether the menu entry that opens the page is enabled. */
    boolean canOpenAdminPage() {
        return opener.supported();
    }

    void openAdminPage() {
        if (!opener.supported()) {
            LOG.warn("The admin page {} was not opened: the desktop cannot open an address in a browser.", adminPage);
            return;
        }
        try {
            opener.open(adminPage);
        } catch (IOException | UnsupportedOperationException | SecurityException failed) {
            LOG.warn("The admin page {} was not opened.", adminPage, failed);
        }
    }

    void stopServer() {
        LOG.info("The server is stopped from the tray icon.");
        stopServer.run();
    }
}
