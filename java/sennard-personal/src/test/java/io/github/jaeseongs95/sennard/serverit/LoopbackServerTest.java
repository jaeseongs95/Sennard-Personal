// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.NestedExceptionUtils;

/**
 * The listener of the real server is bound to 127.0.0.1 and to nothing else: a request on that address is answered, and a connection to the same port on any other
 * address of this PC (another loopback address, IPv6 loopback, each address of each network interface) is refused. This is the only thing that keeps a connection
 * out; the guard filter checks headers and bodies, not who connects.
 */
class LoopbackServerTest {
    private static RunningServer server;

    @BeforeAll
    static void start() throws Exception {
        server = RunningServer.start(List.of(ServerFixtures.Beans.class));
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void theSettingIsTheLoopbackAddress() {
        assertEquals("127.0.0.1", server.context.getEnvironment().getRequiredProperty("server.address"));
    }

    @Test
    void aRequestOnTheLoopbackAddressIsAnswered() throws Exception {
        assertEquals(200, RawHttp.exchange(server.port, "GET", "/hooks/v1/probe", Map.of(), null).status());
    }

    @Test
    void aConnectionToAnotherLoopbackAddressOrIpv6LoopbackIsRefused() throws Exception {
        for (String other : List.of("127.0.0.2", "::1")) {
            assertRefused(InetAddress.getByName(other));
        }
    }

    @Test
    void aConnectionToAnAddressOfAnyNetworkInterfaceIsRefused() throws Exception {
        List<InetAddress> addresses = new ArrayList<>();
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (network.isUp()) {
                addresses.addAll(Collections.list(network.getInetAddresses()).stream().filter(address -> !address.isLoopbackAddress()).toList());
            }
        }
        for (InetAddress address : addresses) {
            assertRefused(address);
        }
    }

    /** The actuator can open a listener of its own; a start that would leave it on another address than loopback does not come up. */
    @Test
    void aStartWithAManagementListenerOnAnotherAddressIsRefused() {
        Exception refused = assertThrows(Exception.class,
                () -> RunningServer.start(List.of(ServerFixtures.Beans.class), "--management.server.port=0", "--management.server.address=0.0.0.0"));
        String reason = NestedExceptionUtils.getMostSpecificCause(refused).getMessage();
        assertTrue(reason.contains("management.server.address must be a loopback address"), refused.toString());
    }

    private static void assertRefused(InetAddress address) {
        try (Socket socket = new Socket()) {
            IOException refused = assertThrows(IOException.class, () -> socket.connect(new InetSocketAddress(address, server.port), 3_000), "a connection to " + address + " was accepted");
            assertTrue(!socket.isConnected(), refused.toString());
        } catch (IOException closing) {
            throw new AssertionError("the socket could not be closed", closing);
        }
    }
}
