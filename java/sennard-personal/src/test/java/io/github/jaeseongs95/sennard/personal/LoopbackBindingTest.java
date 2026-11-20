// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The server is protected by where it listens: the personal edition starts only when every listener it opens is on a loopback address. Another OS user of the same
 * PC is outside what Sennard guards against, so nothing else keeps a connection out. The settings are Boot's own: {@link ServerProperties} ({@code server.address},
 * {@code server.port}) and the environment's {@code management.server.address} and {@code management.server.port}; the address an unset or empty
 * setting gives is null.
 *
 * <pre>
 * l1  main listener      server.address loopback: accepted; any other address, and no address at all (every interface): refused
 * l2  management shares  management.server.port not set, the main port, or negative (management over HTTP off): no listener of its own, so an unset address is accepted
 * l3  management apart   a management.server.port that is another port (or 0, a free one) opens a listener of its own: its management.server.address must be loopback,
 *                        and an unset one binds every interface and is refused
 * l4  management address a management.server.address that is set is checked whichever port it goes with
 * </pre>
 */
class LoopbackBindingTest {
    private static ServerProperties serving(String address, Integer port) throws UnknownHostException {
        ServerProperties server = new ServerProperties();
        server.setAddress(InetAddress.getByName(address));
        server.setPort(port);
        return server;
    }

    private static StandardEnvironment management(Integer port, String address) {
        Map<String, Object> settings = new HashMap<>();
        if (port != null) {
            settings.put("management.server.port", port);
        }
        if (address != null) {
            settings.put("management.server.address", address);
        }
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("management", settings));
        return environment;
    }

    private static LoopbackBinding binding(String serverAddress, Integer serverPort, Integer managementPort, String managementAddress) throws UnknownHostException {
        return new LoopbackBinding(serving(serverAddress, serverPort), management(managementPort, managementAddress));
    }

    private static void assertRefused(String setting, org.junit.jupiter.api.function.Executable start) {
        IllegalStateException refused = assertThrows(IllegalStateException.class, start);
        assertTrue(refused.getMessage().contains(setting), refused.getMessage());
    }

    // ------------------------------------------------------------------------------------------------------------------ l1

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.0.0.2", "localhost", "::1"})
    void aLoopbackAddressIsAccepted(String address) throws UnknownHostException {
        binding(address, 12345, null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::", "192.168.0.10", "10.1.2.3", "203.0.113.7"})
    void anAddressThatIsNotLoopbackRefusesTheStart(String address) {
        assertRefused("server.address", () -> binding(address, 12345, null, null));
    }

    @Test
    void noAddressAtAllBindsEveryInterfaceAndRefusesTheStart() {
        assertRefused("server.address", () -> new LoopbackBinding(new ServerProperties(), new StandardEnvironment()));
    }

    // ------------------------------------------------------------------------------------------------------------------ l2

    @Test
    void aManagementPortThatIsNotSetTheMainOneOrNegativeOpensNoListenerAndNeedsNoAddress() throws UnknownHostException {
        binding("127.0.0.1", 12345, null, null);
        binding("127.0.0.1", 12345, 12345, null);
        binding("127.0.0.1", 12345, -1, null);
    }

    /** The server's port is the operating system's choice at each start ({@code server.port: 0}); where it listens is still the address that is checked, whatever the port. */
    @Test
    void aMainPortOfZeroLetsTheSystemChooseAndIsCheckedByItsAddressLikeAnyOther() throws UnknownHostException {
        binding("127.0.0.1", 0, null, null);
        assertRefused("server.address", () -> binding("192.168.0.10", 0, null, null));
        assertRefused("management.server.address", () -> binding("127.0.0.1", 0, 9090, null));
    }

    @Test
    void theMainPortDefaultsToBootsAndTheManagementPortIsComparedWithIt() throws UnknownHostException {
        binding("127.0.0.1", null, 8080, null);
        assertRefused("management.server.address", () -> binding("127.0.0.1", null, 9090, null));
    }

    // ------------------------------------------------------------------------------------------------------------------ l3

    @Test
    void aManagementPortOfItsOwnWithNoAddressBindsEveryInterfaceAndRefusesTheStart() {
        assertRefused("management.server.address", () -> binding("127.0.0.1", 12345, 9090, null));
        assertRefused("management.server.address", () -> binding("127.0.0.1", 12345, 0, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::", "192.168.0.10", "203.0.113.7"})
    void aManagementPortOfItsOwnOnAnAddressThatIsNotLoopbackRefusesTheStart(String address) {
        assertRefused("management.server.address", () -> binding("127.0.0.1", 12345, 9090, address));
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.0.0.2", "localhost", "::1"})
    void aManagementPortOfItsOwnOnALoopbackAddressIsAccepted(String address) throws UnknownHostException {
        binding("127.0.0.1", 12345, 9090, address);
        binding("127.0.0.1", 12345, 0, address);
    }

    // ------------------------------------------------------------------------------------------------------------------ l4

    @Test
    void aManagementAddressThatIsSetIsCheckedWhicheverPortItGoesWith() throws UnknownHostException {
        assertRefused("management.server.address", () -> binding("127.0.0.1", 12345, null, "192.168.0.10"));
        assertRefused("management.server.address", () -> binding("127.0.0.1", 12345, 12345, "0.0.0.0"));
        binding("127.0.0.1", 12345, null, "127.0.0.1");
    }

    @Test
    void aMainAddressThatIsNotLoopbackRefusesTheStartWhateverManagementSays() {
        assertRefused("server.address", () -> binding("192.168.0.10", 12345, 9090, "127.0.0.1"));
    }
}
