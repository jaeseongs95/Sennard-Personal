// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import io.github.jaeseongs95.sennard.hookclient.CallerTimeZone;
import io.github.jaeseongs95.sennard.hookclient.HookHeaders;
import io.github.jaeseongs95.sennard.hookclient.ServerAddress;

/**
 * The CLI's requests carry the caller's time zone: X-Sennard-Time-Zone is the IANA name the calling environment (its TZ variable) gives, and is left
 * out when the environment gives none, never taken from the JVM. The server the requests go to is found through its address file, as a command finds the real one.
 *
 * <pre>
 * t1  TZ=Asia/Seoul                 sent
 * t2  TZ=:Europe/Paris              sent as Europe/Paris
 * t3  no TZ                         not sent
 * t4  TZ=KST-9, UTC+9, empty        not sent (not region names)
 * </pre>
 */
class ServerControlTimeZoneTest {
    @TempDir
    Path temporary;

    /** What the health endpoint of a stand-in server saw as the header of a request from a client built with this environment. */
    private String sent(Map<String, String> environment) throws IOException {
        AtomicReference<String> seen = new AtomicReference<>("(no request)");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/actuator/health", exchange -> {
            seen.set(String.valueOf(exchange.getRequestHeaders().getFirst(HookHeaders.TIME_ZONE)));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            // The stand-in's port is what its own address file says, with this test's process standing for the server's (a file that names a live process).
            Path file = temporary.resolve("server.properties");
            ProcessHandle self = ProcessHandle.current();
            new ServerAddress(server.getAddress().getPort(), self.pid(), self.info().startInstant().orElseThrow()).writeAtomically(file);
            assertTrue(new ServerControl(file, environment).healthy());
        } finally {
            server.stop(0);
        }
        return seen.get();
    }

    @Test
    void anIanaNameInTzIsSent() throws IOException {
        assertEquals("Asia/Seoul", sent(Map.of("TZ", "Asia/Seoul")));
        assertEquals("Europe/Paris", sent(Map.of("TZ", ":Europe/Paris")));
    }

    @Test
    void noTzIsNoHeader() throws IOException {
        assertEquals("null", sent(Map.of()));
    }

    @Test
    void aValueThatIsNotARegionNameIsNoHeader() throws IOException {
        assertEquals("null", sent(Map.of("TZ", "KST-9")));
        assertEquals("null", sent(Map.of("TZ", "UTC+9")));
        assertEquals("null", sent(Map.of("TZ", "")));
        assertEquals(Optional.empty(), CallerTimeZone.from(Map.of("TZ", "Not/AZone")));
        assertNull(CallerTimeZone.from(Map.of()).orElse(null));
    }
}
