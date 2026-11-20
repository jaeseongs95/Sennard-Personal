// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.personal.PersonalProperties;
import io.github.jaeseongs95.sennard.server.config.SennardProperties;

/**
 * The admin page's port of the real server: it listens on 127.0.0.1 and nowhere else, and it is the operating system's choice at each start, never a fixed or a configured
 * port. Two servers are up at the same time so that the second one's port cannot equal the first one's by the system reusing a freed number.
 *
 * <pre>
 * p1  loopback only  a connection to 127.0.0.1 on the page's port is accepted; a connection to every other address (127.0.0.2, ::1, each address of each network interface
 *                    that is up and not loopback) is refused. 127.0.0.2 and ::1 are always tried, so the bound address is checked on a PC with no other address too.
 * p2  free port      two starts get two different ports, neither the main port, and no setting record of the product has a port component (the page's connector is opened on port 0)
 * </pre>
 */
class AdminPortLoopbackServerTest {
    private static RunningServer first;
    private static RunningServer second;

    @BeforeAll
    static void start() throws Exception {
        first = RunningServer.start(List.of(ServerFixtures.Beans.class));
        second = RunningServer.start(List.of(ServerFixtures.Beans.class));
    }

    @AfterAll
    static void stop() {
        try {
            first.close();
        } finally {
            second.close();
        }
    }

    @Test
    void theAdminPortAcceptsLoopbackAndNoOtherAddress() throws Exception {
        int port = first.adminPort();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 3_000);
            assertTrue(socket.isConnected());
        }
        for (InetAddress other : otherAddresses()) {
            assertRefused(other, port);
        }
    }

    @Test
    void twoStartsGetTwoDifferentPortsThatAreNotTheMainPort() {
        assertTrue(first.adminPort() > 0);
        assertTrue(second.adminPort() > 0);
        assertNotEquals(first.adminPort(), second.adminPort());
        assertNotEquals(first.port, first.adminPort());
        assertNotEquals(second.port, second.adminPort());
    }

    @Test
    void noSettingOfTheProductHoldsAPort() {
        // The product's settings are the two records under the prefix sennard (sennard.* has no other binding of the admin page); none of their components, nested ones
        // included, is a port, so a port for the admin page cannot be configured and the connector's 0 is the only value it ever has.
        List<String> portComponents = new ArrayList<>();
        for (Class<? extends Record> properties : List.of(PersonalProperties.class, SennardProperties.class)) {
            collectPortComponents(properties, properties.getSimpleName(), portComponents);
        }
        assertEquals(List.of(), portComponents);
    }

    private static void collectPortComponents(Class<? extends Record> type, String path, List<String> found) {
        for (RecordComponent component : type.getRecordComponents()) {
            String name = path + "." + component.getName();
            if (component.getName().toLowerCase(Locale.ROOT).contains("port")) {
                found.add(name);
            }
            if (component.getType().isRecord()) {
                collectPortComponents(component.getType().asSubclass(Record.class), name, found);
            }
        }
    }

    /** 127.0.0.2 and ::1 always, and the non-loopback addresses of the interfaces that are up (none on a PC with no network). */
    private static List<InetAddress> otherAddresses() throws IOException {
        List<InetAddress> addresses = new ArrayList<>(List.of(InetAddress.getByName("127.0.0.2"), InetAddress.getByName("::1")));
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (network.isUp()) {
                addresses.addAll(Collections.list(network.getInetAddresses()).stream().filter(address -> !address.isLoopbackAddress()).toList());
            }
        }
        return addresses;
    }

    private static void assertRefused(InetAddress address, int port) throws IOException {
        try (Socket socket = new Socket()) {
            assertThrows(IOException.class, () -> socket.connect(new InetSocketAddress(address, port), 3_000), "a connection to " + address + " on the admin port was accepted");
            assertTrue(!socket.isConnected());
        }
    }
}
