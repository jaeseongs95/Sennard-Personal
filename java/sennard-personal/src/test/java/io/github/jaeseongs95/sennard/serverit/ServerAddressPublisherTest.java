// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.hookclient.ServerAddress;

/**
 * Where the real server says it is ({@code ServerAddressPublisher}): the server binds the port the operating system gives it ({@code server.port: 0}) and writes it, with its process id and
 * start, into {@code server.properties} of its state folder once it is ready. Real Tomcat, real files, the real {@link ProcessHandle}. Case table (input: a server started on a state folder;
 * expected: the file, and what answers at the port it names):
 * <pre>
 * s1  the address   the file is an address of this process (the test's, which runs the server): its pid and its start as the operating system says, and a port that accepts a
 *                   connection and answers HTTP at the hook endpoint's server, not the admin page's; the file is live
 * s2  two servers   two servers at once, each on its own state folder, have their own ports and their own files, and no port is a fixed one
 * s3  stops         a server that stops removes its file; the file of an address that is not its own (another process's, or another start's) is left as it is
 * s4  stale file    a file a killed server left (a process that is gone) is written over when a server starts on that state folder
 * </pre>
 */
class ServerAddressPublisherTest {
    private static HttpURLConnection get(int port, String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(30_000);
        return connection;
    }

    private static Path addressFile(RunningServer server) {
        return server.stateDirectory().resolve(ServerAddress.FILE_NAME);
    }

    @Test
    void theFileNamesThisProcessAndThePortTheServerIsListeningOn() throws Exception {
        try (RunningServer server = RunningServer.start(List.of())) {
            ServerAddress address = ServerAddress.read(addressFile(server)).orElseThrow(() -> new AssertionError("no address file in " + server.stateDirectory()));

            assertEquals(ProcessHandle.current().pid(), address.pid());
            assertEquals(ProcessHandle.current().info().startInstant().orElseThrow(), address.processStart());
            assertTrue(address.live());
            assertEquals(server.port, address.port(), "the main connector's port (local.server.port)");
            assertNotEquals(server.adminPort(), address.port(), "not the admin page's, which is a connector of its own");
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), address.port()), 5_000);
                assertTrue(socket.isConnected());
            }
            HttpURLConnection connection = get(address.port(), "/actuator/health");
            try {
                assertTrue(connection.getResponseCode() >= 100, "an HTTP answer from the server at the announced port");
            } finally {
                connection.disconnect();
            }
        }
    }

    @Test
    void twoServersAtOnceHaveTheirOwnPortsAndFiles() throws Exception {
        try (RunningServer first = RunningServer.start(List.of()); RunningServer second = RunningServer.start(List.of())) {
            ServerAddress one = ServerAddress.read(addressFile(first)).orElseThrow();
            ServerAddress two = ServerAddress.read(addressFile(second)).orElseThrow();

            assertEquals(first.port, one.port());
            assertEquals(second.port, two.port());
            assertNotEquals(one.port(), two.port(), "both are alive, so the system gave them different ports");
            assertNotEquals(first.stateDirectory(), second.stateDirectory());
        }
    }

    @Test
    void aServerThatStopsRemovesItsFile() throws Exception {
        RunningServer server = RunningServer.start(List.of());
        Path file = addressFile(server);
        assertTrue(Files.exists(file));

        server.close();

        assertFalse(Files.exists(file), "the stopped server's address is not left behind");
    }

    @Test
    void anAddressThatIsNotTheServersOwnIsLeftAsItIsWhenTheServerStops() throws Exception {
        Path folder = Files.createTempDirectory("sennard-address-state-");
        try {
            RunningServer server = RunningServer.startAt(folder, List.of());
            Path file = addressFile(server);
            ServerAddress mine = ServerAddress.read(file).orElseThrow();
            // Another server's address, as one that was started over this folder would have written it (another start of the same process id, as a restart is).
            ServerAddress another = new ServerAddress(mine.port() == 65_535 ? 1_024 : mine.port() + 1, mine.pid(), Instant.parse("2020-01-01T00:00:00Z"));
            another.writeAtomically(file);

            server.close();

            assertEquals(Optional.of(another), ServerAddress.read(file), "a file that is not this server's own is not removed");
        } finally {
            RunningServer.deleteTree(folder);
        }
    }

    @Test
    void aFileThatAKilledServerLeftIsWrittenOverByTheNextServer() throws Exception {
        Path folder = Files.createTempDirectory("sennard-address-state-");
        try {
            Process ended = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Instant start = ended.info().startInstant().orElse(Instant.parse("2020-01-01T00:00:00Z"));
            ended.waitFor();
            ServerAddress stale = new ServerAddress(1_024, ended.pid(), start);
            stale.writeAtomically(folder.resolve(ServerAddress.FILE_NAME));
            assertFalse(stale.live(), "the process that wrote it is gone");

            try (RunningServer server = RunningServer.startAt(folder, List.of())) {
                ServerAddress now = ServerAddress.read(addressFile(server)).orElseThrow();

                assertEquals(server.port, now.port());
                assertEquals(ProcessHandle.current().pid(), now.pid());
                assertTrue(now.live());
            }
        } finally {
            RunningServer.deleteTree(folder);
        }
    }
}
