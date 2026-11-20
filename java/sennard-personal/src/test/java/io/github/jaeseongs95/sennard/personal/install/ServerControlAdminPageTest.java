// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import io.github.jaeseongs95.sennard.hookclient.ServerAddress;

/**
 * Where the admin page listens is read from the server's own endpoint, and the server is found through the address file it writes (its port is the operating system's choice at
 * each start): a server that does not answer, or that no file announces, is the answer "unknown" (empty), and the reason is logged where it is met.
 *
 * <pre>
 * a1  answers     a server the file announces, and that answers, names its admin page
 * a2  error       a server that answers with an error status is unknown
 * a3  closed      a file that announces a port nothing listens on is unknown
 * a4  no file     no address file, or a file of a process that is gone, is unknown: nothing is asked
 * a5  moved       the file is read again for every question: a server that started again at another port is the one asked next
 * </pre>
 */
class ServerControlAdminPageTest {
    @TempDir
    Path temporary;

    private static HttpServer server(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/actuator/sennard", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    /** The file a server writes once it listens: this test's own process stands for the server's, so the file names a process that lives. */
    private static void announce(Path file, int port) throws IOException {
        ProcessHandle self = ProcessHandle.current();
        new ServerAddress(port, self.pid(), self.info().startInstant().orElseThrow()).writeAtomically(file);
    }

    @Test
    void aServerThatAnswersNamesItsAdminPage() throws IOException {
        HttpServer server = server(200, "{\"adminPage\":\"http://127.0.0.1:50123/\",\"other\":1}");
        try {
            Path file = temporary.resolve("server.properties");
            announce(file, server.getAddress().getPort());
            assertEquals(Optional.of("http://127.0.0.1:50123/"), new ServerControl(file, Map.of(), Duration.ofSeconds(5)).adminPage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aServerThatAnswersWithAnErrorIsUnknown() throws IOException {
        HttpServer server = server(500, "{}");
        try {
            Path file = temporary.resolve("server.properties");
            announce(file, server.getAddress().getPort());
            assertEquals(Optional.empty(), new ServerControl(file, Map.of(), Duration.ofSeconds(5)).adminPage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aFileThatAnnouncesAPortNothingListensOnIsUnknown() throws IOException {
        HttpServer server = server(200, "{}");
        int closed = server.getAddress().getPort();
        server.stop(0);
        Path file = temporary.resolve("server.properties");
        announce(file, closed);
        assertEquals(Optional.empty(), new ServerControl(file, Map.of(), Duration.ofSeconds(5)).adminPage());
    }

    @Test
    void noFileOrTheFileOfAProcessThatIsGoneIsUnknownAndNothingIsAsked() throws IOException {
        Path file = temporary.resolve("server.properties");
        ServerControl control = new ServerControl(file, Map.of(), Duration.ofSeconds(5));
        assertEquals(Optional.empty(), control.adminPage(), "no file: no server announced itself");
        assertEquals(Optional.empty(), control.running());

        // The same process id with another start time is a process that was given the id after the server's end: the file is a leftover.
        ProcessHandle self = ProcessHandle.current();
        HttpServer server = server(200, "{\"adminPage\":\"http://127.0.0.1:50123/\"}");
        try {
            new ServerAddress(server.getAddress().getPort(), self.pid(), self.info().startInstant().orElseThrow().minusSeconds(3600)).writeAtomically(file);
            assertEquals(Optional.empty(), control.running(), "a process that did not start when the file says it did is not the server");
            assertEquals(Optional.empty(), control.adminPage(), "so the port in the file is not asked");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theFileIsReadAgainForEveryQuestionSoAServerStartedAtAnotherPortIsTheOneAsked() throws IOException {
        HttpServer first = server(200, "{\"adminPage\":\"http://127.0.0.1:1111/\"}");
        HttpServer second = server(200, "{\"adminPage\":\"http://127.0.0.1:2222/\"}");
        try {
            Path file = temporary.resolve("server.properties");
            ServerControl control = new ServerControl(file, Map.of(), Duration.ofSeconds(5));
            announce(file, first.getAddress().getPort());
            assertEquals(Optional.of("http://127.0.0.1:1111/"), control.adminPage());
            announce(file, second.getAddress().getPort());
            assertEquals(Optional.of("http://127.0.0.1:2222/"), control.adminPage());
            assertEquals(second.getAddress().getPort(), control.running().orElseThrow().port());
        } finally {
            first.stop(0);
            second.stop(0);
        }
    }
}
