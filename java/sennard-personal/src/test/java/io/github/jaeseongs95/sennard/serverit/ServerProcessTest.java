// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.personal.SennardPersonalApplication;
import io.github.jaeseongs95.sennard.personal.install.ReleaseJarFixture;

/**
 * The built jar's server as a real process, the way the installed server is started: the application's main class on the jar as the class path, no command line of the user's. Paths
 * beyond the Windows ANSI code page reach the server through the environment and through a UTF-8 {@code sennard.yaml}, the two routes left for them.
 *
 * <p>Why Spring Boot's {@code PropertiesLauncher} and not {@code java -jar}: the jar's {@code Start-Class} is the command line ({@code SennardCli}: install, status, start, ...), which does
 * not start a server by itself; the installation starts the server from the extracted application jar with its main class ({@code SennardPersonalApplication}). This test has the built jar
 * as it is (nested jars), which only Boot's launcher can read, and {@code loader.main} names the main class it starts instead of {@code Start-Class}
 * ({@code org.springframework.boot.loader.launch.PropertiesLauncher}, the class the jar carries).
 *
 * <p>The server's port is the operating system's choice at each start ({@code server.port: 0}), so this test chooses none: it waits for the server to write its address into the state
 * folder ({@link ServerAddress}, {@code server.properties}) and reads the port from there, and it sees the file go when the server ends. Every child has {@code -Duser.home},
 * {@code SENNARD_INSTALL_ROOT} and {@code SENNARD_STATE_DIR} of the test's own (the state folder is the case's), so none of them reaches the profile of the user.
 */
class ServerProcessTest {
    private static final Duration START = Duration.ofSeconds(120);
    /** Boot's launcher: reads the nested jars of the built jar and starts the class {@code loader.main} names. */
    private static final String LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher";

    @TempDir
    Path temporary;

    private static Path jar() {
        return Path.of(System.getProperty("sennard.personal.jar"));
    }

    private Process launch(Path jarFile, Map<String, String> environment, Path log) throws IOException {
        Path home = Files.createDirectories(temporary.resolve("home"));
        ProcessBuilder builder = new ProcessBuilder(List.of(ReleaseJarFixture.javaProgram().toString(), "-Xmx" + System.getProperty("sennard.test.childHeap"), "-Duser.home=" + home,
                "-Dloader.main=" + SennardPersonalApplication.class.getName(), "-cp", jarFile.toString(), LAUNCHER));
        // The places of the product, and the temporary folder of the child, are this test's: the server is killed at the end of a case and then cannot delete the folders its Tomcat makes in
        // the temporary folder, which therefore lies in this test's folder, which JUnit deletes (D-0194).
        builder.environment().putAll(ReleaseJarFixture.isolated(home, Map.of("SENNARD_INSTALL_ROOT", temporary.resolve("install").toString())));
        builder.environment().remove("SENNARD_STATE_DIR"); // a case gives its own, or leaves it to the yaml beside the jar
        builder.environment().put("SENNARD_STORE_PREPARE", "false"); // these runs are about the process, not the database
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        return builder.start();
    }

    /**
     * Waits for the server to announce itself in {@code state} and answer its health: the port is what the server wrote, never one this test chose. A process that has already died ends
     * the wait. The file is written when the server is ready, so the health request is made once.
     *
     * @return the port the server announced
     */
    private int awaitHealthy(Process process, Path state, Path log) throws Exception {
        Path file = state.resolve(ServerAddress.FILE_NAME);
        Instant deadline = Instant.now().plus(START);
        while (Instant.now().isBefore(deadline)) {
            assertTrue(process.isAlive(), () -> "the server exited early: " + read(log));
            Optional<ServerAddress> address = ServerAddress.read(file).filter(ServerAddress::live);
            if (address.isPresent()) {
                assertEquals(address.get().pid(), process.pid(), "the address is the one of the process this test started");
                RawHttp.Response health = RawHttp.exchange(address.get().port(), "GET", "/actuator/health", Map.of(), null);
                assertEquals(200, health.status(), () -> "the announced server is healthy: " + health.text() + "\n" + read(log));
                return address.get().port();
            }
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
        throw new AssertionError("the server did not announce itself: " + read(log));
    }

    private static String read(Path log) {
        try {
            return Files.readString(log, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "(no log: " + unreadable + ")";
        }
    }

    private static void initialize(int port) throws IOException {
        byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}").getBytes(StandardCharsets.UTF_8);
        RawHttp.Response response = RawHttp.exchange(port, "POST", "/mcp",
                Map.of("Accept", "application/json, text/event-stream", "Content-Type", "application/json", "Content-Length", Integer.toString(body.length)), body);
        assertEquals(200, response.status(), response.text());
        assertTrue(response.text().contains("sennard"), response.text());
    }

    /** The server is asked to end and ends with exit code 0; and the address it announced goes with it, so that no client takes the port of a server that is gone for a server. */
    private void shutDown(int port, Process process, Path state) throws Exception {
        assertEquals(200, RawHttp.exchange(port, "POST", "/actuator/shutdown", Map.of("Content-Length", "0"), null).status());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the server ended after /actuator/shutdown");
        assertEquals(0, process.exitValue());
        assertFalse(Files.exists(state.resolve(ServerAddress.FILE_NAME)), "the server removed the address it announced when it ended");
    }

    /** The log is what is left of a server the OS started, and a user may attach it to a report: requests that fail must not put their content in it. */
    @Test
    void theLogFileIsInTheStateFolderAndHoldsNoRequestContent() throws Exception {
        Path state = temporary.resolve("log-state");
        Path log = temporary.resolve("log.log");
        String secret = "sentinel-" + UUID.randomUUID();
        Process process = launch(jar(), Map.of("SENNARD_STATE_DIR", state.toString()), log);
        try {
            int port = awaitHealthy(process, state, log);
            Map<String, String> json = Map.of("Content-Type", "application/json");
            for (String body : List.of("{\"session_id\":\"" + secret + "\",\"prompt\":\"" + secret + "\"}", "{\"session_id\":\"" + secret + "\",")) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                for (String event : List.of("SessionStart", "UserPromptSubmit", "NotAnEvent")) {
                    RawHttp.exchange(port, "POST", "/hooks/v1/claude/" + event, withLength(json, bytes), bytes);
                }
            }
            byte[] call = ("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"checkpoint_context\",\"arguments\":{\"text\":\"" + secret + "\"}}}").getBytes(StandardCharsets.UTF_8);
            Map<String, String> mcp = new java.util.HashMap<>(withLength(json, call));
            mcp.put("Accept", "application/json, text/event-stream");
            mcp.put("Authorization", "Bearer " + secret);
            RawHttp.exchange(port, "POST", "/mcp", mcp, call);
            // Bodies that are not JSON: a parser's error names the token it stopped at, so the secret is a bare word where a value belongs.
            String word = "sentinel" + UUID.randomUUID().toString().replace("-", "");
            byte[] broken = ("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":" + word + "}").getBytes(StandardCharsets.UTF_8);
            Map<String, String> brokenMcp = new java.util.HashMap<>(withLength(json, broken));
            brokenMcp.put("Accept", "application/json, text/event-stream");
            List<Integer> statuses = new java.util.ArrayList<>();
            statuses.add(RawHttp.exchange(port, "POST", "/mcp", brokenMcp, broken).status());
            for (String path : List.of("/hooks/v1/claude/UserPromptSubmit", "/hooks/v1/claude/SessionStart/wake", "/actuator/sennard", "/actuator/shutdown")) {
                statuses.add(RawHttp.exchange(port, "POST", path, withLength(json, broken), broken).status());
            }
            assertTrue(statuses.stream().allMatch(status -> status >= 400), "every broken body is refused: " + statuses);
            shutDown(port, process, state);
            Path file = state.resolve("logs/sennard-server.log");
            assertTrue(Files.exists(file), () -> "the log file is created at start: " + read(log));
            assertFalse(read(file).contains(secret), () -> read(file));
            assertFalse(read(file).contains(word), () -> read(file));
            assertFalse(read(log).contains(word), () -> "the console the OS discards holds the same: " + read(log));
        } finally {
            process.destroyForcibly();
        }
    }

    /** A server this test run starts shows no tray icon on the user's desktop: the build's environment (SENNARD_TRAY_ENABLED=false) reaches the child, and the server says so in its log. */
    @Test
    void aServerStartedByATestRunShowsNoTrayIcon() throws Exception {
        Path state = temporary.resolve("tray-state");
        Path log = temporary.resolve("tray.log");
        Process process = launch(jar(), Map.of("SENNARD_STATE_DIR", state.toString(), "LOGGING_LEVEL_IO_GITHUB_JAESEONGS95_SENNARD_PERSONAL_TRAY", "INFO"), log);
        try {
            int port = awaitHealthy(process, state, log);
            shutDown(port, process, state);
            String file = read(state.resolve("logs/sennard-server.log"));
            assertTrue(file.contains("No tray icon: the setting sennard.tray.enabled is false."), file);
        } finally {
            process.destroyForcibly();
        }
    }

    private static Map<String, String> withLength(Map<String, String> headers, byte[] body) {
        Map<String, String> all = new java.util.HashMap<>(headers);
        all.put("Content-Length", Integer.toString(body.length));
        return all;
    }

    @Test
    void aStateDirectoryBeyondTheAnsiCodePageWorksFromTheEnvironment() throws Exception {
        Path state = temporary.resolve("sennard-é-😀-state");
        Path log = temporary.resolve("env.log");
        Process process = launch(jar(), Map.of("SENNARD_STATE_DIR", state.toString()), log);
        try {
            int port = awaitHealthy(process, state, log);
            assertTrue(Files.isDirectory(state), "the directory exists under its exact name: " + state);
            initialize(port);
            shutDown(port, process, state);
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void aStateDirectoryBeyondTheAnsiCodePageWorksFromAUtf8YamlBesideTheJar() throws Exception {
        Path state = temporary.resolve("yaml-é-😀-state");
        Path installation = temporary.resolve("installation");
        Files.createDirectories(installation);
        Path copiedJar = Files.copy(jar(), installation.resolve("sennard-personal.jar"));
        Files.writeString(installation.resolve("sennard.yaml"), "sennard:\n  state-dir: '" + state + "'\n", StandardCharsets.UTF_8);
        Path log = temporary.resolve("yaml.log");
        Process process = launch(copiedJar, Map.of(), log);
        try {
            int port = awaitHealthy(process, state, log);
            assertTrue(Files.isDirectory(state), "the directory exists under its exact name: " + state + "\n" + read(log));
            initialize(port);
            shutDown(port, process, state);
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void aNonLoopbackBindAddressRefusesToStart() throws Exception {
        Path log = temporary.resolve("bind.log");
        Path state = temporary.resolve("bind");
        Process process = launch(jar(), Map.of("SERVER_ADDRESS", "0.0.0.0", "SENNARD_STATE_DIR", state.toString()), log);
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the process ended on its own");
            assertFalse(process.exitValue() == 0, "a refused start is a failure, not a clean exit");
        } finally {
            process.destroyForcibly();
        }
        assertFalse(Files.exists(state.resolve(ServerAddress.FILE_NAME)), "a server that refused to start announced no address: " + read(log));
    }
}
