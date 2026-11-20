// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The admin page's address before its connector is bound: the page's port is the operating system's choice when the server starts, so until then no port is the page's and no
 * address can be given. What a running server answers is {@code AdminPageServerTest}'s.
 *
 * <pre>
 * a1  not bound   no port is the page's (not 0, not a negative one, not a port of a fixed kind), and the port and the origin are refused
 * a2  host name   the page's host is localhost, which is what a passkey is bound to
 * </pre>
 */
class AdminPageAddressTest {
    @Test
    void beforeItIsBoundNoPortIsThePagesAndNoAddressIsGiven() {
        AdminPageAddress address = new AdminPageAddress();
        for (int port : new int[] {-1, 0, 1, 12345, 65535}) {
            assertFalse(address.owns(port), "port " + port);
        }
        assertThrows(IllegalStateException.class, address::port);
        assertThrows(IllegalStateException.class, address::origin);
    }

    @Test
    void thePagesHostIsLocalhostBecauseAPasskeyIsBoundToIt() {
        assertEquals("localhost", AdminPageAddress.HOST);
    }
}
