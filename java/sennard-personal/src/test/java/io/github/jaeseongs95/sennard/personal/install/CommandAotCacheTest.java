// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import com.sun.net.httpserver.HttpServer;

import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.jvm.JvmPreset;
import io.github.jaeseongs95.sennard.hookclient.HookClientCall;
import io.github.jaeseongs95.sennard.hookclient.HookClientCall.FailureAnswer;
import io.github.jaeseongs95.sennard.hookclient.HookClientMain;
import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.server.hook.host.AntigravityHost;
import io.github.jaeseongs95.sennard.server.hook.host.GeminiHost;

/**
 * The AOT cache of the hook client, on the installed version the test makes from the build's extracted release ({@link ReleaseJarFixture#installedCopy}), the JDK this test runs with and
 * the real hook client jar (no stand-in for the JVM, the cache or the client). One installed version gets its cache from {@link CommandAotCache#make} (what the installer does); every
 * case then runs the client the way an installed hook definition does ({@link HookClientCommand#hookProgram}: the java by its absolute path, the client's options, the client's jar as
 * the class path, the main class, where to find the server and the jar, and the call's arguments) on a folder of its own and compares exit code, standard output and standard error with the run
 * that has the cache, and counts where the JVM loaded its classes from ({@code -Xlog:class+load} written to a file of the test's folder by a JVM option placed after the others):
 * "shared objects file" is the cache, "source: file:" is a JAR. The client is JDK only, so no class of Spring, Jackson, SLF4J or Logback is loaded in any run. The cases that change the
 * cache or the jar work on a folder that holds the client's jar alone (the class path is that one jar, so the cache is made for it the same way), made with the same {@code make}.
 *
 * <pre>
 * a1  used          the cache is used (most classes come from it): exit 1 (no server announced), nothing on standard output, the client's reason code
 *                   {@code Sennard hook failed: SERVER_NOT_REACHABLE} on standard error; no Spring, Jackson, SLF4J or Logback class is loaded
 * a2  answered      with a server that answers, found through its address file, the answer is the whole standard output, exit 0, nothing on standard error, the cache still serves the
 *                   classes (the training run reached the answered path: it is given a server of its own that answers), no framework class; also for an Antigravity hook (the training is a Gemini one)
 * a3  no file       cache file missing: same exit code, output and error as a1, nothing printed by the JVM, no class from the cache
 * a4  damaged       cache file with changed header bytes, and an empty one: same as a3
 * a5  jar changed   the client's JAR with another modification time: same as a3 (the JDK refuses a cache whose JAR changed)
 * a6  moved         the version's folder copied (attributes kept) to another folder: same output as a1; whether the cache is used is measured and shown in the message (the manual
 *                   says only that the class path may have another prefix)
 * a7  the words     the definition's command is the java by its absolute path, the client's options with AOT mode auto and the cache beside the jar, the client's jar as the whole class
 *                   path, the client's main class and the two locations; the training leaves only the cache: no recording, no copy of the Antigravity input, no address file of the
 *                   training's server
 * a8  failure       a training that fails prints a warning, leaves no recording and no cache behind, does not stop the install and the client still answers as without it; a creation
 *                   that fails says so; both steps and the final run have the same class path words; a command that cannot run is the caller's, and the files the training made are
 *                   still removed
 * a9  build         the cache of the commandAotCache task (outside the version) is used by the extracted release it was made from when the JVM is given it (the same options and class
 *                   path words, the cache file named by the option), and the extracted release holds no cache itself
 * a10 arguments     the training run is a Gemini BeforeModel hook against the address file it is given, and the Antigravity input it reads first is named by {@code --warm}
 * a11 the server     the training run is given the address file of a live server that answers (this installer's own, on a port the operating system gave), which is gone with the file when the
 *                   training ends
 * </pre>
 */
class CommandAotCacheTest {
    private static final Logger LOG = LoggerFactory.getLogger(CommandAotCacheTest.class);
    /** The classes the client must never load: it is JDK only, and a hook starts it for every event. */
    private static final Pattern FRAMEWORK = Pattern.compile("\\s(org\\.springframework|tools\\.jackson|com\\.fasterxml|org\\.slf4j|ch\\.qos\\.logback)\\.");
    private static final String SEPARATOR = "\n--\n";
    /** What a Gemini BeforeModel hook is run with: the label of the host, the event and the failure answer that makes a missing server a failed hook. */
    private static final List<String> GEMINI_CALL = HookClientCall.of(GeminiHost.ID.label(), "BeforeModel", FailureAnswer.FAIL).arguments();
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @TempDir
    static Path shared;

    private static InstalledVersion original;
    /** The address file of a server that is not there: it is never made. */
    private static Path noServer;
    private static Result reference;

    @BeforeAll
    static void makeTheCacheOfAnInstalledVersion() throws Exception {
        original = ReleaseJarFixture.installedCopy(shared.resolve("root"), ReleaseJarFixture.version());
        noServer = shared.resolve("no-server.properties");
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        new CommandAotCache(new JavaCommands(), new PrintStream(said, true, StandardCharsets.UTF_8)).make(original, java());
        assertEquals("", said.toString(StandardCharsets.UTF_8), "the training said nothing: it worked");
        assertTrue(Files.size(original.folder().resolve("sennard-hook.aot")) > 0, "the cache exists");
        assertFalse(Files.exists(original.folder().resolve("sennard-hook.aotconf")), "the recording is removed once the cache exists");
        assertFalse(Files.exists(original.folder().resolve("sennard-aot-antigravity-input.json")), "the Antigravity input of the training is removed too");
        assertFalse(Files.exists(original.folder().resolve(CommandAotCache.TRAINING_ADDRESS_FILE)), "the address file of the training's server is removed when the training ends");
        assertFalse(Files.exists(original.aotCache(JvmPreset.G1_2G.collector())), "the hook client's training makes no cache of the server's");
        reference = hook(original, noServer);
    }

    private static Path java() {
        return ReleaseJarFixture.javaProgram();
    }

    record Result(int exit, String stdout, String stderr) {
        @Override
        public String toString() {
            return "exit " + exit + SEPARATOR + stdout + SEPARATOR + stderr;
        }
    }

    /** Where the JVM of one run loaded its classes from, and how many of them are a framework's (none may be). */
    record Loads(long total, long cache, long jar, long framework) {
        boolean cacheUsed() {
            return cache > total * 9 / 10;
        }

        boolean cacheNotUsed() {
            return cache == 0 && jar > 0;
        }
    }

    // ------------------------------------------------------------------ a1, a2, a7

    @Test
    void theCacheIsUsedAndTheClientEndsAsItDoesWithoutAServer() throws Exception {
        assertEquals(1, reference.exit, reference.toString());
        assertEquals("", reference.stdout, "a hook's standard output is its answer to the host: nothing from the JVM");
        assertTrue(reference.stderr.startsWith("Sennard hook failed: SERVER_NOT_REACHABLE"), reference.toString());
        Loads loads = loads(original, noServer);
        assertTrue(loads.cacheUsed(), "the classes come from the cache: " + loads);
        assertEquals(0, loads.framework(), "the client loads no class of Spring, Jackson, SLF4J or Logback: " + loads);
    }

    @Test
    void anAnsweredHookIsTheServersAnswerAloneAndTheCacheStillServesItsClasses() throws Exception {
        HttpServer server = answering("{\"decision\":\"allow\"}");
        try {
            Path announced = announce(server);
            assertEquals(new Result(0, "{\"decision\":\"allow\"}", ""), hook(original, announced));
            Loads loads = loads(original, announced);
            assertTrue(loads.cacheUsed(), "the answered path is served by the cache although the training's server answered the run: " + loads);
            assertEquals(0, loads.framework(), loads.toString());
        } finally {
            server.stop(0);
        }
    }

    /**
     * AGS3-D-0210: Antigravity and Gemini CLI only run command hooks (their documents list no other handler type), so each of their hooks is one JVM start and the
     * cache is what keeps it short. The cache is trained on a Gemini call; an Antigravity hook reduces its own input to its own fields, and the cache must serve it too.
     */
    @Test
    void anAnsweredAntigravityHookIsServedByTheCacheToo() throws Exception {
        HttpServer server = answering("{\"decision\":\"allow\"}");
        try {
            Path announced = announce(server);
            List<String> call = HookClientCall.of(AntigravityHost.ID.label(), "PreToolUse", FailureAnswer.ALLOW_DECISION).arguments();
            String input = new ClassPathResource("hook-fixtures/windows/agy/PreToolUse.json").getContentAsString(StandardCharsets.UTF_8);
            Path log = logFile();
            Result result = run(withLog(HookClientCommand.hookProgram(java(), original, announced), log), call, input, Map.of());
            assertEquals(0, result.exit(), result.toString());
            assertEquals("{\"decision\":\"allow\"}", result.stdout(), result.toString());
            Loads loads = readLoads(log);
            assertTrue(loads.cacheUsed(), "an Antigravity hook is served by the cache: " + loads);
            assertEquals(0, loads.framework(), loads.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theDefinitionsCommandIsTheJavaWithTheClientsOptionsAndJarAndTheTrainingLeftOnlyTheCache() throws Exception {
        List<String> words = HookClientCommand.hookProgram(java(), original, noServer);
        assertEquals(java().toString(), words.getFirst(), "the java by its absolute path, with no launcher in between");
        String cache = original.folder().resolve("sennard-hook.aot").toString();
        assertTrue(words.contains("-XX:AOTMode=auto") && words.contains("-XX:AOTCache=" + cache) && words.contains("-Xlog:disable"), words.toString());
        assertFalse(words.stream().anyMatch(word -> word.contains("AOTMode=record") || word.contains("AOTMode=create") || word.contains("AOTConfiguration")), words.toString());
        int classPath = words.indexOf("-cp");
        assertEquals(original.hookClientJar().toString(), words.get(classPath + 1), "the client's jar is the whole class path");
        assertEquals(HookClientMain.class.getName(), words.get(classPath + 2));
        assertEquals(List.of("--server-file=" + noServer, "--jar=" + original.appJar()), words.subList(classPath + 3, words.size()), "the locations and nothing after them: the call's arguments are the host's definition's");
        assertTrue(trainingInput().contains("\"hook_event_name\": \"BeforeModel\""), "the training input is a BeforeModel call");
    }

    // ------------------------------------------------------------------ a3, a4, a5, a6

    @Test
    void aMissingCacheFileIsASilentStartWithoutIt() throws Exception {
        InstalledVersion copy = trainedCopy("missing");
        Files.delete(copy.folder().resolve("sennard-hook.aot"));
        assertSameAnswer(copy);
        assertTrue(loads(copy, noServer).cacheNotUsed());
    }

    @Test
    void aCacheWithChangedHeaderBytesIsASilentStartWithoutIt() throws Exception {
        InstalledVersion copy = trainedCopy("damaged");
        Path cache = copy.folder().resolve("sennard-hook.aot");
        byte[] bytes = Files.readAllBytes(cache);
        for (int i = 0; i < 16; i++) {
            bytes[i] ^= 0x5a;
        }
        Files.write(cache, bytes);
        assertSameAnswer(copy);
        assertTrue(loads(copy, noServer).cacheNotUsed());
    }

    @Test
    void anEmptyCacheFileIsASilentStartWithoutIt() throws Exception {
        InstalledVersion copy = trainedCopy("empty");
        Files.write(copy.folder().resolve("sennard-hook.aot"), new byte[0]);
        assertSameAnswer(copy);
        assertTrue(loads(copy, noServer).cacheNotUsed());
    }

    @Test
    void theClientJarWithAnotherModificationTimeIsASilentStartWithoutTheCache() throws Exception {
        InstalledVersion copy = trainedCopy("touched");
        Files.setLastModifiedTime(copy.hookClientJar(), FileTime.from(Instant.now().plus(Duration.ofHours(1))));
        assertSameAnswer(copy);
        assertTrue(loads(copy, noServer).cacheNotUsed(), "the JDK refuses a cache whose JAR changed");
    }

    @Test
    void aVersionCopiedToAnotherFolderStartsSilentlyWhetherOrNotTheCacheIsStillUsed() throws Exception {
        InstalledVersion trained = trainedCopy("to-move");
        InstalledVersion moved = new InstalledVersion(shared.resolve("moved-" + COUNTER.incrementAndGet()).resolve("6.0.0"));
        copyTree(trained.folder(), moved.folder());
        assertSameAnswer(moved);
        Loads loads = loads(moved, noServer);
        assertTrue(loads.cacheUsed() || loads.cacheNotUsed(), "either the cache is used or it is not, never half: " + loads);
        LOG.info("version folder copied with its attributes to another folder: cache used = {} ({})", loads.cacheUsed(), loads);
    }

    /**
     * The build's cache ({@code commandAotCache}, made outside the version from the extracted release) was made with the same JVM options and the same class path words as an installed
     * hook runs with, so the extracted release uses it when the option that names the cache points at it: the words are those of {@link HookClientCommand#hookProgram} with the
     * cache's own path.
     */
    @Test
    void theCacheTheBuildMadeIsUsedByTheExtractedReleaseItWasMadeFor() throws Exception {
        InstalledVersion built = InstalledVersion.ofFolder(ReleaseJarFixture.extracted());
        Path cache = Path.of(System.getProperty("sennard.command.aot.cache"));
        assertTrue(Files.size(cache) > 0, "commandAotCache made " + cache);
        assertEquals("sennard-hook.aot", cache.getFileName().toString());
        assertFalse(Files.exists(built.folder().resolve("sennard-hook.aot")), "the extracted release carries no cache: the installer makes the installed version's own");
        Path log = logFile();
        List<String> words = withLog(HookClientCommand.hookProgram(java(), built, noServer), log);
        words.replaceAll(word -> word.startsWith("-XX:AOTCache=") ? "-XX:AOTCache=" + cache : word);
        Result result = run(words, GEMINI_CALL, trainingInput(), Map.of());
        assertEquals(1, result.exit, result.toString());
        assertEquals("", result.stdout, result.toString());
        Loads loads = readLoads(log);
        assertTrue(loads.cacheUsed(), "the build's cache serves the extracted release it was made from: " + loads);
        assertEquals(0, loads.framework(), loads.toString());
    }

    // ------------------------------------------------------------------ a8

    @Test
    void aTrainingThatFailsWarnsAndLeavesNoRecordingAndTheClientStillAnswers() throws Exception {
        InstalledVersion copy = copyWithoutCache("failing-training");
        List<List<String>> steps = new ArrayList<>();
        Commands failing = (argv, environment, timeout) -> {
            steps.add(argv);
            return new Commands.Result(3, "no good");
        };
        ByteArrayOutputStream said = new ByteArrayOutputStream();

        new CommandAotCache(failing, new PrintStream(said, true, StandardCharsets.UTF_8)).make(copy, java()); // a failed training does not throw: the install goes on without a cache

        assertEquals(1, steps.size(), "no second step after a failed recording");
        assertTrue(steps.get(0).contains("-XX:AOTMode=record") && steps.get(0).stream().anyMatch(word -> word.startsWith("-XX:AOTConfiguration=") && word.endsWith("sennard-hook.aotconf")), steps.get(0).toString());
        assertEquals(java().toString(), steps.get(0).getFirst(), "the training runs the java the definitions name");
        String warning = said.toString(StandardCharsets.UTF_8);
        assertTrue(warning.contains("warning: AOT training of the hook client failed in record (exit 3)") && warning.contains("no good"), warning);
        assertFalse(Files.exists(copy.folder().resolve("sennard-hook.aotconf")), "no recording is left");
        assertFalse(Files.exists(copy.folder().resolve("sennard-aot-antigravity-input.json")), "the Antigravity input of the training is not left");
        assertFalse(Files.exists(copy.folder().resolve("sennard-hook.aot")), "no cache");
        assertSameAnswer(copy);
    }

    @Test
    void aCreationThatFailsSaysSoAndBothStepsAndTheFinalRunHaveTheSameClassPathWords() throws Exception {
        InstalledVersion copy = copyWithoutCache("failing-creation");
        List<List<String>> steps = new ArrayList<>();
        Commands creationFails = (argv, environment, timeout) -> {
            steps.add(argv);
            if (steps.size() == 1) {
                try {
                    Files.writeString(copy.folder().resolve("sennard-hook.aotconf"), "a recording");
                } catch (IOException cause) {
                    throw new IllegalStateException(cause);
                }
                return new Commands.Result(1, ""); // the client ends with 1 when no server answers: that is the training run
            }
            return new Commands.Result(4, "cannot create");
        };
        ByteArrayOutputStream said = new ByteArrayOutputStream();

        new CommandAotCache(creationFails, new PrintStream(said, true, StandardCharsets.UTF_8)).make(copy, java());

        assertEquals(2, steps.size(), steps.toString());
        assertTrue(steps.get(1).contains("-XX:AOTMode=create") && steps.get(1).stream().anyMatch(word -> word.startsWith("-XX:AOTCache=") && word.endsWith("sennard-hook.aot")), steps.get(1).toString());
        assertTrue(said.toString(StandardCharsets.UTF_8).contains("warning: AOT training of the hook client failed in create (exit 4)"), said.toString(StandardCharsets.UTF_8));
        assertFalse(Files.exists(copy.folder().resolve("sennard-hook.aotconf")), "the recording is removed even when the creation failed");
        List<String> installed = HookClientCommand.hookProgram(java(), copy, noServer);
        String classPath = installed.get(installed.indexOf("-cp") + 1);
        for (List<String> step : steps) {
            assertEquals(classPath, step.get(step.indexOf("-cp") + 1), "the cache is for the class path it is made with: " + step);
            assertEquals(HookClientMain.class.getName(), step.get(step.indexOf("-cp") + 2));
            assertTrue(step.containsAll(GEMINI_CALL) && step.contains("--server-file=" + copy.folder().resolve(CommandAotCache.TRAINING_ADDRESS_FILE)), step.toString());
        }
        assertSameAnswer(copy);
    }

    /** A command that cannot be run is not a failed training: it reaches the caller, and what the training had made is still removed. */
    @Test
    void aCommandThatCannotRunReachesTheCallerAndTheFilesOfTheTrainingAreStillRemoved() throws Exception {
        InstalledVersion copy = copyWithoutCache("unstartable");
        Commands unstartable = (argv, environment, timeout) -> {
            try {
                Files.writeString(copy.folder().resolve("sennard-hook.aotconf"), "a recording");
            } catch (IOException cause) {
                throw new IllegalStateException(cause);
            }
            throw new UnavailableException(UnavailableCode.COMMAND_NOT_STARTED, Map.of("command", String.valueOf(argv), "cause", "no such program"));
        };

        UnavailableException failure = assertThrows(UnavailableException.class,
                () -> new CommandAotCache(unstartable, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)).make(copy, java()));

        assertEquals(UnavailableCode.COMMAND_NOT_STARTED, failure.code());
        assertFalse(Files.exists(copy.folder().resolve("sennard-hook.aotconf")), "the recording is removed");
        assertFalse(Files.exists(copy.folder().resolve("sennard-aot-antigravity-input.json")), "the Antigravity input is removed");
    }

    // ------------------------------------------------------------------ a10

    @Test
    void theTrainingIsAGeminiHookAgainstTheAddressFileItIsGivenAndNamesTheAntigravityInputToRead() {
        Path none = shared.resolve("given.properties");
        Path warm = shared.resolve("warm.json");
        assertEquals(List.of("hook", "gemini", "BeforeModel", "--server-file=" + none), CommandAotCache.hookArguments(none));
        assertEquals(List.of("hook", "gemini", "BeforeModel", "--server-file=" + none, "--warm=agy=" + warm), CommandAotCache.trainingArguments(none, warm));
        assertEquals("aot/gemini-before-model.json", CommandAotCache.TRAINING_INPUT);
        assertEquals("aot/antigravity-pre-tool-use.json", CommandAotCache.WARM_INPUT);
        assertFalse(Files.exists(none), "the file is only named by the arguments");
    }

    // ------------------------------------------------------------------ a11

    @Test
    void theTrainingRunIsGivenTheAddressFileOfALiveServerThatAnswersAndItIsGoneWhenTheTrainingEnds() throws Exception {
        InstalledVersion copy = copyWithoutCache("training-server");
        AtomicReference<ServerAddress> given = new AtomicReference<>();
        List<Integer> statuses = new ArrayList<>();
        List<String> answers = new ArrayList<>();
        Commands probing = (argv, environment, timeout) -> {
            String option = argv.stream().filter(word -> word.startsWith(HookClientCall.SERVER_FILE + "=")).findFirst().orElseThrow();
            ServerAddress address = ServerAddress.read(Path.of(option.substring(option.indexOf('=') + 1))).orElseThrow(() -> new AssertionError("the training's address file is not there: " + argv));
            given.set(address);
            assertTrue(address.live(), "the file names a process that lives: " + address);
            try {
                HttpURLConnection connection = (HttpURLConnection) address.baseUrl().resolve("/hook").toURL().openConnection(Proxy.NO_PROXY);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write("{}".getBytes(StandardCharsets.UTF_8));
                }
                statuses.add(connection.getResponseCode());
                try (InputStream in = connection.getInputStream()) {
                    answers.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            } catch (IOException cause) {
                throw new AssertionError("the training's server does not answer", cause);
            }
            return new Commands.Result(0, "");
        };

        new CommandAotCache(probing, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)).make(copy, java()); // no recording is made by this fake: the warning is not this case's

        assertEquals(List.of(200), statuses);
        assertEquals(List.of("{}"), answers);
        assertEquals(ProcessHandle.current().pid(), given.get().pid(), "the server is announced as this process's, which lives while the training runs");
        assertFalse(Files.exists(copy.folder().resolve(CommandAotCache.TRAINING_ADDRESS_FILE)), "the address file is removed with the training");
        assertThrows(ConnectException.class, () -> {
            HttpURLConnection after = (HttpURLConnection) given.get().baseUrl().toURL().openConnection(Proxy.NO_PROXY);
            after.setConnectTimeout(5_000);
            after.getResponseCode();
        }, "the training's server is stopped");
    }

    // ------------------------------------------------------------------ the cases' tools

    /**
     * A version folder that holds the client's jar alone (copied from the installed version, the class path of the client is that one jar) and has the cache {@link CommandAotCache#make}
     * makes for it: one case may damage the cache or the jar without another case seeing it.
     */
    private InstalledVersion trainedCopy(String name) throws Exception {
        InstalledVersion copy = copyWithoutCache(name);
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        new CommandAotCache(new JavaCommands(), new PrintStream(said, true, StandardCharsets.UTF_8)).make(copy, java());
        assertEquals("", said.toString(StandardCharsets.UTF_8), "the training of " + name + " said nothing: it worked");
        assertTrue(Files.size(copy.folder().resolve("sennard-hook.aot")) > 0, "the cache of " + name + " exists");
        return copy;
    }

    /** A version folder with the client's jar alone and no cache. */
    private InstalledVersion copyWithoutCache(String name) throws IOException {
        InstalledVersion copy = new InstalledVersion(shared.resolve(name + "-" + COUNTER.incrementAndGet()).resolve("6.0.0"));
        Path lib = Files.createDirectories(copy.folder().resolve("lib"));
        Files.copy(original.hookClientJar(), lib.resolve(original.hookClientJar().getFileName()));
        return copy;
    }

    private static void assertSameAnswer(InstalledVersion version) throws Exception {
        assertEquals(reference, hook(version, noServer), "the same exit code, standard output and standard error as the run that has the cache");
    }

    /** A server that answers every request with this body, as the real one answers a hook. */
    private static HttpServer answering(String body) throws IOException {
        byte[] answer = body.getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        return server;
    }

    /** The address file such a server would write: its port and this test's own process, which stands for the server's (a file that names a process that lives). */
    private static Path announce(HttpServer server) throws IOException {
        Path file = shared.resolve("server-" + COUNTER.incrementAndGet() + ".properties");
        ProcessHandle self = ProcessHandle.current();
        new ServerAddress(server.getAddress().getPort(), self.pid(), self.info().startInstant().orElseThrow()).writeAtomically(file);
        return file;
    }

    private static String trainingInput() throws IOException {
        return new String(new ClassPathResource(CommandAotCache.TRAINING_INPUT).getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * The words with {@code -Xlog:class+load:file=<name>} as the last of the JVM options (just before {@code -cp}). The file is a name and not a path: the runs are made in the test's
     * folder, which is where the name is written, so no drive letter's colon is among the colons that separate the option's parts.
     * <b>Unverified, the lead confirms it on the first run:</b> the client's options begin with {@code -Xlog:disable}; the file log is added after it, on the assumption that the
     * JVM reads its {@code -Xlog} options in order, so that this one switches a file log on again. If it does not, the log file stays empty and {@link #readLoads} fails by saying so.
     */
    private static List<String> withLog(List<String> words, Path log) {
        List<String> logged = new ArrayList<>(words);
        logged.add(logged.indexOf("-cp"), "-Xlog:class+load:file=" + log.getFileName());
        return logged;
    }

    private static Path logFile() {
        return shared.resolve("class-load-" + COUNTER.incrementAndGet() + ".txt");
    }

    /** One hook run of a Gemini BeforeModel call with the training's own input on standard input. */
    static Result hook(InstalledVersion version, Path serverFile) throws Exception {
        return run(HookClientCommand.hookProgram(java(), version, serverFile), GEMINI_CALL, trainingInput(), Map.of());
    }

    /**
     * One run of the command's words and the call's arguments, {@code input} on standard input, in the test's folder. The wait for the end is for a process that never ends: the
     * run is not timed.
     */
    private static Result run(List<String> words, List<String> call, String input, Map<String, String> environment) throws Exception {
        Path directory = Files.createDirectories(shared.resolve("run-" + COUNTER.incrementAndGet()));
        Path stdin = directory.resolve("in.json");
        Files.writeString(stdin, input, StandardCharsets.UTF_8);
        Path out = directory.resolve("out.txt");
        Path err = directory.resolve("err.txt");
        List<String> command = new ArrayList<>(words);
        command.addAll(call);
        ProcessBuilder builder = new ProcessBuilder(command).directory(shared.toFile()).redirectInput(stdin.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().putAll(environment);
        Process process = builder.start();
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError(words.getFirst() + " did not end within two minutes");
        }
        return new Result(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8), Files.readString(err, StandardCharsets.UTF_8));
    }

    /** A hook run that logs every class load to a file, counted by where the class came from. */
    static Loads loads(InstalledVersion version, Path serverFile) throws Exception {
        Path log = logFile();
        run(withLog(HookClientCommand.hookProgram(java(), version, serverFile), log), GEMINI_CALL, trainingInput(), Map.of());
        return readLoads(log);
    }

    private static Loads readLoads(Path log) throws IOException {
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        assertFalse(lines.isEmpty(), "the class-load log is empty: the JVM did not write it (the file log is added after -Xlog:disable, see withLog)");
        return new Loads(lines.size(), lines.stream().filter(line -> line.contains("shared objects file")).count(), lines.stream().filter(line -> line.contains("source: file:")).count(),
                lines.stream().filter(line -> FRAMEWORK.matcher(line).find()).count());
    }

    /** Files.walkFileTree and Files.copy with the attributes kept, so a copy has the file times the original has. */
    static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
