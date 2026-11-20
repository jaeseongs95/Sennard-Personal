// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.personal.ReleasedFolder;
import tools.jackson.databind.JsonNode;

/**
 * The release jar run as a user would run it: copied into a folder whose name is outside ASCII and started there by its file name, {@code java -jar sennard-<version>.jar <command>},
 * with install, status, start, stop, upgrade and uninstall, which is what the "Server package" workflow's jar is checked with (the Gradle task {@code jarRun}; it is not part of
 * {@code test}). The test makes the folder itself, from letters the JDK's {@code native.encoding} can write ({@link ReleaseJarFixture#nonAsciiLetters}; a PC whose encoding can write
 * none fails the test and does not skip it).
 *
 * <p>Where a non-ASCII path may be: the Windows {@code java} launcher reads its command line in the platform's encoding, so a path outside it would reach the program as other
 * letters. The program is therefore never given the folder as an argument: the jar is named by its file name and the command runs <em>in</em> the folder (its working directory), and
 * the installation's folder and the state folder come in the environment ({@code SENNARD_INSTALL_ROOT}, {@code SENNARD_STATE_DIR}), which the child gets as text and not through the command
 * line. The one path that is an argument is the home folder ({@code -Duser.home}), which is the test's own ASCII-only folder (the test checks that). Nothing is written outside the test's folders: the home, the
 * installation, the state folder and the host plugins' places are all the test's ({@link ReleaseJarFixture#isolated}). The server's port is the operating system's choice at each start, so
 * every port here is read from the address file the server wrote ({@link ServerAddress}). The runner has no business database and the server is given none, so the store waits as
 * NOT_CONFIGURED: it connects to nothing and prepares nothing, the server is up and MCP answers.
 *
 * <p>No case asserts a time: the commands are waited for with a deadline that only stops a hung process.
 */
@Tag("release-jar")
class ReleaseJarRunTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"server-package\",\"version\":\"1\"}}}";
    private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";

    @TempDir
    Path temporary;

    private Path home;
    private Path state;
    private Map<String, String> environment;

    /** A server a failed case left running must not outlive it, and its folders must be let go of before JUnit deletes the temporary folder. */
    @AfterEach
    void endTheServerAndReleaseTheFolders() throws Exception {
        if (state != null) {
            ReleaseJarFixture.stopServer(state.resolve(ServerAddress.FILE_NAME));
        }
        ReleasedFolder.await(temporary);
    }

    private record Answer(int code, String body) {
    }

    /** {@code java -Duser.home=<home> -jar <jarName> <arguments>} in {@code directory}: the jar is named by its file name, relative to the folder the command runs in. */
    private ReleaseJarFixture.Run run(Path directory, String jarName, String... arguments) throws Exception {
        List<String> words = new ArrayList<>(List.of("-jar", jarName));
        words.addAll(List.of(arguments));
        return ReleaseJarFixture.run(ReleaseJarFixture.java(home, words.toArray(String[]::new)), directory, environment);
    }

    private JsonNode status(Path directory, String jarName) throws Exception {
        return ReleaseJarFixture.statusJson(run(directory, jarName, "status"));
    }

    private static List<String> versionsOf(JsonNode status) {
        List<String> versions = new ArrayList<>();
        status.path("versions").forEach(version -> versions.add(version.asString()));
        return versions;
    }

    private static Answer post(ServerAddress address, String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(address.baseUrl().resolve("/mcp")).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-11-25").POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return new Answer(response.statusCode(), response.body());
    }

    private static void expect(String what, Answer answer, String contains) {
        assertEquals(200, answer.code(), what + ": HTTP " + answer.code() + " " + answer.body());
        assertTrue(answer.body().contains(contains), what + ": " + answer.body());
    }

    /** The server at the announced port is a Sennard server: it is healthy and its MCP endpoint answers {@code initialize} and lists its tools. */
    private static void assertServerAnswers(ServerAddress address) throws IOException, InterruptedException {
        assertEquals(200, ReleaseJarFixture.serverHealth(address));
        expect("initialize", post(address, INITIALIZE), "\"sennard\"");
        expect("tools/list", post(address, TOOLS_LIST), "\"plan_workflow\"");
    }

    @Test
    void theReleaseJarInstallsRunsAndUpgradesFromANonAsciiFolderByItsFileName() throws Exception {
        String letters = ReleaseJarFixture.nonAsciiLetters();
        home = temporary.resolve("home");
        Files.createDirectories(home);
        assertTrue(home.toString().chars().allMatch(letter -> letter < 0x80), "the home folder is the one path that is given as an argument, so it is ASCII-only: " + home);
        String version = ReleaseJarFixture.version();
        String jarName = InstalledVersion.jarName(version);
        assertTrue(jarName.chars().allMatch(letter -> letter < 0x80), jarName);
        // The folder the user downloaded the jar into, named outside ASCII; the installation and the state folder are inside it and named so too.
        Path folder = temporary.resolve("release-" + letters);
        Files.createDirectories(folder);
        assertTrue(folder.toString().chars().anyMatch(letter -> letter > 127), "the folder is named outside ASCII: " + folder);
        Files.copy(ReleaseJarFixture.releaseJar(), folder.resolve(jarName));
        Path root = folder.resolve("install-" + letters);
        state = folder.resolve("state-" + letters);
        Path addressFile = state.resolve(ServerAddress.FILE_NAME);
        environment = ReleaseJarFixture.isolated(home, Map.of("SENNARD_INSTALL_ROOT", root.toString(), "SENNARD_STATE_DIR", state.toString()));

        // ---- install
        ReleaseJarFixture.Run install = run(folder, jarName, "install");
        assertEquals(0, install.exitCode(), install.stdout());
        // Printed as UTF-8, whatever the code page of the PC is.
        assertTrue(install.stdout().contains(root.toString()), "the installation folder is printed outside ASCII as it is: " + install.stdout());
        assertFalse(install.stdout().contains("warning: AOT"), "the AOT training did not fail: " + install.stdout());
        InstalledVersion installed = new InstalledVersion(root.resolve(InstalledVersion.VERSIONS).resolve(version));
        assertTrue(Files.isRegularFile(installed.appJar()), install.stdout());
        JvmSettingsFile.Content jvm = JvmSettingsFile.read(installed.jvmSettingsFile()).orElseThrow();
        assertTrue(Files.size(installed.aotCache(jvm.preset().collector())) > 0, "sennard install left no AOT cache of the server in " + installed.folder());
        assertTrue(Files.size(installed.folder().resolve(JvmOptions.HOOK_AOT_CACHE_FILE)) > 0, "sennard install left no AOT cache of the hook client in " + installed.folder());
        ServerAddress first = ReleaseJarFixture.liveServer(addressFile);
        assertServerAnswers(first);

        // ---- status, from the jar as downloaded and from the installed jar (the command the install tells the user to run)
        JsonNode running = status(folder, jarName);
        assertEquals(root.toString(), running.path("installRoot").asString(), "the folder outside ASCII comes back as it was written: " + running);
        assertTrue(running.path("healthy").booleanValue(), running.toString());
        assertEquals(first.port(), running.path("port").intValue());
        assertEquals(List.of(version), versionsOf(running));
        assertEquals("java -jar " + installed.appJar(), running.path("command").asString(), running.toString());
        // A non-ASCII working folder, the installed jar by its file name: the same installation, the same answer.
        JsonNode fromInstalled = status(installed.folder(), jarName);
        assertEquals(running.path("command").asString(), fromInstalled.path("command").asString());
        assertEquals(first.port(), fromInstalled.path("port").intValue());

        // ---- start while the server runs starts nothing; stop ends it; start runs it again
        ReleaseJarFixture.Run startRunning = run(folder, jarName, "start");
        assertEquals(0, startRunning.exitCode(), "sennard start failed while the server was running: " + startRunning.stdout());
        assertEquals(first, ReleaseJarFixture.liveServer(addressFile), "no second server was started");
        ReleaseJarFixture.Run stop = run(folder, jarName, "stop");
        assertEquals(0, stop.exitCode(), stop.stdout());
        assertFalse(first.live(), "the server's process is gone");
        assertFalse(Files.exists(addressFile), "the server deleted the file that announced it");
        assertFalse(status(folder, jarName).path("healthy").booleanValue(), "sennard status reports the stopped server as not healthy");
        ReleaseJarFixture.Run start = run(folder, jarName, "start");
        assertEquals(0, start.exitCode(), start.stdout());
        ServerAddress second = ReleaseJarFixture.liveServer(addressFile);
        assertNotEquals(first.pid(), second.pid(), "a new process");
        assertServerAnswers(second);

        // ---- upgrade: the jar of a higher version, in the same folder, run by its file name
        String newer = ReleaseJarFixture.newerVersion();
        String newerName = InstalledVersion.jarName(newer);
        ReleaseJarFixture.releaseJarWithVersion(folder, newer);
        byte[] jvmSettingsBefore = Files.readAllBytes(installed.jvmSettingsFile());
        ReleaseJarFixture.Run upgrade = run(folder, newerName, "upgrade");
        assertEquals(0, upgrade.exitCode(), upgrade.stdout());
        InstalledVersion upgraded = new InstalledVersion(root.resolve(InstalledVersion.VERSIONS).resolve(newer));
        assertTrue(Files.isRegularFile(upgraded.appJar()), upgrade.stdout());
        assertFalse(Files.exists(installed.folder()), "the old folder is gone only after the new server answered");
        assertArrayEquals(jvmSettingsBefore, Files.readAllBytes(upgraded.jvmSettingsFile()), "the upgrade keeps the saved preset");
        assertFalse(upgrade.stdout().contains("warning: AOT"), "the AOT training did not fail: " + upgrade.stdout());
        ServerAddress third = ReleaseJarFixture.liveServer(addressFile);
        assertFalse(second.live(), "the old version's server ended");
        assertServerAnswers(third);
        JsonNode upgradedStatus = status(upgraded.folder(), newerName);
        assertEquals("java -jar " + upgraded.appJar(), upgradedStatus.path("command").asString(), "the command is the newest version's jar");
        assertEquals(List.of(newer), versionsOf(upgradedStatus));
        assertEquals(third.port(), upgradedStatus.path("port").intValue());

        // ---- uninstall: refused from the installed jar, done from the jar as downloaded
        ReleaseJarFixture.Run fromInside = run(upgraded.folder(), newerName, "uninstall");
        assertEquals(1, fromInside.exitCode(), fromInside.stdout());
        assertTrue(fromInside.stdout().contains("UNINSTALL_FROM_INSTALLED_COPY"), fromInside.stdout());
        assertEquals(third, ReleaseJarFixture.liveServer(addressFile), "nothing was changed: the server still runs");
        ReleaseJarFixture.Run uninstall = run(folder, newerName, "uninstall");
        assertEquals(0, uninstall.exitCode(), uninstall.stdout());
        assertFalse(Files.exists(root), "the program is gone: " + uninstall.stdout());
        assertFalse(third.live(), "the server's process is gone");
        assertFalse(Files.exists(addressFile), "the server deleted the file that announced it");
        assertTrue(Files.isDirectory(state), "the state stays");
    }
}
