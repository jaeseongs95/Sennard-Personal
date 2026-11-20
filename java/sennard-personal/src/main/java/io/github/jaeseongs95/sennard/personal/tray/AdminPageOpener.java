// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.tray;

import java.io.IOException;
import java.net.URI;

/** What opens the admin page for the user: the desktop's browser in the server, a recorder in the tests (the AWT desktop is the host's, so it is behind this interface). */
interface AdminPageOpener {
    /** Whether the desktop can open an address in a browser. */
    boolean supported();

    /** Opens the address in the user's browser. */
    void open(URI address) throws IOException;
}
