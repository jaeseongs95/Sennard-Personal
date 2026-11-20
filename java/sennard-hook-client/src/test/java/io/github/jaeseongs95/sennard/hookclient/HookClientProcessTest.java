// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The hook client as a host runs it: its real entry point in a Java process of its own, on this module's classes and resources alone ({@code sennard.hookclient.classpath}, set by this
 * module's build to the module's main output: no Spring, no Jackson, no SLF4J, no other module is on that class path, as in the jar the host's command names), with a small heap
 * ({@code -Xmx} from {@code sennard.test.childHeap}), the serial collector and the JVM's own class-load log written to a file. The streams are the process's: stdin comes from a file,
 * stdout and stderr go to files, and what the host would read is read from them as bytes. Nothing here is timed: the one wait is the end of the process, with a bound only so that a
 * process that hangs fails the case. How fast the client starts is not asserted; what makes it fast is: the classes it loads. Case table (input: the command words
 * {@code hook gemini BeforeModel --server-file=<file> --on-failure=allow-decision}, stdin the Gemini {@code BeforeModel} input of a 4 MB conversation, the environment below; expected):
 * <ol>
 * <li>a server that the file announces answers (the file is real, of the test's own live process, and the child checks the liveness through the JDK's {@code ProcessHandle}): stdout is the server's answer byte for byte (UTF-8 beyond ASCII), exit 0, nothing on stderr; the server received one POST to
 * {@code /hooks/v1/gemini/BeforeModel} whose body is the common fields and the model and has no part of the conversation, and {@code X-Sennard-Time-Zone: Asia/Seoul} (from {@code TZ})</li>
 * <li>the file announces a server whose port nobody listens on: stdout is {@code {"decision":"allow"}} (the call's failure answer), exit 0, nothing on stderr</li>
 * <li>in both runs the JVM's class-load log has the client's entry point (the log is not empty or a log of another process) and no class of org.springframework, com.fasterxml,
 * tools.jackson, ch.qos or org.slf4j</li>
 * <li>the MCP relay ({@code McpRelayMain}) in a JVM of its own, on the same class path and with the same log: a JSON-RPC request on stdin is posted to {@code /mcp} of the announced server with the
 * host's label and its answer is a line of stdout; the log has the relay's entry point and no class of those libraries; the process ends with stdin</li>
 * </ol>
 * The child's environment is built from nothing, not inherited: the host's environment is the host's and the test's is the build's (Gradle's isolated home and temp folders, the
 * variables of the session that runs the build), so the child has {@code TZ=Asia/Seoul}, the variable the client reads, and, on Windows, {@code SystemRoot}, which the Windows
 * socket library needs to open a connection; no other variable. (If a platform needs more than that for a JVM to start, the minimum to inherit is added here, with its reason.)
 */
class HookClientProcessTest {
    private static final String ENTRY_POINT = "io.github.jaeseongs95.sennard.hookclient.HookClientMain";
    private static final String RELAY_ENTRY_POINT = "io.github.jaeseongs95.sennard.hookclient.McpRelayMain";
    /** The libraries the client must never load: they are what a host would pay for at every hook. */
    private static final List<String> FORBIDDEN_PACKAGES = List.of("org.springframework.", "com.fasterxml.", "tools.jackson.", "ch.qos.", "org.slf4j.");
    /** A line of {@code -Xlog:class+load}: {@code [0.012s][info][class,load] java.lang.Object source: shared objects file}; the class name is the first word after the tags. */
    private static final Pattern CLASS_LOAD = Pattern.compile("\\[class,load\\]\\s+(\\S+)");
    private static final String TIME_ZONE = "Asia/Seoul";
    private static final String MODEL = "gemini-3-pro";
    private static final String CONVERSATION_TURN = "y".repeat(1_048_576);
    private static final String BEFORE_MODEL = "{\"session_id\":\"g1\",\"transcript_path\":\"/t.json\",\"cwd\":\"/w\",\"hook_event_name\":\"BeforeModel\",\"timestamp\":\"2026-11-11T00:00:00Z\","
            + "\"llm_request\":{\"model\":\"" + MODEL + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + CONVERSATION_TURN + "\"},{\"role\":\"model\",\"content\":\"" + CONVERSATION_TURN
            + " 한글 😀\"},{\"role\":\"user\",\"content\":\"" + CONVERSATION_TURN + "\"},{\"role\":\"model\",\"content\":\"" + CONVERSATION_TURN + "\"}],"
            + "\"config\":{\"temperature\":0.2,\"systemInstruction\":\"" + "s".repeat(10_000) + "\"},\"toolConfig\":{\"mode\":\"AUTO\"}}}";
    private static final String BEFORE_MODEL_SENT = "{\"session_id\":\"g1\",\"transcript_path\":\"/t.json\",\"cwd\":\"/w\",\"llm_request\":{\"model\":\"" + MODEL + "\"}}";

    @TempDir
    Path temporary;

    private HookServerStub stub;
    /** The address file the stub is announced in: the child reads it as a host's command does. */
    private Path serverFile;

    @BeforeEach
    void startStub() throws IOException {
        stub = new HookServerStub();
        serverFile = temporary.resolve(ServerAddress.FILE_NAME);
        stub.announceIn(serverFile);
    }

    @AfterEach
    void stopStub() {
        stub.close();
    }

    /** What the host sees of the process, and what the JVM logged of the classes it loaded. */
    private record ClientRun(int exit, byte[] stdout, String stderr, List<String> loadedClasses) {
    }

    /** The child's whole environment: see the class comment. */
    private static Map<String, String> childEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("TZ", TIME_ZONE);
        String systemRoot = System.getenv("SystemRoot"); // Windows only; the lookup is case-insensitive there and absent elsewhere
        if (systemRoot != null) {
            environment.put("SystemRoot", systemRoot);
        }
        return environment;
    }

    private ClientRun runClient(String name, Path serverFile) throws Exception {
        return runJvm(name, ENTRY_POINT, BEFORE_MODEL, List.of("hook", "gemini", "BeforeModel", "--server-file=" + serverFile, "--on-failure=allow-decision"));
    }

    /** The relay in a JVM of its own: the words of its command line are the ones a host's MCP registration carries ({@link McpRelayArguments}). */
    private ClientRun runRelay(String name, Path serverFile, String stdinText) throws Exception {
        return runJvm(name, RELAY_ENTRY_POINT, stdinText, List.of("--host=gemini", "--server-file=" + serverFile));
    }

    private ClientRun runJvm(String name, String entryPoint, String stdinText, List<String> words) throws Exception {
        String childHeap = System.getProperty("sennard.test.childHeap");
        String classPath = System.getProperty("sennard.hookclient.classpath");
        assertNotNull(childHeap, "sennard.test.childHeap is set by the build on every test task");
        assertNotNull(classPath, "sennard.hookclient.classpath is set by this module's build");
        Path folder = Files.createDirectory(temporary.resolve(name));
        Path stdin = folder.resolve("stdin.json");
        Path stdout = folder.resolve("stdout.bin");
        Path stderr = folder.resolve("stderr.txt");
        Files.writeString(stdin, stdinText, StandardCharsets.UTF_8);
        String classLog = "class-load.log"; // relative to the child's working directory (the folder): a path with a drive letter has a colon, which the log option also uses
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx" + childHeap, "-XX:+UseSerialGC", "-XX:-UsePerfData",
                "-Xlog:class+load:file=" + classLog, "-cp", classPath, entryPoint));
        command.addAll(words);
        ProcessBuilder builder = new ProcessBuilder(command).directory(folder.toFile()).redirectInput(stdin.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().clear();
        builder.environment().putAll(childEnvironment());
        Process process = builder.start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("the client did not end in 120 s; stderr: " + Files.readString(stderr));
        }
        return new ClientRun(process.exitValue(), Files.readAllBytes(stdout), Files.readString(stderr), loadedClasses(folder.resolve(classLog)));
    }

    private static List<String> loadedClasses(Path log) throws IOException {
        List<String> classes = new ArrayList<>();
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            Matcher match = CLASS_LOAD.matcher(line);
            if (match.find()) {
                classes.add(match.group(1));
            }
        }
        return classes;
    }

    /** The JVM log names the client's entry point (so it is the log of this process) and no class of a library the hook must not pay for. */
    private static void assertNoLibraryWasLoaded(ClientRun process) {
        assertNoLibraryWasLoaded(process, ENTRY_POINT);
    }

    private static void assertNoLibraryWasLoaded(ClientRun process, String entryPoint) {
        assertTrue(process.loadedClasses().contains("java.lang.Object"), "the class-load log has the JVM's classes: " + process.loadedClasses().size() + " lines read");
        assertTrue(process.loadedClasses().contains(entryPoint), "the class-load log is this program's: " + entryPoint + " is loaded");
        List<String> libraries = process.loadedClasses().stream().filter(name -> FORBIDDEN_PACKAGES.stream().anyMatch(name::startsWith)).toList();
        assertEquals(List.of(), libraries, "classes of Spring, Jackson, Logback or SLF4J were loaded by the client");
    }

    @Test
    void theClientInItsOwnJvmPostsTheReducedInputAnswersWithTheServersAnswerAndLoadsNoLibrary() throws Exception {
        ClientRun process = runClient("server-up", serverFile);

        assertEquals("", process.stderr());
        assertEquals(0, process.exit());
        assertEquals(HookServerStub.ANSWER, new String(process.stdout(), StandardCharsets.UTF_8));
        assertEquals(1, stub.received.size());
        HookServerStub.Received request = stub.received.get(0);
        assertEquals("POST", request.method());
        assertEquals("/hooks/v1/gemini/BeforeModel", request.path());
        assertEquals(BEFORE_MODEL_SENT, request.bodyText());
        assertTrue(request.bodyText().contains(MODEL));
        assertFalse(request.bodyText().contains("yyyy") || request.bodyText().contains("systemInstruction"), "no part of the conversation or the config reached the server");
        assertEquals(TIME_ZONE, request.headers().getFirst(HookHeaders.TIME_ZONE));
        assertTrue(process.loadedClasses().contains("io.github.jaeseongs95.sennard.hookclient.InputReducer"), "the input was reduced by the client's own reader");
        assertNoLibraryWasLoaded(process);
    }

    @Test
    void theClientInItsOwnJvmAnswersWithTheCallsFailureAnswerWhenNothingListensAndLoadsNoLibrary() throws Exception {
        Path gone = temporary.resolve("gone.properties");
        HookServerStub.announce(HookServerStub.unusedPort(), gone);

        ClientRun process = runClient("server-down", gone);

        assertEquals("", process.stderr());
        assertEquals(0, process.exit());
        assertEquals(HookClient.ALLOW_DECISION_JSON, new String(process.stdout(), StandardCharsets.UTF_8));
        assertTrue(stub.received.isEmpty(), "the stand-in on another port was not asked");
        assertNoLibraryWasLoaded(process);
    }

    @Test
    void theRelayInItsOwnJvmPostsARequestToTheMcpEndpointAnswersWithALineOfStdoutAndLoadsNoLibrary() throws Exception {
        String answer = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"serverInfo\":{\"name\":\"sennard\"},\"note\":\"한글 😀\"}}";
        stub.answer = answer.getBytes(StandardCharsets.UTF_8);
        stub.contentType = "application/json";
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}";

        ClientRun process = runRelay("relay", serverFile, request + "\n");

        assertEquals("", process.stderr());
        assertEquals(0, process.exit());
        assertEquals(answer + "\n", new String(process.stdout(), StandardCharsets.UTF_8), "the answer, one line, the stdio transport's line feed");
        assertEquals(1, stub.received.size());
        HookServerStub.Received received = stub.received.get(0);
        assertEquals("POST", received.method());
        assertEquals("/mcp", received.path());
        assertEquals(request, received.bodyText(), "the message as the host wrote it");
        assertEquals("gemini", received.headers().getFirst("Sennard-Host-Label"));
        assertEquals("application/json, text/event-stream", received.headers().getFirst("Accept"));
        assertEquals(TIME_ZONE, received.headers().getFirst(HookHeaders.TIME_ZONE));
        assertNoLibraryWasLoaded(process, RELAY_ENTRY_POINT);
    }
}
