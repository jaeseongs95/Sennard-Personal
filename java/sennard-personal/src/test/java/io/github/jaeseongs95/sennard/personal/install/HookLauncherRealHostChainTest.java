// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.jaeseongs95.sennard.hookclient.HookClientCall;
import io.github.jaeseongs95.sennard.hookclient.HookClientCall.FailureAnswer;
import io.github.jaeseongs95.sennard.hookclient.HookHeaders;
import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.server.hook.HookInstallation;

/**
 * The real hook client of an installation (the program a host's hook command runs: the user's {@code java} and the client's jar, {@link HookClientCommand#hookProgram}) in a real host
 * chain. Case (target: the client's {@code hook claude SessionStart --bind-host}; input: a host stand-in process, a Java program that runs the command as its child with the hook's
 * JSON on stdin; stand-in: only the server, the JDK's own {@code HttpServer} on a loopback socket that the operating system gave a port, announced in the address file the command's
 * {@code --server-file=} names; expected: the {@code X-Sennard-Host-Pids} list the server received holds the pid of the host stand-in, followed by the host's own parent, so the list
 * is the parent chain in order; the client answered the server's body with exit 0).
 *
 * <pre>
 * r1  direct        host -> client (a host that takes a program and its arguments: Claude's exec form)
 *                   the host's pid is the first candidate: nothing is between the host and the client, and the client's JVM is the only java process below the host
 * r2  cmd (Windows) host -> cmd.exe /c &lt;command string&gt; -> client (a host that takes a string and runs it with the command interpreter)
 *                   the first candidate is the cmd.exe the host started, the second is the host; the client's JVM is the only java process below the host
 * r3  sh (not Win)  host -> sh -c &lt;command string&gt; -> client
 *                   the host is among the candidates, and every candidate before it is a shell that is below the host (a shell may run its last command in place and then
 *                   there is none); the client's JVM is the only java process below the host
 * </pre>
 * No program of another kind stands between the host and the client: the processes above it are the host's and the shell's. The other cases of the hook client program (it starts,
 * its options, the failure answers) are {@code HookLauncherProcessTest}'s.
 */
class HookLauncherRealHostChainTest {
    private static final String ANSWER = "{\"hookSpecificOutput\":{\"additionalContext\":\"from the server\"}}";
    private static final Set<String> SHELLS = Set.of("cmd.exe", "sh", "bash", "dash", "zsh");

    @TempDir
    Path temporary;

    private record Received(String method, String path, String candidates, String body) {
    }

    /** A process below the host stand-in at the moment the server answers. */
    private record Below(long pid, long parent, String command) {
        /** The file name of the program, lower case; empty when the OS gives no command of the process (it is then no program this test looks for). */
        String program() {
            return command.isEmpty() ? "" : Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void theHostIsTheFirstProcessAboveTheClientWhenTheHostRunsItDirectly() throws Exception {
        Chain chain = run(HookLauncherRealHostChainTest::direct);
        assertEquals(0, chain.at(), "the host stand-in is the nearest process above the client: " + chain.context());
        assertEquals(ProcessHandle.current().pid(), chain.candidates().get(chain.at() + 1), "after the host comes its own parent: " + chain.context());
        assertOnlyTheClientsJvm(chain);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theHostIsAboveTheCmdShellThatRunsTheClientOnWindows() throws Exception {
        Chain chain = run((program, call) -> List.of("cmd.exe", "/c", commandString(program, call)));
        assertEquals(1, chain.at(), "the cmd.exe the host started is between the host and the client: " + chain.context());
        Below shell = chain.below().stream().filter(process -> process.pid() == chain.candidates().get(0)).findFirst().orElseThrow();
        assertEquals("cmd.exe", shell.program(), chain.context());
        assertEquals(chain.host(), shell.parent(), "the shell is the host's child: " + chain.context());
        assertEquals(ProcessHandle.current().pid(), chain.candidates().get(chain.at() + 1), "after the host comes its own parent: " + chain.context());
        assertOnlyTheClientsJvm(chain);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void theHostIsAmongTheCandidatesAndEveryProcessBeforeItIsTheShellItRanOnUnix() throws Exception {
        Chain chain = run((program, call) -> List.of("sh", "-c", commandString(program, call)));
        assertTrue(chain.at() <= 1, "at most the shell is between the host and the client: " + chain.context());
        for (Long pid : chain.candidates().subList(0, chain.at())) {
            Below shell = chain.below().stream().filter(process -> process.pid() == pid).findFirst().orElseThrow(() -> new AssertionError(pid + " is below the host: " + chain.context()));
            assertTrue(SHELLS.contains(shell.program()), pid + " is a shell: " + chain.context());
        }
        assertEquals(ProcessHandle.current().pid(), chain.candidates().get(chain.at() + 1), "after the host comes its own parent: " + chain.context());
        assertOnlyTheClientsJvm(chain);
    }

    /**
     * The client is the one program of its own below the host: the {@code java} that runs it, not a launcher that starts another program (a {@code java} is told by the file name,
     * {@code java} or {@code java.exe}: no OS is asked).
     */
    private static void assertOnlyTheClientsJvm(Chain chain) {
        long javas = chain.below().stream().filter(process -> process.program().startsWith("java")).count();
        assertEquals(1, javas, "the client's JVM is the only java process below the host: " + chain.context());
    }

    /** What one run of the host stand-in showed. */
    private record Chain(long host, List<Long> candidates, int at, List<Below> below, String context) {
    }

    /** How a host runs the command: the words the host stand-in starts, from the installed hook command's words and the call's arguments. */
    @FunctionalInterface
    private interface Host {
        List<String> start(List<String> program, List<String> call);
    }

    /** A host that takes a program and its arguments (Claude's exec form): the words as they are. */
    private static List<String> direct(List<String> program, List<String> call) {
        List<String> words = new ArrayList<>(program);
        words.addAll(call);
        return words;
    }

    /** The command string a host that takes one gives its shell: the installed definition's own rule (the words with a space quoted). */
    private static String commandString(List<String> program, List<String> call) {
        // The relay's program is not run here; the installation names two programs, and the hook's is the one whose command line is made.
        return new HookInstallation(program, program).hookCommandLine(call);
    }

    /** The call's own arguments: the server is not among them, it is found through the address file the program names. */
    private static List<String> hookArguments() {
        return HookClientCall.of("claude", "SessionStart", FailureAnswer.FAIL).bindingHost().withTimeout(60).arguments();
    }

    /** Runs the host stand-in, which runs the installed hook client's command the way {@code host} says, against the stand-in server, and reads the chain from what the server saw. */
    private Chain run(Host how) throws Exception {
        List<Received> received = new CopyOnWriteArrayList<>();
        List<List<Below>> belowTheHost = new CopyOnWriteArrayList<>();
        AtomicReference<Process> started = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> answer(exchange, received, belowTheHost, started));
        server.start();
        try {
            // The stand-in's port is the one the operating system gave it; the client finds it in the address file, as it finds the real server's.
            Path serverFile = temporary.resolve("state").resolve(ServerAddress.FILE_NAME);
            Files.createDirectories(serverFile.getParent());
            InstalledHookCommand.announceThisProcess(serverFile, server.getAddress().getPort());
            InstalledVersion version = InstalledHookCommand.hookClientOnly(temporary.resolve("install-root"));
            List<String> program = InstalledHookCommand.hookProgram(ReleaseJarFixture.javaProgram(), version, serverFile);
            Path log = temporary.resolve("host.log");
            Path errors = temporary.resolve("host.err");
            Path home = temporary.resolve("home");
            List<String> command = new ArrayList<>(ReleaseJarFixture.java(home, "-Xmx" + System.getProperty("sennard.test.childHeap"), "-cp", System.getProperty("sennard.test.classpath"),
                    HookLauncherHostStandIn.class.getName()));
            command.addAll(how.start(program, hookArguments()));
            ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(log.toFile()).redirectError(errors.toFile());
            // The places of the product are this test's, for the stand-in and for the client it runs (the server file is the client's only need of them).
            builder.environment().putAll(ReleaseJarFixture.isolated(home, Map.of("SENNARD_INSTALL_ROOT", temporary.resolve("install-root").toString(), "SENNARD_STATE_DIR", serverFile.getParent().toString())));
            Process host = builder.start();
            started.set(host);

            boolean ended = host.waitFor(3, TimeUnit.MINUTES);
            if (!ended) {
                host.descendants().forEach(ProcessHandle::destroyForcibly);
                host.destroyForcibly();
            }
            String output = Files.readString(log, StandardCharsets.UTF_8) + "\n--- stderr ---\n" + Files.readString(errors, StandardCharsets.UTF_8);
            assertTrue(ended, "the host stand-in did not end in 3 minutes: " + output);
            assertEquals(0, host.exitValue(), output);
            assertTrue(output.contains("hookExit=0\nhookStdout=" + ANSWER) || output.contains("hookExit=0\r\nhookStdout=" + ANSWER), output);

            assertEquals(1, received.size(), output);
            Received request = received.get(0);
            assertEquals("POST", request.method());
            assertEquals("/hooks/v1/claude/SessionStart", request.path());
            assertTrue(request.body().contains("launcher-chain-1"), request.body());
            List<Below> below = belowTheHost.get(0);
            assertNotNull(request.candidates(), "the client sent no " + HookHeaders.HOST_PIDS + "; below the host at the request: " + below);
            List<Long> candidates = Arrays.stream(request.candidates().split(",")).map(String::strip).map(Long::parseLong).toList();
            String context = "candidates " + candidates + ", host stand-in " + host.pid() + ", below the host at the request: " + below;
            int at = candidates.indexOf(host.pid());
            assertTrue(at >= 0, "the host stand-in is among the candidates: " + context);
            return new Chain(host.pid(), candidates, at, below, context);
        } finally {
            server.stop(0);
        }
    }

    /** Records the request and what runs below the host stand-in at that moment (the client is waiting for this very answer), then answers. */
    private static void answer(HttpExchange exchange, List<Received> received, List<List<Below>> belowTheHost, AtomicReference<Process> started) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            for (int i = 0; i < 200 && started.get() == null; i++) {
                Thread.sleep(10); // the test's thread has not yet stored the process it just started
            }
            belowTheHost.add(started.get().descendants().map(process -> new Below(process.pid(), process.parent().map(ProcessHandle::pid).orElse(-1L), process.info().command().orElse(""))).toList());
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), exchange.getRequestHeaders().getFirst(HookHeaders.HOST_PIDS),
                    new String(body, StandardCharsets.UTF_8)));
            byte[] answer = ANSWER.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
