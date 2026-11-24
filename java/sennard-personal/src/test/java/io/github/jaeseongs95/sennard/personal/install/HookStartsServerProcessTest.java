// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.jvm.Collector;
import io.github.jaeseongs95.sennard.hookclient.HookClientCall;
import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.personal.ReleasedFolder;
import io.github.jaeseongs95.sennard.server.hook.HookInstallation;
import io.github.jaeseongs95.sennard.server.hook.HostHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.HostRegistrationFile;
import io.github.jaeseongs95.sennard.server.hook.host.AntigravityHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.host.ClaudeHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.host.CodexHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.host.GeminiHookAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A session start's hook starts the installed server when nothing listens, with the real release, real processes and real ports: the release the build made is copied to where an
 * installation keeps a version ({@code <root>/versions/<version>}, {@link ReleaseJarFixture#installedCopy}), and the hook is run as a host runs it, from the command of the generated
 * definition, with its standard streams as pipes (a server that kept the hook's pipe would keep the host waiting). The command is what an installed definition names
 * ({@link HookClientCommand#hookProgram}): the {@code java} of the JDK running the hook client's jar, with {@code --server-file=} (where the server announces its address) and
 * {@code --jar=} (the installed Sennard jar). The client is JDK only and has no installer; when it finds no live server at a session start ({@code --start-server=<seconds>}) it runs the
 * installed jar ({@code java -XX:+UseSerialGC ... -jar <jar> start --within=<seconds>}, {@code JarServerStart}), which starts the server and waits for it, and then the client sends
 * the event once. The server's port is the operating system's choice at each start, so it is never chosen here: it is read from the address file the server writes in its state
 * folder ({@link ServerAddress}). The installation, its state folder and its address file are this test's; every child JVM gets {@code SENNARD_INSTALL_ROOT} and
 * {@code SENNARD_STATE_DIR} (and the one the test starts itself {@code -Duser.home}), so no run reaches an installation of the PC.
 *
 * <p>The processes a hook made are told by their place below it: a hook's children are what it started itself (the command that starts the server, and, for a session start the server
 * accepted a wake registration for, the native wake worker, {@code java -jar <jar> native-wake-worker <host> <event>}, which the hook leaves running as its own child), and the children of
 * the command that starts the server are the server (the product starts it as its own child; it outlives its parent). So the servers a hook started are the processes two levels below it;
 * a process one level below it is not told to be a server or a worker (the JDK gives no command line of another process on every OS, and whether the sampler sees a worker depends on
 * timing). Only {@code java} processes are counted: Windows may give a console program a console host of its own.
 *
 * <p>Case table (installation, server, hook; expected):
 * <pre>
 * installed, no server      claude SessionStart   -> exit 0, the server's answer on stdout, the event recorded by the server (list_session_status),
 *                                                    the server alive after the hook ended, the hook's pipes at EOF (the client, the command it ran and the server
 *                                                    hold none of them)
 * installed, server running claude SessionStart   -> the same answer; the hook started no server (no process two levels below it; the native wake worker the server's
 *                                                    wake registration makes the hook leave running is its direct child, seen or not), the server is the one that ran (its
 *                                                    address file is unchanged), the start's lock file is never made
 * installed, no server      two SessionStart at once -> both answered; the server processes seen are as many as one start makes (sampled every 20 ms)
 * application jar damaged   claude SessionStart   -> exit 2, the guidance on stderr, nothing on stdout, no server process, the lock is free (the command that starts the server fails:
 *                                                    SERVER_NOT_STARTED, answered with the definition's failure answer)
 * server slow (limit 1 s)   claude SessionStart   -> exit 2, the guidance, the lock is free; the server being started is not killed and comes up
 * application jar gone      claude SessionStart   -> exit 2, the guidance, no server process, no lock file, no jar made
 * installed, no server      agy PreToolUse, codex SessionEnd, gemini AfterTool -> the answers they had before (the reason code {@code Sennard hook failed: SERVER_NOT_REACHABLE}
 *                                                    on stderr for the two that fail); the hook made no process, no lock file, nothing listens
 * installed by sennard install  claude SessionStart   -> exit 0, the server's answer on stdout (the installation has the AOT caches and the JVM preset the install chose, and the
 *                                                    hook client has its own cache sennard-hook.aot)
 * </pre>
 * The time a start takes is measured on a real installation five times by {@link #measuresTheStartUntilTheEventIsAnsweredOnARealInstallation}, which the
 * {@code perfBaseline} task runs. No case asserts a time: the hook is waited for with a deadline that only stops a hung process, and what the cases show is what happened
 * (the answer, the processes, the lock).
 */
class HookStartsServerProcessTest {
    /** The tag of the measurements the default {@code test} task leaves out and {@code :sennard-personal:perfBaseline} runs (sennard-personal/build.gradle.kts, PerfBaselineMeasurement.TAG). */
    private static final String PERF_BASELINE = "perf-baseline";
    private static final Logger LOG = LoggerFactory.getLogger(HookStartsServerProcessTest.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String VERSION = ReleaseJarFixture.version();
    /** The identifier the recorded host inputs carry; each case puts its own in. */
    private static final String FIXTURE_SESSION = "10000001-1111-4111-8111-000000000001";
    private static final String GUIDANCE = "Sennard server is not reachable. Check with: java -jar <the Sennard jar in the install folder> status. After it is running, restart this session; tools do not attach later.";
    /** The lock file of the start (StartLock.FILE) in the state folder. */
    private static final String LOCK_FILE = StartLock.FILE;
    private static final ExecutorService READERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    static Path shared;

    private static Path root;
    private static Path home;
    private static InstalledVersion installed;

    /** The address files of the servers this case's states hold: what ends them when the case is over. */
    private final List<Path> addressFiles = new ArrayList<>();
    private Path state;
    private Path addressFile;
    private Map<String, String> environment;
    /** The version whose jars the generated definitions run: the shared installation's, or another one a case made. */
    private InstalledVersion commandVersion;

    @BeforeAll
    static void installTheBuiltRelease() throws IOException {
        root = shared.resolve("install-root");
        home = shared.resolve("home");
        installed = ReleaseJarFixture.installedCopy(root, VERSION);
        assertTrue(Files.isRegularFile(installed.appJar()), "the installed application jar: " + installed.appJar());
    }

    @AfterAll
    static void stopTheReaders() {
        READERS.shutdownNow();
        // The servers of the cases are gone, but Windows can keep their program files held for a while (measured in EngineeringRunLifecycleTest's folders and here): JUnit
        // deletes the temporary folder right after this method.
        ReleasedFolder.await(shared);
    }

    /** A server a case started must not outlive it: it is asked to end (which also ends its store), and only what is still there after that is killed. */
    @AfterEach
    void endTheServers() throws Exception {
        List<ProcessHandle> servers = new ArrayList<>();
        for (Path file : addressFiles) {
            ServerAddress.read(file).filter(ServerAddress::live).flatMap(address -> ProcessHandle.of(address.pid())).ifPresent(servers::add);
        }
        try {
            addressFiles.forEach(file -> new ServerControl(file, Map.of()).shutdown());
        } finally {
            servers.forEach(ProcessHandle::destroyForcibly);
            awaitExit(servers);
        }
    }

    /** The processes are gone: their files can be deleted (Windows keeps a running program's files) and their pids are not mistaken for a later start's. */
    private static void awaitExit(List<ProcessHandle> handles) throws Exception {
        for (ProcessHandle handle : handles) {
            handle.onExit().get(60, TimeUnit.SECONDS);
        }
    }

    /** This case's installation settings: its own state folder (and so its own address file) and the store only where the case needs the event recorded. */
    private void configure(boolean store) throws IOException {
        useState(shared.resolve("state-" + UUID.randomUUID()), root);
        ReleaseJarFixture.writeSettings(installed, state, store);
        commandVersion = installed;
    }

    /** A state folder and the environment of the commands that run against it and the installation at {@code installRoot}: the places of the product are all this test's. */
    private void useState(Path stateFolder, Path installRoot) throws IOException {
        state = Files.createDirectories(stateFolder);
        addressFile = state.resolve(ServerAddress.FILE_NAME);
        addressFiles.add(addressFile);
        environment = ReleaseJarFixture.isolated(home, Map.of("SENNARD_INSTALL_ROOT", installRoot.toString(), "SENNARD_STATE_DIR", state.toString()));
    }

    // ------------------------------------------------------------------------------------------------------------------ the hooks and their processes

    /** The hook definition of a host this installation's hooks are generated as: the real adapter and this installation's programs ({@link HookClientCommand}). */
    private JsonNode definition(HostHookAdapter adapter) {
        HookInstallation installation = InstalledHookCommand.installation(ReleaseJarFixture.javaProgram(), commandVersion, addressFile);
        return adapter.registrationFiles(installation).stream().filter(file -> file.relativePath().getFileName().toString().equals("hooks.json")).findFirst()
                .map(HostRegistrationFile::content).orElseThrow(() -> new AssertionError("the adapter registers no hooks.json"));
    }

    private List<String> claudeSessionStart() {
        JsonNode handler = definition(new ClaudeHookAdapter(JSON)).path("hooks").path("SessionStart").path(0).path("hooks").path(0);
        List<String> argv = new ArrayList<>(List.of(handler.path("command").stringValue()));
        handler.path("args").forEach(argument -> argv.add(argument.stringValue()));
        return argv;
    }

    /**
     * The program and arguments of a command-string hook, the way a host splits them with no shell: at spaces, a word in double quotes (the java's path, the jar's, when
     * they hold a space) being one word without its quotes. The first word is the {@code java}, the rest the client's options, class path and main class, and the call's arguments.
     */
    private static List<String> commandString(JsonNode handler) {
        return InstalledHookCommand.split(handler.path("command").stringValue());
    }

    /** The same hook with another wait for the server (the definition's is {@link HookClientCall#START_SERVER_SECONDS}). */
    private static List<String> withStartWait(List<String> argv, int seconds) {
        List<String> changed = new ArrayList<>();
        for (String argument : argv) {
            changed.add(argument.startsWith(HookClientCall.START_SERVER + "=") ? HookClientCall.START_SERVER + "=" + seconds : argument);
        }
        assertTrue(changed.contains(HookClientCall.START_SERVER + "=" + seconds), "the definition asks for the start: " + argv);
        return changed;
    }

    private static String fixture(String resource, String session) throws IOException {
        try (InputStream stream = HookStartsServerProcessTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace(FIXTURE_SESSION, session);
        }
    }

    private static byte[] claudeSessionStartInput(String session) throws IOException {
        return fixture("/hook-fixtures/linux/claude/SessionStart.json", session).getBytes(StandardCharsets.UTF_8);
    }

    private record Finished(int exit, String out, String err, Duration elapsed) {
    }

    /** A hook process as a host has it: stdin written and closed, stdout and stderr read to their ends by this test. */
    private final class Running {
        private final Process process;
        private final CompletableFuture<byte[]> out;
        private final CompletableFuture<byte[]> err;
        private final long started = System.nanoTime();
        private final CompletableFuture<Long> ended;

        Running(List<String> argv, byte[] stdin) throws IOException {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.environment().putAll(environment);
            builder.environment().remove("CLAUDE_CODE_SESSION_ATTENDED");
            process = builder.start();
            ended = process.onExit().thenApply(done -> System.nanoTime());
            out = readAll(process.getInputStream());
            err = readAll(process.getErrorStream());
            try (OutputStream in = process.getOutputStream()) {
                in.write(stdin);
            }
        }

        private static CompletableFuture<byte[]> readAll(InputStream stream) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return stream.readAllBytes();
                } catch (IOException unreadable) {
                    throw new UncheckedIOException(unreadable);
                }
            }, READERS);
        }

        /** Waits for the hook to end, and then for its pipes to end: a pipe still open after the hook ended is held by a process the hook started. */
        Finished await() throws Exception {
            assertTrue(process.waitFor(180, TimeUnit.SECONDS), "the hook process did not end");
            long elapsed = ended.get(10, TimeUnit.SECONDS) - started;
            try {
                return new Finished(process.exitValue(), new String(out.get(30, TimeUnit.SECONDS), StandardCharsets.UTF_8),
                        new String(err.get(30, TimeUnit.SECONDS), StandardCharsets.UTF_8), Duration.ofNanos(elapsed));
            } catch (TimeoutException held) {
                throw new AssertionError("the hook ended but its stdout or stderr did not: a process it started holds the pipe a host would be waiting on", held);
            }
        }
    }

    private Finished run(List<String> argv, byte[] stdin) throws Exception {
        return new Running(argv, stdin).await();
    }

    /** What a hook run made, sampled every 20 ms while it ran: {@link #below} is every {@code java} process under it, {@link #servers} those two levels below (the hook, the command that starts the server, the server). */
    private record Made(Set<Long> below, Set<Long> servers) {
    }

    private record HookOutcome(Finished finished, String awaitFailure) {
        String describe(Running running) {
            if (finished != null) {
                return "pid=" + running.process.pid() + ", exit=" + finished.exit() + ", elapsed=" + finished.elapsed();
            }
            Long endedAt = running.ended.exceptionally(failure -> null).getNow(null);
            String elapsed = endedAt == null ? "unobserved" : Duration.ofNanos(endedAt - running.started).toString();
            String exit = running.process.isAlive() ? "unobserved" : Integer.toString(running.process.exitValue());
            return "pid=" + running.process.pid() + ", exit=" + exit + ", elapsed=" + elapsed + ", awaitFailure=" + awaitFailure;
        }
    }

    /** A failed wait must still leave the other hook's outcome available for the race assertion. */
    private static HookOutcome awaitConcurrentHook(Running running) throws InterruptedException {
        try {
            return new HookOutcome(running.await(), "none");
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (AssertionError failed) {
            return new HookOutcome(null, failed.getCause() instanceof TimeoutException ? "pipes-did-not-close" : "process-did-not-end");
        } catch (Exception failed) {
            return new HookOutcome(null, failed.getClass().getSimpleName());
        }
    }

    /** Runs a hook as {@link #run} does and says what processes it made. */
    private Made runMaking(List<String> argv, byte[] stdin, Consumer<Finished> check) throws Exception {
        Running running = new Running(argv, stdin);
        try (Sampler sampler = new Sampler(running.process)) {
            Finished finished = running.await();
            check.accept(finished);
            return new Made(new HashSet<>(sampler.below), new HashSet<>(sampler.servers));
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ what the installation shows

    /**
     * The {@code java} processes a hook created, seen while it runs: {@link #below} everything under it, {@link #servers} two levels below it. The hook starts the command that starts the
     * server ({@code java ... -jar <jar> start}), which starts the server as its own child: so the second level is the servers a start made, whatever their command line (the JDK gives
     * no command line of another process on every OS). A server that was announced is told by its own address file, which says its pid.
     */
    private static final class Sampler implements AutoCloseable {
        final Set<Long> below = ConcurrentHashMap.newKeySet();
        final Set<Long> servers = ConcurrentHashMap.newKeySet();
        private volatile boolean running = true;

        Sampler(Process hook) {
            Thread.ofVirtual().start(() -> {
                while (running) {
                    hook.descendants().filter(Sampler::isJava).forEach(handle -> below.add(handle.pid()));
                    hook.children().flatMap(ProcessHandle::children).filter(Sampler::isJava).forEach(handle -> servers.add(handle.pid()));
                    LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
                }
            });
        }

        /** The program is a {@code java} ({@code java} or {@code java.exe}: the file name tells, no OS is asked). */
        private static boolean isJava(ProcessHandle handle) {
            return handle.info().command().map(command -> Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT).startsWith("java")).orElse(false);
        }

        Made snapshot() {
            return new Made(Set.copyOf(below), Set.copyOf(servers));
        }

        @Override
        public void close() {
            running = false;
        }
    }

    /** The address of the server that runs now, from the file it wrote: where its port is read, never chosen. */
    private Optional<ServerAddress> address() {
        return ServerAddress.read(addressFile).filter(ServerAddress::live);
    }

    private boolean healthy() {
        return new ServerControl(addressFile, Map.of()).healthy();
    }

    private boolean listening() {
        return new ServerControl(addressFile, Map.of()).listening();
    }

    /** Whether the start's lock can be taken: nobody holds it. */
    private boolean lockIsFree() throws IOException {
        try (FileChannel channel = FileChannel.open(state.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            return channel.tryLock() != null;
        }
    }

    private int post(String path, String body, Map<String, String> headers) throws Exception {
        return send(path, body, headers).statusCode();
    }

    private HttpResponse<String> send(String path, String body, Map<String, String> headers) throws Exception {
        URI uri = address().orElseThrow(() -> new AssertionError("no live server is announced in " + addressFile)).baseUrl().resolve(path);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The main sessions the server lists, asked as a Codex session does it (a recorded tool call's metadata), after that session registered itself with its own start. */
    private Set<String> listedSessions() throws Exception {
        String caller = UUID.randomUUID().toString();
        assertEquals(200, post("/hooks/v1/codex/SessionStart", fixture("/hook-fixtures/windows/codex/SessionStart.json", caller), Map.of()));
        String meta = fixture("/mcp-meta-fixtures/windows/codex.json", caller);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"list_session_status\",\"arguments\":{\"schemaVersion\":\"1.0.0\"},\"_meta\":" + meta + "}}";
        HttpResponse<String> response = send("/mcp", body, Map.of("Accept", "application/json, text/event-stream", "Sennard-Host-Label", "codex", "MCP-Protocol-Version", "2025-11-25"));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode envelope = JSON.readTree(JSON.readTree(response.body()).path("result").path("content").get(0).path("text").stringValue());
        assertTrue(envelope.path("ok").asBoolean(), envelope.toString());
        Set<String> sessions = new HashSet<>();
        envelope.path("data").path("sessions").forEach(item -> sessions.add(item.path("sessionId").stringValue()));
        return sessions;
    }

    // ------------------------------------------------------------------------------------------------------------------ cases

    @Test
    @Tag("business-db")
    void aSessionStartWithNoServerStartsTheServerAndTheEventIsRecordedAndAnsweredAsIfItWasUp() throws Exception {
        configure(true);
        assertFalse(listening(), "no server at the start of the case");
        String session = UUID.randomUUID().toString();

        Finished first = run(claudeSessionStart(), claudeSessionStartInput(session));

        assertEquals(0, first.exit(), first.toString());
        assertEquals("", first.err(), "nothing on stderr: the host shows it");
        assertTrue(first.out().startsWith("{") && !first.out().contains(GUIDANCE), "the server's answer, not the guidance: " + first.out());
        assertTrue(healthy(), "the server the hook started answers");
        ServerAddress server = address().orElseThrow(() -> new AssertionError("the server the hook started announced itself in " + addressFile));
        assertTrue(listedSessions().contains(session), "the event reached the server and the session is recorded");
        LockSupport.parkNanos(Duration.ofSeconds(2).toNanos());
        assertTrue(server.live(), "the server outlives the hook process that started it");
        assertTrue(lockIsFree(), "the start's lock is not held after the hook");

        String second = UUID.randomUUID().toString();
        Finished again = run(claudeSessionStart(), claudeSessionStartInput(second));
        assertEquals(0, again.exit(), again.toString());
        assertEquals(first.out().replace(session, second), again.out(), "a hook that finds the server running gets the same answer: the start changes nothing of what the host sees");
        LOG.info("session start with no server: {} ms until the answer", first.elapsed().toMillis());
    }

    @Test
    @Tag("business-db")
    void aSessionStartThatFindsTheServerRunningStartsNothing() throws Exception {
        configure(true);
        ReleaseJarFixture.Run start = ReleaseJarFixture.run(ReleaseJarFixture.java(home, "-jar", installed.appJar().toString(), "start"), root, environment);
        assertEquals(0, start.exitCode(), start.stdout());
        awaitStore();
        ServerAddress before = address().orElseThrow();
        Files.deleteIfExists(state.resolve(LOCK_FILE));

        Made made = runMaking(claudeSessionStart(), claudeSessionStartInput(UUID.randomUUID().toString()), finished -> assertEquals(0, finished.exit(), finished.toString()));

        assertEquals(Set.of(), made.servers(), "the hook started no server: the server was found through the file (java processes below the hook: " + made.below() + ")");
        assertEquals(before, address().orElseThrow(), "the server that ran is the one that runs: nothing was started");
        assertFalse(Files.exists(state.resolve(LOCK_FILE)), "the start was not entered: its lock file was never made");
    }

    private void awaitStore() {
        ServerControl server = new ServerControl(addressFile, Map.of());
        ServerControl.await(() -> server.storeStatus().map(status -> status.phase() == io.github.jaeseongs95.sennard.service.store.pg.StorePhase.READY).orElse(false),
                Duration.ofMinutes(3), UnavailableCode.STORE_PREPARATION_TIMEOUT, Map.of());
    }

    /** The announcement and probes are read once after both waits, when an assertion fails. */
    private String concurrentStartSnapshot() {
        Instant observedAt = Instant.now();
        String announced = switch (ServerAddress.inspect(addressFile)) {
            case ServerAddress.Reading.Found found -> {
                ServerAddress address = found.address();
                yield "pid=" + address.pid() + ", port=" + address.port() + ", processStart=" + address.processStart();
            }
            case ServerAddress.Reading.Absent absent -> "absent";
            case ServerAddress.Reading.Unusable unusable -> "unusable";
        };
        ServerControl control = new ServerControl(addressFile, Map.of(), Duration.ofSeconds(1));
        boolean listening = control.listening();
        String health = listening ? (control.healthy() ? "2xx" : "no-2xx-observed") : "not-probed";
        String storePhase = listening ? control.storeStatus().map(StoreStatus::phase).map(Enum::name).orElse("unobserved") : "not-probed";
        return "post-await probes started at " + observedAt + ": announcement=[" + announced + "], listening (live announcement)=" + listening
                + ", health=" + health + ", storePhase=" + storePhase;
    }

    private static String describePids(Set<Long> pids) {
        return pids.stream().sorted().limit(12).toList() + " (" + pids.size() + " seen)";
    }

    @Test
    @Tag("business-db")
    void twoSessionStartsAtTheSameTimeStartOneServer() throws Exception {
        configure(true);
        Made single = runMaking(claudeSessionStart(), claudeSessionStartInput(UUID.randomUUID().toString()), finished -> assertEquals(0, finished.exit(), finished.toString()));
        ServerAddress first = address().orElseThrow();
        assertTrue(single.servers().contains(first.pid()), "the sampler sees the server's process, which is the one announced: " + single + " " + first);
        new ServerControl(addressFile, Map.of()).shutdown(); // returns when the process is gone: a later sample must not count the one that left
        assertFalse(listening());

        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        Running one = new Running(claudeSessionStart(), claudeSessionStartInput(a));
        Running two = new Running(claudeSessionStart(), claudeSessionStartInput(b));
        HookOutcome outcomeOne;
        HookOutcome outcomeTwo;
        Made sampledOne;
        Made sampledTwo;
        try (Sampler samplerOne = new Sampler(one.process); Sampler samplerTwo = new Sampler(two.process)) {
            outcomeOne = awaitConcurrentHook(one);
            outcomeTwo = awaitConcurrentHook(two);
            sampledOne = samplerOne.snapshot();
            sampledTwo = samplerTwo.snapshot();
        }
        Supplier<String> diagnostics = () -> "hook one [" + outcomeOne.describe(one) + "], hook two [" + outcomeTwo.describe(two)
                + "]; elapsed runs from launch to observed exit. Java PIDs sampled every 20 ms during the waits (an empty sample leaves the process unobserved): "
                + "one descendants=" + describePids(sampledOne.below()) + ", servers=" + describePids(sampledOne.servers())
                + "; two descendants=" + describePids(sampledTwo.below()) + ", servers=" + describePids(sampledTwo.servers())
                + "; single-start servers=" + describePids(single.servers()) + "; " + concurrentStartSnapshot();
        Finished finishedOne = outcomeOne.finished();
        Finished finishedTwo = outcomeTwo.finished();
        assertNotNull(finishedOne, () -> "the first hook wait failed: " + diagnostics.get());
        assertNotNull(finishedTwo, () -> "the second hook wait failed: " + diagnostics.get());
        assertEquals(0, finishedOne.exit(), () -> "the first hook failed: " + diagnostics.get());
        assertEquals(0, finishedTwo.exit(), () -> "the second hook failed: " + diagnostics.get());
        assertTrue(finishedOne.out().startsWith("{") && finishedTwo.out().startsWith("{"), () -> "both hooks must return JSON: " + diagnostics.get());
        Set<Long> both = new HashSet<>(sampledOne.servers());
        both.addAll(sampledTwo.servers());
        ServerAddress second = address().orElseThrow(() -> new AssertionError("the server did not announce a live address: " + diagnostics.get()));
        assertTrue(both.contains(second.pid()), () -> "the server that answered must be among those seen: " + diagnostics.get());
        assertEquals(single.servers().size(), both.size(), () -> "two concurrent starts must make as many server processes as one start: " + diagnostics.get());
        Set<String> listed = listedSessions();
        assertTrue(listed.contains(a) && listed.contains(b), () -> "both events must be recorded: " + diagnostics.get());
        assertTrue(lockIsFree(), () -> "the startup lock must be free: " + diagnostics.get());
    }

    @Test
    void aDamagedApplicationJarIsTheGuidanceAndLeavesNoServerAndNoLock() throws Exception {
        configure(false);
        Path jar = installed.appJar();
        byte[] original = Files.readAllBytes(jar);
        assertTrue(jar.toFile().setWritable(true), "the copy's jar can be written");
        Files.write(jar, "this is not a jar".getBytes(StandardCharsets.UTF_8));
        try {
            Made made = runMaking(claudeSessionStart(), claudeSessionStartInput(UUID.randomUUID().toString()), finished -> {
                assertEquals(2, finished.exit(), finished.toString());
                assertEquals(GUIDANCE, finished.err());
                assertEquals("", finished.out());
            });

            assertEquals(Set.of(), made.servers(), "the command that starts the server could not run the jar, so no server process was made");
            assertFalse(listening());
            assertTrue(lockIsFree(), "the lock is not left held");
        } finally {
            Files.write(jar, original);
        }
    }

    @Test
    void aServerThatIsNotReadyInTheTimeAllowedIsTheGuidanceAndTheLockIsFreeAndTheServerBeingStartedIsLeftToComeUp() throws Exception {
        configure(false);

        // The hook gave up by itself and did not wait on: run waits for the process to end (and fails when it does not), and its end is the guidance with exit code 2,
        // not the server's answer. No elapsed time is asserted.
        Finished finished = run(withStartWait(claudeSessionStart(), 1), claudeSessionStartInput(UUID.randomUUID().toString()));

        assertEquals(2, finished.exit(), finished.toString());
        assertEquals(GUIDANCE, finished.err());
        assertEquals("", finished.out());
        assertTrue(lockIsFree(), "the lock is not left held");
        // The server that was being started is not killed: a store that is half prepared must not be cut, and the next session start finds it up.
        ServerControl.await(this::healthy, Duration.ofMinutes(2), UnavailableCode.START_FAILED, Map.of("what", "other"));
    }

    @Test
    void aSessionStartWhoseApplicationJarIsGoneIsTheGuidanceAndStartsNothing() throws Exception {
        configure(false);
        InstalledVersion removed = InstalledHookCommand.hookClientOnly(shared.resolve("removed-installation"));
        commandVersion = removed;
        assertFalse(Files.exists(removed.appJar()), "the definitions name a jar that is not there");

        Made made = runMaking(claudeSessionStart(), claudeSessionStartInput(UUID.randomUUID().toString()), finished -> {
            assertEquals(2, finished.exit(), finished.toString());
            assertEquals(GUIDANCE, finished.err());
            assertEquals("", finished.out());
        });

        assertEquals(Set.of(), made.servers(), "no server process");
        assertFalse(listening());
        assertFalse(Files.exists(state.resolve(LOCK_FILE)), "nothing was started, so nothing was locked");
        assertFalse(Files.exists(removed.appJar()), "and nothing was made in the folder of the installation that is not there");
    }

    @Test
    void anEventOtherThanTheSessionStartStartsNothingWhenThereIsNoServer() throws Exception {
        configure(false);
        List<List<String>> hooks = new ArrayList<>();
        hooks.add(commandString(definition(new AntigravityHookAdapter(JSON)).path("sennard-tool-boundary").path("PreToolUse").path(0).path("hooks").path(0)));
        hooks.add(commandString(definition(new CodexHookAdapter(JSON)).path("hooks").path("SessionEnd").path(0).path("hooks").path(0)));
        hooks.add(commandString(definition(new GeminiHookAdapter(JSON)).path("hooks").path("AfterTool").path(0).path("hooks").path(0)));
        for (List<String> hook : hooks) {
            assertFalse(hook.stream().anyMatch(argument -> argument.startsWith(HookClientCall.START_SERVER)), "this event does not ask for the start: " + hook);
        }
        String gemini = "{\"session_id\":\"" + FIXTURE_SESSION + "\",\"transcript_path\":\"\",\"cwd\":\"/work\",\"hook_event_name\":\"AfterTool\",\"timestamp\":\"2026-01-01T00:00:00Z\"}";
        Set<Long> made = new HashSet<>();

        Running agy = new Running(hooks.get(0), fixture("/hook-fixtures/linux/agy/PreToolUse.json", FIXTURE_SESSION).getBytes(StandardCharsets.UTF_8));
        Finished agyAnswer;
        try (Sampler sampler = new Sampler(agy.process)) {
            agyAnswer = agy.await();
            made.addAll(sampler.below);
        }
        Running codex = new Running(hooks.get(1), fixture("/hook-fixtures/linux/codex/SessionEnd.json", FIXTURE_SESSION).getBytes(StandardCharsets.UTF_8));
        Finished codexAnswer;
        try (Sampler sampler = new Sampler(codex.process)) {
            codexAnswer = codex.await();
            made.addAll(sampler.below);
        }
        Running geminiHook = new Running(hooks.get(2), gemini.getBytes(StandardCharsets.UTF_8));
        Finished geminiAnswer;
        try (Sampler sampler = new Sampler(geminiHook.process)) {
            geminiAnswer = geminiHook.await();
            made.addAll(sampler.below);
        }

        assertEquals(0, agyAnswer.exit(), agyAnswer.toString());
        JsonNode agyDecision = JSON.readTree(agyAnswer.out());
        assertEquals("deny", agyDecision.path("decision").asString(), agyAnswer.toString());
        assertTrue(agyDecision.path("reason").asString().contains("SERVER_NOT_REACHABLE"), agyAnswer.toString());
        assertEquals(1, codexAnswer.exit(), codexAnswer.toString());
        assertTrue(codexAnswer.err().contains("Sennard hook failed"), codexAnswer.toString());
        assertEquals(1, geminiAnswer.exit(), geminiAnswer.toString());
        assertEquals(Set.of(), made, "no hook made a process: none started a server");
        assertFalse(listening());
        assertFalse(Files.exists(state.resolve(LOCK_FILE)), "the start was not entered");
    }

    /**
     * A session start with no server, the real store, on an installation made by {@code sennard install} (so the version has the AOT caches and the JVM preset an installation has; the
     * copies of the other cases have neither), {@code rounds} times, each against a server that was stopped. Every start must be answered by the server. The time of a round is the
     * hook process's, from its start to its end (the hook client's start, the installed jar's start, the server's start and its store, and the event's request), which is what a host's
     * limit for the hook is spent on; the times are returned.
     *
     * <p>The install runs with the store left unprepared ({@code SENNARD_STORE_PREPARE=false}): it has no business database yet, and the settings that name it are written into the
     * installed version afterwards ({@link ReleaseJarFixture#writeSettings}), which every later start reads.
     */
    private List<Long> startsOnARealInstallation(int rounds) throws Exception {
        Path installRoot = shared.resolve("install-root-measured");
        useState(shared.resolve("state-measured-" + UUID.randomUUID()), installRoot);
        Map<String, String> unprepared = new HashMap<>(environment);
        unprepared.put("SENNARD_STORE_PREPARE", "false");
        ReleaseJarFixture.Run install = ReleaseJarFixture.run(ReleaseJarFixture.java(home, "-jar", ReleaseJarFixture.releaseJar().toString(), "install"), null, unprepared);
        assertEquals(0, install.exitCode(), install.stdout());
        // The server the install started holds the settings database of the state folder (H2 lets one process hold its file), and the link of the business database is written into that
        // database by this process next: the server is stopped (the call returns when its process has ended) before, and every round starts its own.
        new ServerControl(addressFile, Map.of()).shutdown();
        InstalledVersion real = new InstalledVersion(installRoot.resolve(InstalledVersion.VERSIONS).resolve(VERSION));
        ReleaseJarFixture.writeSettings(real, state, true);
        commandVersion = real;
        assertTrue(Files.size(real.folder().resolve(JvmOptions.HOOK_AOT_CACHE_FILE)) > 0, "the installation has the hook client's AOT cache");
        Collector collector = JvmSettingsFile.read(real.jvmSettingsFile()).orElseThrow().preset().collector();
        assertTrue(Files.size(real.aotCache(collector)) > 0, "and the server's, for the collector of the preset the install chose");
        List<Long> millis = new ArrayList<>();
        for (int round = 1; round <= rounds; round++) {
            assertFalse(listening(), "round " + round + ": no server");
            Finished finished = run(claudeSessionStart(), claudeSessionStartInput(UUID.randomUUID().toString()));
            assertEquals(0, finished.exit(), "round " + round + ": " + finished);
            assertTrue(finished.out().startsWith("{") && !finished.out().contains(GUIDANCE), "round " + round + ": " + finished.out());
            millis.add(finished.elapsed().toMillis());
            new ServerControl(addressFile, Map.of()).shutdown();
        }
        return millis;
    }

    /** The default run's case on a real installation: one start, answered by the server it started (the five-round measurement is the next case's). */
    @Test
    @Tag("business-db")
    void aSessionStartOnARealInstallationStartsTheServerAndIsAnswered() throws Exception {
        List<Long> millis = startsOnARealInstallation(1);
        LOG.info("session start with no server (installed copy): {} ms until the answer", millis.get(0));
    }

    /**
     * The measurement of the start on this PC: {@link #startsOnARealInstallation} five times, the numbers to the log. It asserts only what the single start asserts (every
     * start answered); it is a measurement and not a regression gate, so it carries the tag of the {@code perfBaseline} task and the default {@code test} task leaves it out.
     */
    @Test
    @Tag(PERF_BASELINE)
    void measuresTheStartUntilTheEventIsAnsweredOnARealInstallation() throws Exception {
        List<Long> millis = startsOnARealInstallation(5);
        List<Long> sorted = millis.stream().sorted().toList();
        LOG.info("MEASURED session start with no server (installed copy, store prepared, this PC): {} ms per round; median {} ms; the definitions' wait {} s and limit {} s", millis,
                sorted.get(sorted.size() / 2), HookClientCall.START_SERVER_SECONDS, HookClientCall.SESSION_START_LIMIT_SECONDS);
    }
}
