// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.github.jaeseongs95.sennard.personal.ReleasedFolder;
import io.github.jaeseongs95.sennard.server.execution.StreamOutput;
import io.github.jaeseongs95.sennard.server.skills.SkillRunEnvironment;
import io.github.jaeseongs95.sennard.server.skills.SkillCheckService;
import io.github.jaeseongs95.sennard.server.skills.SkillCheckService.Outcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code run} command of test-engineering through {@link SkillCheckService} with the personal edition's real process runner: a real child JVM runs a small Java
 * source program ({@code java File.java}, the test JVM's own {@code java}), so nothing depends on the operating system. The cases:
 *
 * <pre>
 * a  a passing command answers a PASS receipt: exit 0, stdout and stderr and their digests, argv, executable, java-N, platform, equal snapshot digests; stdin reads end of input
 * b  a failing command answers a FAIL receipt with the exit code and the failure's stderr; timeout-ms 300000 (the upper bound) is accepted
 * c  red (FAIL), a production fix, green (PASS): the two receipts, saved as files, make check-proof CONSISTENT
 * d  a command still running at the limit is TIMED_OUT, and the process it started is gone when run answers
 * e  timeout-ms 0, -5, 300001 and a non-integer are refused and nothing runs
 * f  a snapshot outside the root (a ../ path, an absolute path, the {"file"} form) is refused; a snapshot that no longer matches the files is refused before anything runs
 * g  an empty argv, an empty item, a NUL, 129 items, an item of 8193 characters are refused and nothing runs
 * i  a command that changes, or deletes, a file of the snapshot is INPUT_CHANGED whatever its exit code was
 * j  (in ToolRulesServerTest) run_skill_check answers with a job and the job holds the receipt
 * k  output past what the runner keeps in memory of a program that ended by itself is SPAWN_ERROR with its exit code and the kept start;
 *    a program that cannot be started is SPAWN_ERROR; a blank environment-note is refused
 * l  a command that writes without end (stdout, then stderr) is not stopped for its output: it is killed at its limit (TIMED_OUT) with the kept start of the stream, its process gone;
 *    the whole of what a run wrote is in the log files of the state directory
 * m  a command that ends on its own and left a process it started running: that process is killed before run answers
 * n  the child's environment: no server variable outside the allow list; a variable the caller names is there; the default environmentNote names the caller's variables, not values;
 *    refused: an env that is not an object of strings, too many variables, a bad name or value (more in the cases below)
 * o  a batch file (.bat, .cmd) with an argument the Windows interpreter reads as syntax is refused and nothing runs; on Windows a real batch file runs with ordinary arguments
 * p  the working directory is the real path the snapshot was checked against
 * (the concurrency limit, the stop of the server and the clean-up at start are in EngineeringRunLifecycleTest)
 * </pre>
 */
class EngineeringRunTest {
    private static final String INVALID = "INVALID_INPUT";
    private static final String INTEGRITY = "INTEGRITY_FAILED";
    private static final String TIMEOUT_KEY = "practices.runner.timeoutmsMustBeWithin1300000";
    private static final String ARGV_KEY = "practices.runner.explicitBoundedArgvIsRequired";
    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();
    /** The heap of every JVM the tests start (the property is set on the Test tasks in java/build.gradle.kts): the JVM of a test command and the JVMs the programs of the tests start in turn. */
    private static final String JAVA_HEAP = "-Xmx" + System.getProperty("sennard.test.childHeap");
    private static final String REQ = "R-LIMIT";
    private static final String CALC_BUGGY = """
            public class Calc {
                static int limit(int n) {
                    return n + 1;
                }
            }
            """;
    private static final String CALC_FIXED = """
            public class Calc {
                static int limit(int n) {
                    return n;
                }
            }
            """;
    private static final String CHECK = """
            public class Check {
                public static void main(String[] args) {
                    int got = Calc.limit(2);
                    if (got != 2) {
                        throw new AssertionError("expected 2 but got " + got);
                    }
                    System.out.println("check ok");
                }
            }
            """;
    private static final String HELLO = """
            public class Hello {
                public static void main(String[] args) throws Exception {
                    int input = System.in.read();
                    System.out.println("hello " + String.join(",", args) + " stdin=" + input);
                    System.err.println("note on stderr");
                }
            }
            """;
    private static final String MARKER = """
            public class Marker {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("ran.txt"), "ran");
                }
            }
            """;
    private static final String MUTATE = """
            public class Mutate {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Path data = java.nio.file.Path.of("data.txt");
                    if (args.length > 0) {
                        java.nio.file.Files.delete(data);
                    } else {
                        java.nio.file.Files.writeString(data, "changed by the command");
                    }
                }
            }
            """;
    /** What the runner keeps of one stream in memory: its first and its last bytes. */
    private static final int MEMORY = StreamOutput.HEAD_BYTES + StreamOutput.TAIL_BYTES;
    private static final String LOUD = """
            public class Loud {
                public static void main(String[] args) {
                    System.out.print("x".repeat(1_500_000));
                }
            }
            """;
    /** Writes without end, a hundred thousand characters every ten milliseconds, to stdout or (with an argument) to stderr; its process id is in a file. */
    private static final String FLOOD = """
            public class Flood {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("flood.pid"), Long.toString(ProcessHandle.current().pid()));
                    String chunk = "x".repeat(100_000);
                    while (true) {
                        if (args.length > 0) {
                            System.err.print(chunk);
                            System.err.flush();
                        } else {
                            System.out.print(chunk);
                            System.out.flush();
                        }
                        Thread.sleep(10);
                    }
                }
            }
            """;
    /** Starts Flood.java as another JVM that writes into the very files the command's own output goes to (the output is inherited), writes its own process id, and sleeps: the one that writes is not the command. */
    private static final String FLOOD_PARENT = """
            public class FloodParent {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("parent.pid"), Long.toString(ProcessHandle.current().pid()));
                    String launcher = ProcessHandle.current().info().command().orElseThrow();
                    new ProcessBuilder(launcher, "@HEAP@", "Flood.java").redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT).start();
                    Thread.sleep(600_000);
                }
            }
            """.replace("@HEAP@", JAVA_HEAP);
    /** Starts another JVM of itself that sleeps, writes that JVM's process id, waits three seconds and ends normally, leaving the other one running. */
    private static final String LEAVER = """
            public class Leaver {
                public static void main(String[] args) throws Exception {
                    if (args.length > 0) {
                        Thread.sleep(600_000);
                        return;
                    }
                    String launcher = ProcessHandle.current().info().command().orElseThrow();
                    Process child = new ProcessBuilder(launcher, "@HEAP@", "Leaver.java", "child").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    java.nio.file.Files.writeString(java.nio.file.Path.of("child.pid"), Long.toString(child.pid()));
                    Thread.sleep(3_000);
                }
            }
            """.replace("@HEAP@", JAVA_HEAP);
    /** Prints the name of every environment variable it has, then whether the one variable the caller sets is there with the value it was given (the value itself is never printed). */
    private static final String ENV_NAMES = """
            public class EnvNames {
                public static void main(String[] args) {
                    System.getenv().keySet().stream().sorted().forEach(System.out::println);
                    String value = System.getenv("SENNARD_RUN_TEST_CALLER");
                    System.out.println("caller=" + (value == null ? "absent" : "kept-by-caller".equals(value) ? "yes" : "wrong"));
                }
            }
            """;
    /** Writes a file to say it runs, then sleeps ten minutes. */
    private static final String SLEEP = """
            public class Sleep {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("started.txt"), "started");
                    Thread.sleep(600_000);
                }
            }
            """;
    /** Writes what the runner keeps of a stream in memory plus the number of bytes its second argument names to stdout (first argument "out") or stderr ("err"), flushes, stays a second and a half, and ends with 0. */
    private static final String EXACT = """
            public class Exact {
                public static void main(String[] args) throws Exception {
                    int count = @MEMORY@ + Integer.parseInt(args[1]);
                    byte[] bytes = new byte[count];
                    java.util.Arrays.fill(bytes, (byte) 'x');
                    java.io.PrintStream target = "err".equals(args[0]) ? System.err : System.out;
                    target.write(bytes, 0, count);
                    target.flush();
                    Thread.sleep(1_500);
                }
            }
            """.replace("@MEMORY@", Integer.toString(MEMORY));
    private static final String CWD = """
            public class Cwd {
                public static void main(String[] args) {
                    System.out.println(System.getProperty("user.dir"));
                }
            }
            """;
    /** Starts another JVM of itself that sleeps, writes that JVM's process id, and sleeps too. */
    private static final String PARENT = """
            public class Parent {
                public static void main(String[] args) throws Exception {
                    if (args.length > 0) {
                        Thread.sleep(600_000);
                        return;
                    }
                    String launcher = ProcessHandle.current().info().command().orElseThrow();
                    Process child = new ProcessBuilder(launcher, "@HEAP@", "Parent.java", "child").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    java.nio.file.Files.writeString(java.nio.file.Path.of("child.pid"), Long.toString(child.pid()));
                    Thread.sleep(600_000);
                }
            }
            """.replace("@HEAP@", JAVA_HEAP);

    @TempDir
    static Path state;

    private static AnnotationConfigApplicationContext context;
    private static SkillCheckService service;

    @BeforeAll
    static void wire() {
        context = SkillCheckContext.open(state);
        service = context.getBean(SkillCheckService.class);
    }

    @AfterAll
    static void close() {
        context.close();
        context = null;
        service = null;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------- helpers

    private static Outcome run(Object... pairs) {
        return service.run("test-engineering", "run", TestJson.object(pairs));
    }

    private static Outcome snapshotCommand(Object... pairs) {
        return service.run("test-engineering", "snapshot", TestJson.object(pairs));
    }

    private static JsonNode value(Outcome outcome) {
        assertNotNull(outcome.body().get("value"), () -> "a refused call: " + outcome.body());
        return outcome.body().get("value");
    }

    private static void refused(Outcome outcome, String code, String key) {
        assertEquals(2, outcome.exitCode(), () -> outcome.body().toString());
        assertEquals(code, outcome.body().get("code").stringValue(), () -> outcome.body().toString());
        assertEquals(key, outcome.body().get("messageKey").stringValue(), () -> outcome.body().toString());
        assertNotEquals(key, outcome.body().get("message").stringValue(), () -> "the catalog has no sentence for " + key);
    }

    private static void source(Path root, String file, String text) throws IOException {
        Files.writeString(root.resolve(file), text, StandardCharsets.UTF_8);
    }

    private static ObjectNode snapshot(Path root, String... files) {
        return (ObjectNode) value(snapshotCommand("root", root.toString(), "files", TestJson.array(List.of(files)))).deepCopy();
    }

    /** The command line of a test command: this JVM's java, the heap of the test's child JVMs, then the arguments. The receipt must carry exactly this argv. */
    private static List<String> java(String... arguments) {
        List<String> argv = new java.util.ArrayList<>(List.of(JAVA, JAVA_HEAP));
        argv.addAll(List.of(arguments));
        return argv;
    }

    private static Outcome runJava(Path root, JsonNode snapshot, long timeoutMs, String... javaArguments) {
        List<String> argv = new java.util.ArrayList<>(List.of(JAVA, JAVA_HEAP));
        argv.addAll(List.of(javaArguments));
        return run("root", root.toString(), "snapshot", snapshot, "timeout-ms", timeoutMs, "argv", TestJson.array(argv));
    }

    /** What every receipt this command answers must be: the contract's own members, sealed by its digest over the rest. */
    private static void assertSealed(JsonNode receipt) {
        assertEquals("engineering-run-receipt", receipt.get("kind").stringValue());
        assertEquals("2.0.0", receipt.get("schemaVersion").stringValue());
        assertTrue(receipt.has("exitCode"), "the contract requires the member, null or not: " + receipt);
        assertFalse(receipt.has("signal") || receipt.has("nodeVersion"), "the 5.x members are not in a 2.0.0 receipt: " + receipt);
        ObjectNode body = (ObjectNode) receipt.deepCopy();
        body.remove("digest");
        assertEquals(TestJson.hashJson(body), receipt.get("digest").stringValue());
        assertEquals(TestJson.hashText(receipt.get("stdout").stringValue()), receipt.get("stdoutDigest").stringValue());
        assertEquals(TestJson.hashText(receipt.get("stderr").stringValue()), receipt.get("stderrDigest").stringValue());
        assertEquals(Runtime.version().toString(), receipt.get("recorderRuntime").stringValue(), "the full version of the recording runtime");
        assertFalse(receipt.get("platform").stringValue().isBlank());
        assertTrue(Instant.parse(receipt.get("startedAt").stringValue()).compareTo(Instant.parse(receipt.get("finishedAt").stringValue())) <= 0);
    }

    /** A file under the root and the reference a proof carries: its path and the digest of its raw bytes. */
    private static ObjectNode save(Path root, String relative, JsonNode document) throws IOException {
        byte[] bytes = (TestJson.MAPPER.writeValueAsString(document) + "\n").getBytes(StandardCharsets.UTF_8);
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        return TestJson.object("path", relative, "digest", TestJson.hashBytes(bytes));
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------ a, b: PASS and FAIL

    @Test
    void aPassingCommandAnswersAPassReceiptOfTheRunAndItsInputs(@TempDir Path root) throws IOException {
        source(root, "Hello.java", HELLO);
        ObjectNode before = snapshot(root, "Hello.java");
        Outcome outcome = run("root", root.toString(), "snapshot", before, "argv", TestJson.array(java("Hello.java", "one", "two")));

        assertEquals(0, outcome.exitCode(), () -> outcome.body().toString());
        JsonNode receipt = value(outcome);
        assertSealed(receipt);
        assertEquals("PASS", receipt.get("status").stringValue());
        assertEquals(0, receipt.get("exitCode").intValue());
        assertTrue(receipt.get("stdout").stringValue().startsWith("hello one,two stdin=-1"), receipt.get("stdout").stringValue());
        assertTrue(receipt.get("stderr").stringValue().contains("note on stderr"));
        assertEquals(java("Hello.java", "one", "two"), TestJson.strings(receipt.get("argv")));
        assertEquals(JAVA, receipt.get("executable").stringValue());
        assertEquals(30000, receipt.get("timeoutMs").intValue(), "the default limit");
        assertEquals("Child environment: emptied, then the server's variables " + String.join(", ", SkillRunEnvironment.INHERITED_NAMES)
                + " when the server has them, then the variables set by the caller (names only; values are not recorded): none. Undeclared environmental inputs are not captured.",
                receipt.get("environmentNote").stringValue());
        assertEquals(before.get("digest"), receipt.get("snapshot").get("digest"));
        assertEquals(before.get("digest"), receipt.get("afterDigest"));
        assertEquals(before, receipt.get("snapshot"));
        assertTrue(someLogHolds("stdout.log", "hello one,two stdin=-1"), "the whole stdout of the run is in its log file in the state directory");
        assertTrue(someLogHolds("stderr.log", "note on stderr"), "so is its stderr, in a file of its own");
    }

    /** Whether the log file {@code name} of some execution in this class's state directory holds {@code text}. */
    private static boolean someLogHolds(String name, String text) throws IOException {
        Path logs = state.resolve("executions");
        if (!Files.isDirectory(logs)) {
            return false;
        }
        try (java.util.stream.Stream<Path> folders = Files.list(logs)) {
            for (Path folder : folders.toList()) {
                Path file = folder.resolve(name);
                if (Files.isRegularFile(file) && Files.readString(file).contains(text)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void aFailingCommandAnswersAFailReceiptWithItsExitCodeAndItsStderr(@TempDir Path root) throws IOException {
        source(root, "Calc.java", CALC_BUGGY);
        source(root, "Check.java", CHECK);
        ObjectNode before = snapshot(root, "Calc.java", "Check.java");
        Outcome outcome = runJava(root, before, 300000, "Check.java");

        assertEquals(1, outcome.exitCode(), () -> outcome.body().toString());
        JsonNode receipt = value(outcome);
        assertSealed(receipt);
        assertEquals("FAIL", receipt.get("status").stringValue());
        assertEquals(1, receipt.get("exitCode").intValue());
        assertTrue(receipt.get("stderr").stringValue().contains("AssertionError: expected 2 but got 3"), receipt.get("stderr").stringValue());
        assertEquals(300000, receipt.get("timeoutMs").intValue(), "the upper bound is accepted");
        assertEquals(receipt.get("snapshot").get("digest"), receipt.get("afterDigest"));
    }

    @Test
    void anEnvironmentNoteTheCallerGivesIsRecordedAndABlankOneIsRefused(@TempDir Path root) throws IOException {
        source(root, "Hello.java", HELLO);
        ObjectNode before = snapshot(root, "Hello.java");
        JsonNode receipt = value(run("root", root.toString(), "snapshot", before, "argv", TestJson.array(java("Hello.java")), "environment-note", "JDK as installed, no network"));
        assertEquals("JDK as installed, no network", receipt.get("environmentNote").stringValue());
        refused(run("root", root.toString(), "snapshot", before, "argv", TestJson.array(java("Hello.java")), "environment-note", " "), INVALID, "practices.runner.environmentNoteIsRequired");
    }

    // ------------------------------------------------------------------------------------------------------------------------------------- c: red, fix, green, proof

    @Test
    void aRedReceiptAProductionFixAndAGreenReceiptMakeCheckProofConsistent(@TempDir Path root) throws IOException {
        source(root, "Calc.java", CALC_BUGGY);
        source(root, "Check.java", CHECK);
        List<String> files = List.of("Calc.java", "Check.java");
        ObjectNode base = snapshot(root, "Calc.java", "Check.java");
        Outcome redRun = runJava(root, base, 60000, "Check.java");
        assertEquals("FAIL", value(redRun).get("status").stringValue(), () -> redRun.body().toString());
        ObjectNode redRef = save(root, "evidence/red.json", value(redRun));

        source(root, "Calc.java", CALC_FIXED);
        ObjectNode head = snapshot(root, "Calc.java", "Check.java");
        assertNotEquals(base.get("digest"), head.get("digest"));
        Outcome greenRun = runJava(root, head, 60000, "Check.java");
        assertEquals("PASS", value(greenRun).get("status").stringValue(), () -> greenRun.body().toString());
        ObjectNode greenRef = save(root, "evidence/green.json", value(greenRun));

        List<String> argv = java("Check.java");
        ObjectNode theCase = TestJson.object("id", "LIMIT-1", "requirementIds", TestJson.array(List.of(REQ)), "scenario", "A limit of two keeps two.", "entryPoint", "Calc.limit(int)", "level", "unit",
                "expectedBehavior", "Returns the limit itself.", "oracle", "The literal 2 derived from the frozen requirement.", "testFiles", TestJson.array(List.of("Check.java")), "required", true,
                "sensitivity", "red-green", "sensitivityReason", "An off-by-one in the production file must fail the same test.", "argv", TestJson.array(argv));
        ObjectNode plan = TestJson.object("schemaVersion", "1.0.0", "kind", "engineering-test-plan", "taskId", "demo-limit", "contractDigest", TestJson.hashText("task"), "requirements",
                TestJson.array(List.of(REQ)), "scopeFiles", TestJson.array(files), "runner", "java source launcher", "environment", "The test JVM's own java, no network.", "cases", TestJson.array(List.of(theCase)),
                "exclusions", TestJson.array(List.of("Negative limits are outside this example.")));
        ObjectNode entry = TestJson.object("caseId", "LIMIT-1", "status", "DEMONSTRATED", "red", redRef, "green", greenRef, "mutationPaths", TestJson.array(List.of("Calc.java")),
                "assertionWitness", "AssertionError", "reason", "The red run fails the assertion; the same test passes after the fix to Calc.java.");
        ObjectNode proof = TestJson.object("schemaVersion", "1.0.0", "kind", "engineering-test-proof", "planDigest", TestJson.hashJson(plan), "candidate", head, "proofs", TestJson.array(List.of(entry)));

        Outcome judged = service.run("test-engineering", "check-proof", TestJson.object("root", root.toString(), "plan", plan, "proof", proof));
        assertEquals(0, judged.exitCode(), () -> judged.body().toString());
        assertEquals("CONSISTENT", value(judged).get("verdict").stringValue(), () -> judged.body().toString());
        assertEquals(head.get("digest"), value(judged).get("targetDigest"));
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------ d: the limit

    @Test
    void aCommandStillRunningAtTheLimitIsTimedOutAndTheProcessItStartedIsKilledToo(@TempDir Path root) throws Exception {
        source(root, "Parent.java", PARENT);
        ObjectNode before = snapshot(root, "Parent.java");
        long pid = -1;
        try {
            Outcome outcome = runJava(root, before, 8000, "Parent.java");

            assertEquals(1, outcome.exitCode(), () -> outcome.body().toString());
            JsonNode receipt = value(outcome);
            assertSealed(receipt);
            // The status says the limit was reached (the command was still running when it ended) and the exit code is none, because the command did not end by itself: the run is
            // judged by what it answered, not by how long it took.
            assertEquals("TIMED_OUT", receipt.get("status").stringValue());
            assertTrue(receipt.get("exitCode").isNull());
            assertTrue(Files.exists(root.resolve("child.pid")), "the command had started its own process before the limit; raise the limit if this machine is that slow");
            pid = Long.parseLong(Files.readString(root.resolve("child.pid")));
            Optional<ProcessHandle> grandchild = ProcessHandle.of(pid);
            assertFalse(grandchild.map(ProcessHandle::isAlive).orElse(false), "the process the command started is gone when run answers");
            assertEquals(receipt.get("snapshot").get("digest"), receipt.get("afterDigest"));
            ReleasedFolder.await(root);
        } finally {
            if (pid > 0) {
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    // --------------------------------------------------------------------------------------------------------------------------------------------- e, f, g: refusals

    @Test
    void aLimitOutsideOneToThreeHundredThousandIsRefusedAndNothingRuns(@TempDir Path root) throws IOException {
        source(root, "Marker.java", MARKER);
        ObjectNode before = snapshot(root, "Marker.java");
        for (long limit : new long[] {0, -5, 300001, 3_000_000_000L}) {
            refused(runJava(root, before, limit, "Marker.java"), INVALID, TIMEOUT_KEY);
        }
        refused(run("root", root.toString(), "snapshot", before, "timeout-ms", "soon", "argv", TestJson.array(java("Marker.java"))), INVALID, "skillCheck.argumentMustBeInteger");
        assertFalse(Files.exists(root.resolve("ran.txt")), "a refused call starts nothing");
    }

    @Test
    void aSnapshotOutsideTheRootIsRefusedAndOneThatNoLongerHoldsIsRefusedBeforeTheCommandRuns(@TempDir Path root, @TempDir Path elsewhere) throws IOException {
        source(root, "Marker.java", MARKER);
        ObjectNode before = snapshot(root, "Marker.java");
        List<String> argv = java("Marker.java");
        Path outside = elsewhere.resolve("snapshot.json");
        Files.writeString(outside, TestJson.MAPPER.writeValueAsString(before));

        refused(run("root", root.toString(), "snapshot", "../" + elsewhere.getFileName() + "/snapshot.json", "argv", TestJson.array(argv)), INVALID, "practices.io.unsafePathComponent");
        refused(run("root", root.toString(), "snapshot", outside.toString(), "argv", TestJson.array(argv)), INVALID, "practices.io.absoluteDriveBackslashControlAndStream");
        refused(run("root", root.toString(), "snapshot", TestJson.object("file", outside.toString()), "argv", TestJson.array(argv)), INVALID, "practices.runner.snapshotMustBeUnderTheRoot");
        refused(run("root", "relative/root", "snapshot", before, "argv", TestJson.array(argv)), INVALID, "practices.io.aBoundedRelativePathIsRequired");

        // A snapshot file under the root is read; and a snapshot of files that have changed since is not run over.
        JsonNode viaFile = value(run("root", root.toString(), "snapshot", save(root, "snap.json", before).get("path").stringValue(), "argv", TestJson.array(argv)));
        assertEquals("PASS", viaFile.get("status").stringValue());
        Files.delete(root.resolve("ran.txt"));
        source(root, "Marker.java", MARKER + "// edited\n");
        refused(run("root", root.toString(), "snapshot", before, "argv", TestJson.array(argv)), INTEGRITY, "practices.core.currentFilesDifferFromTheDeclared");
        assertFalse(Files.exists(root.resolve("ran.txt")), "the command did not run over a snapshot that no longer holds");
    }

    @Test
    void anArgvThatIsEmptyOrHoldsANulOrIsOutOfBoundsIsRefusedAndNothingRuns(@TempDir Path root) throws IOException {
        source(root, "Marker.java", MARKER);
        ObjectNode before = snapshot(root, "Marker.java");
        List<List<String>> refusals = List.of(List.of(), List.of(JAVA, ""), List.of(JAVA, "Marker.java\0"), List.of("\0"), java.util.Collections.nCopies(129, JAVA), List.of(JAVA, "x".repeat(8193)));
        for (List<String> argv : refusals) {
            refused(run("root", root.toString(), "snapshot", before, "argv", TestJson.array(argv)), INVALID, ARGV_KEY);
        }
        refused(run("root", root.toString(), "snapshot", before), INVALID, ARGV_KEY);
        assertFalse(Files.exists(root.resolve("ran.txt")), "a refused call starts nothing");
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------ i, k: other endings

    @Test
    void aCommandThatChangesAFileOfTheSnapshotIsInputChangedWhateverItsExitCodeWas(@TempDir Path root) throws IOException {
        source(root, "Mutate.java", MUTATE);
        Files.writeString(root.resolve("data.txt"), "original");
        ObjectNode before = snapshot(root, "Mutate.java", "data.txt");
        Outcome outcome = runJava(root, before, 60000, "Mutate.java");

        assertEquals(1, outcome.exitCode(), () -> outcome.body().toString());
        JsonNode receipt = value(outcome);
        assertSealed(receipt);
        assertEquals("INPUT_CHANGED", receipt.get("status").stringValue());
        assertEquals(0, receipt.get("exitCode").intValue(), "what the command answered is kept");
        assertNotEquals(receipt.get("snapshot").get("digest"), receipt.get("afterDigest"));
        assertEquals("changed by the command", Files.readString(root.resolve("data.txt")));
    }

    @Test
    void aCommandThatDeletesAFileOfTheSnapshotIsInputChangedToo(@TempDir Path root) throws IOException {
        source(root, "Mutate.java", MUTATE);
        Files.writeString(root.resolve("data.txt"), "original");
        ObjectNode before = snapshot(root, "Mutate.java", "data.txt");
        Outcome outcome = runJava(root, before, 60000, "Mutate.java", "delete");

        JsonNode receipt = value(outcome);
        assertSealed(receipt);
        assertEquals("INPUT_CHANGED", receipt.get("status").stringValue());
        assertEquals(1, outcome.exitCode());
        assertFalse(Files.exists(root.resolve("data.txt")));
    }

    @Test
    void outputPastWhatTheRunnerKeepsOfAProgramThatEndedByItselfIsSpawnErrorWithItsExitCodeAndTheKeptStart(@TempDir Path root) throws IOException {
        source(root, "Loud.java", LOUD);
        ObjectNode before = snapshot(root, "Loud.java");
        Outcome loud = run("root", root.toString(), "snapshot", before, "timeout-ms", 60000, "argv", TestJson.array(java("Loud.java")));
        JsonNode receipt = value(loud);
        assertSealed(receipt);
        assertEquals("SPAWN_ERROR", receipt.get("status").stringValue());
        assertEquals(1, loud.exitCode());
        assertEquals(0, receipt.get("exitCode").intValue(), "the program ended by itself, and the exit code it ended with is recorded");
        assertEquals(StreamOutput.HEAD_BYTES, receipt.get("stdout").stringValue().length(), "the first bytes are kept");
        assertTrue(someLogHolds("stdout.log", "x".repeat(1_500_000)), "the whole of what it wrote is in the log file");
    }

    @Test
    void aProgramThatCannotBeStartedIsSpawnError(@TempDir Path root) throws IOException {
        source(root, "Loud.java", LOUD);
        ObjectNode before = snapshot(root, "Loud.java");
        Outcome missing = run("root", root.toString(), "snapshot", before, "argv", TestJson.array(List.of(root.resolve("no-such-program").toString())));
        JsonNode notStarted = value(missing);
        assertSealed(notStarted);
        assertEquals("SPAWN_ERROR", notStarted.get("status").stringValue());
        assertTrue(notStarted.get("exitCode").isNull());
        assertEquals(1, missing.exitCode());
    }

    // ------------------------------------------------------------------------------------------------------------------------- l: the output bound while it runs

    @Test
    void aCommandThatWritesWithoutEndIsNotStoppedForItsOutputAndIsKilledAtItsLimit(@TempDir Path root) throws Exception {
        source(root, "Flood.java", FLOOD);
        ObjectNode before = snapshot(root, "Flood.java");
        for (boolean toStderr : new boolean[] {false, true}) {
            Files.deleteIfExists(root.resolve("flood.pid"));
            Outcome outcome = toStderr ? runJava(root, before, 5000, "Flood.java", "stderr") : runJava(root, before, 5000, "Flood.java");

            JsonNode receipt = value(outcome);
            assertSealed(receipt);
            // The runner does not stop a command for what it writes: it ran until its limit and was killed there.
            assertEquals("TIMED_OUT", receipt.get("status").stringValue(), () -> "stderr " + toStderr + ": " + receipt.get("status"));
            assertEquals(1, outcome.exitCode());
            assertTrue(receipt.get("exitCode").isNull(), "it did not end by itself");
            assertEquals(StreamOutput.HEAD_BYTES, receipt.get(toStderr ? "stderr" : "stdout").stringValue().length(), "the first bytes of the stream it flooded are kept");
            assertEquals("", receipt.get(toStderr ? "stdout" : "stderr").stringValue());
            long pid = Long.parseLong(Files.readString(root.resolve("flood.pid")));
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "the flooding process is gone when run answers");
        }
        ReleasedFolder.await(root);
    }

    @Test
    void aProcessTheCommandStartedThatWritesWithoutEndIsKilledWithTheCommandAtItsLimit(@TempDir Path root) throws Exception {
        source(root, "FloodParent.java", FLOOD_PARENT);
        source(root, "Flood.java", FLOOD);
        ObjectNode before = snapshot(root, "FloodParent.java", "Flood.java");
        Outcome outcome = runJava(root, before, 5000, "FloodParent.java");

        JsonNode receipt = value(outcome);
        assertSealed(receipt);
        // The command itself writes nothing: what is written is by the process it started, into the pipes of the command's output; the command is killed at its limit with it.
        assertEquals("TIMED_OUT", receipt.get("status").stringValue(), () -> receipt.get("status").toString());
        assertTrue(receipt.get("exitCode").isNull(), "it did not end by itself");
        assertEquals(StreamOutput.HEAD_BYTES, receipt.get("stdout").stringValue().length(), "the first bytes are kept");
        long parent = Long.parseLong(Files.readString(root.resolve("parent.pid")));
        long flooder = Long.parseLong(Files.readString(root.resolve("flood.pid")));
        assertFalse(ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false), "the command is gone when run answers");
        assertFalse(ProcessHandle.of(flooder).map(ProcessHandle::isAlive).orElse(false), "the process that wrote without end is gone when run answers");
        ReleasedFolder.await(root);
    }

    // ------------------------------------------------------------------------------------------------------------- m: what a command that ended leaves behind

    @Test
    void aCommandThatEndsOnItsOwnLeavesNoProcessItStartedAlive(@TempDir Path root) throws Exception {
        source(root, "Leaver.java", LEAVER);
        ObjectNode before = snapshot(root, "Leaver.java");
        long pid = -1;
        try {
            Outcome outcome = runJava(root, before, 60000, "Leaver.java");
            JsonNode receipt = value(outcome);
            assertEquals("PASS", receipt.get("status").stringValue(), () -> outcome.body().toString());
            assertTrue(Files.exists(root.resolve("child.pid")), "the command started its own process");
            pid = Long.parseLong(Files.readString(root.resolve("child.pid")));
            Optional<ProcessHandle> left = ProcessHandle.of(pid);
            assertFalse(left.map(ProcessHandle::isAlive).orElse(false), "the process the command started and left running is gone when run answers");
            left.ifPresent(handle -> handle.onExit().join());
            ReleasedFolder.await(root);
        } finally {
            if (pid > 0) {
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------- n: the child's environment

    @Test
    void theChildSeesNoVariableOfTheServerThatIsNotOnTheAllowList(@TempDir Path root) throws IOException {
        source(root, "EnvNames.java", ENV_NAMES);
        ObjectNode before = snapshot(root, "EnvNames.java");
        Set<String> listed = SkillRunEnvironment.INHERITED_NAMES.stream().map(name -> name.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        Set<String> serverOnly = System.getenv().keySet().stream().filter(name -> !listed.contains(name.toUpperCase(Locale.ROOT))).collect(Collectors.toCollection(TreeSet::new));
        assertFalse(serverOnly.isEmpty(), "the test JVM's own environment has variables beyond the list, or this case proves nothing");

        JsonNode receipt = value(runJava(root, before, 60000, "EnvNames.java"));
        assertEquals("PASS", receipt.get("status").stringValue(), receipt.toString());
        List<String> seen = receipt.get("stdout").stringValue().lines().filter(line -> !line.startsWith("caller=")).toList();
        List<String> notListed = seen.stream().filter(name -> !listed.contains(name.toUpperCase(Locale.ROOT))).toList();
        assertEquals(List.of(), notListed, "the child has only names of the list (the names of the server's variables it was not given are not printed)");
        assertTrue(seen.stream().anyMatch(name -> "PATH".equalsIgnoreCase(name)), "a listed name the server has is passed: " + seen);
        assertTrue(receipt.get("stdout").stringValue().contains("caller=absent"));
    }

    private static Outcome runWithEnv(Path root, JsonNode snapshot, JsonNode env, String... javaArguments) {
        List<String> argv = new java.util.ArrayList<>(List.of(JAVA, JAVA_HEAP));
        argv.addAll(List.of(javaArguments));
        return run("root", root.toString(), "snapshot", snapshot, "timeout-ms", 60000, "argv", TestJson.array(argv), "env", env);
    }

    @Test
    void aVariableTheCallerNamesIsSeenByTheChildAndTheDefaultNoteNamesItButNeverItsValue(@TempDir Path root) throws IOException {
        source(root, "EnvNames.java", ENV_NAMES);
        ObjectNode before = snapshot(root, "EnvNames.java");

        JsonNode receipt = value(runWithEnv(root, before, TestJson.object("SENNARD_RUN_TEST_CALLER", "kept-by-caller", "SENNARD_RUN_TEST_OTHER", "x"), "EnvNames.java"));

        assertEquals("PASS", receipt.get("status").stringValue(), receipt.toString());
        assertTrue(receipt.get("stdout").stringValue().contains("caller=yes"), receipt.get("stdout").stringValue());
        String note = receipt.get("environmentNote").stringValue();
        assertTrue(note.contains("SENNARD_RUN_TEST_CALLER, SENNARD_RUN_TEST_OTHER"), note);
        assertFalse(note.contains("kept-by-caller"), "values are not recorded: " + note);
        assertEquals(note, value(runWithEnv(root, before, TestJson.object("SENNARD_RUN_TEST_OTHER", "y", "SENNARD_RUN_TEST_CALLER", "kept-by-caller"), "EnvNames.java")).get("environmentNote").stringValue(),
                "the same names give the same note, whatever their order (red and green must agree)");
        JsonNode own = value(run("root", root.toString(), "snapshot", before, "argv", TestJson.array(java("EnvNames.java")), "env", TestJson.object("SENNARD_RUN_TEST_CALLER", "kept-by-caller"),
                "environment-note", "the caller's own note"));
        assertEquals("the caller's own note", own.get("environmentNote").stringValue(), "a note the caller gives is kept as written");
    }

    @Test
    void anEnvThatIsNotAnObjectOfStringsOrIsOutOfBoundsIsRefusedAndNothingRuns(@TempDir Path root) throws IOException {
        source(root, "Marker.java", MARKER);
        ObjectNode before = snapshot(root, "Marker.java");
        for (JsonNode notStrings : List.of(TestJson.array(List.of("A=b")), TestJson.object("A", 1), TestJson.object("A", true), TestJson.object("A", TestJson.array(List.of("x"))))) {
            refused(runWithEnv(root, before, notStrings, "Marker.java"), INVALID, "skillCheck.argumentMustBeObjectOfStrings");
        }
        refused(run("root", root.toString(), "snapshot", before, "argv", TestJson.array(java("Marker.java")), "env", "A=b"), INVALID, "skillCheck.argumentMustBeObjectOfStrings");
        List<JsonNode> outOfBounds = new java.util.ArrayList<>();
        outOfBounds.add(TestJson.object("", "v"));
        outOfBounds.add(TestJson.object("A=B", "v"));
        outOfBounds.add(TestJson.object("A\0B", "v"));
        outOfBounds.add(TestJson.object("A\nB", "v"));
        outOfBounds.add(TestJson.object("A", "v\0w"));
        outOfBounds.add(TestJson.object("N".repeat(129), "v"));
        outOfBounds.add(TestJson.object("A", "v".repeat(8193)));
        ObjectNode tooMany = TestJson.object();
        for (int i = 0; i < 33; i++) {
            tooMany.put("SENNARD_RUN_TEST_" + i, "v");
        }
        outOfBounds.add(tooMany);
        for (JsonNode env : outOfBounds) {
            refused(runWithEnv(root, before, env, "Marker.java"), INVALID, "practices.runner.explicitBoundedEnvIsRequired");
        }
        ObjectNode atTheBounds = TestJson.object();
        for (int i = 0; i < 31; i++) {
            atTheBounds.put("SENNARD_RUN_TEST_" + i, "v");
        }
        atTheBounds.put("N".repeat(128), "v".repeat(8192));
        assertEquals("PASS", value(runWithEnv(root, before, atTheBounds, "Marker.java")).get("status").stringValue(), "32 variables, the longest name and the longest value are accepted");
    }

    // ------------------------------------------------------------------------------------------------------------------------------------- p: the working directory

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theCommandRunsInTheRealPathOfTheRootNotInTheTextTheCallerWrote(@TempDir Path root) throws IOException {
        source(root, "Cwd.java", CWD);
        ObjectNode before = snapshot(root, "Cwd.java");
        String written = Character.toLowerCase(root.toString().charAt(0)) + root.toString().substring(1);
        assertNotEquals(root.toString(), written, "the drive letter of the temporary folder is upper case, so the lower case spelling differs from the real path");

        JsonNode receipt = value(run("root", written, "snapshot", before, "argv", TestJson.array(java("Cwd.java"))));

        assertEquals("PASS", receipt.get("status").stringValue(), receipt.toString());
        assertEquals(root.toRealPath().toString(), receipt.get("stdout").stringValue().strip(), "Windows keeps the spelling a directory is started with, so the real path is what is passed");
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------- o: batch files

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aRealBatchFileRunsWithOrdinaryArgumentsUnderTheEmptiedEnvironmentAndAnInjectionIsRefused(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("echoargs.bat"), "@echo off\r\necho first=[%1] second=[%2]\r\necho all=[%*]\r\n");
        ObjectNode before = snapshot(root, "echoargs.bat");
        String batch = root.resolve("echoargs.bat").toString();

        JsonNode receipt = value(run("root", root.toString(), "snapshot", before, "argv", TestJson.array(List.of(batch, "build", "--no-daemon=1"))));
        assertEquals("PASS", receipt.get("status").stringValue(), receipt.toString());
        assertTrue(receipt.get("stdout").stringValue().contains("first=[build] second=[--no-daemon]"), "the interpreter itself splits an argument at an equals sign: " + receipt.get("stdout").stringValue());
        assertTrue(receipt.get("stdout").stringValue().contains("all=[build --no-daemon=1]"), "what a wrapper such as gradlew.bat reads (%*) is the argument as written: " + receipt.get("stdout").stringValue());

        refused(run("root", root.toString(), "snapshot", before, "argv", TestJson.array(List.of(batch, "a\"&echo INJECTED"))), INVALID, "practices.runner.batchFileArgumentsUnsafe");
    }

    @Test
    void aBatchFileWithAnArgumentTheInterpreterWouldReadAsSyntaxIsRefusedAndNothingRuns(@TempDir Path root) throws IOException {
        source(root, "Marker.java", MARKER);
        ObjectNode before = snapshot(root, "Marker.java");
        String batch = root.resolve("run.bat").toString();
        for (String argument : List.of("a&echo INJECTED", "a\"&echo INJECTED", "%PATHEXT%", "a>ran.txt", "a|b", "a^b", "a!b", "a\nb")) {
            refused(run("root", root.toString(), "snapshot", before, "argv", TestJson.array(List.of(batch, "first", argument))), INVALID, "practices.runner.batchFileArgumentsUnsafe");
        }
        refused(run("root", root.toString(), "snapshot", before, "argv", TestJson.array(List.of(root.resolve("run.CMD").toString(), "a&b"))), INVALID, "practices.runner.batchFileArgumentsUnsafe");
        assertFalse(Files.exists(root.resolve("ran.txt")), "a refused call starts nothing");
    }

    // ------------------------------------------------------------------------------------------------------------------------ q: where the program is found

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aProgramNamedWithAPathIsFoundRelativeToTheRootAndABareNameIsLeftToThePathSearch(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("echoargs.bat"), "@echo off\r\necho first=[%1]\r\n");
        Files.writeString(Files.createDirectories(root.resolve("tools")).resolve("echoargs.bat"), "@echo off\r\necho first=[%1] in tools\r\n");
        ObjectNode before = snapshot(root, "echoargs.bat", "tools/echoargs.bat");
        for (String program : List.of(".\\echoargs.bat", "./echoargs.bat", "tools\\echoargs.bat", "tools/echoargs.bat")) {
            JsonNode receipt = value(run("root", root.toString(), "snapshot", before, "argv", TestJson.array(List.of(program, "x"))));
            assertEquals("PASS", receipt.get("status").stringValue(), () -> program + ": " + receipt);
            assertTrue(receipt.get("stdout").stringValue().contains(program.startsWith("tools") ? "first=[x] in tools" : "first=[x]"), () -> program + ": " + receipt.get("stdout").stringValue());
            assertEquals(program, receipt.get("executable").stringValue(), "the receipt names the program as the caller wrote it");
        }
        // A name without a separator is the operating system's own search (the server's PATH and its own folders), not the root: the file under the root is not found.
        JsonNode bare = value(run("root", root.toString(), "snapshot", before, "argv", TestJson.array(List.of("echoargs.bat", "x"))));
        assertEquals("SPAWN_ERROR", bare.get("status").stringValue(), bare.toString());
        assertTrue(bare.get("exitCode").isNull());
        ReleasedFolder.await(root);
    }

    // ------------------------------------------------------------------------------------------------------------------------------ r: the bound of the output

    @Test
    void exactlyWhatTheRunnerKeepsInMemoryOnEachStreamIsKeptWhole(@TempDir Path root) throws IOException {
        source(root, "Exact.java", EXACT);
        ObjectNode before = snapshot(root, "Exact.java");
        for (String stream : List.of("out", "err")) {
            // The program stays alive after it wrote: what it wrote is judged while it runs and at its end.
            JsonNode receipt = value(runJava(root, before, 60000, "Exact.java", stream, "0"));
            assertSealed(receipt);
            assertEquals("PASS", receipt.get("status").stringValue(), () -> stream + ": " + receipt.get("status"));
            assertEquals(0, receipt.get("exitCode").intValue());
            String kept = receipt.get("out".equals(stream) ? "stdout" : "stderr").stringValue();
            assertEquals(MEMORY, kept.length(), "all of it is kept");
            assertEquals("", receipt.get("out".equals(stream) ? "stderr" : "stdout").stringValue());
        }
    }

    @Test
    void oneByteOverWhatTheRunnerKeepsInMemoryOnEitherStreamIsSpawnErrorWithTheStartKept(@TempDir Path root) throws IOException {
        source(root, "Exact.java", EXACT);
        ObjectNode before = snapshot(root, "Exact.java");
        for (String stream : List.of("out", "err")) {
            Outcome outcome = runJava(root, before, 60000, "Exact.java", stream, "1");
            JsonNode receipt = value(outcome);
            assertSealed(receipt);
            assertEquals("SPAWN_ERROR", receipt.get("status").stringValue(), () -> stream + ": " + receipt.get("status"));
            assertEquals(0, receipt.get("exitCode").intValue());
            assertEquals(StreamOutput.HEAD_BYTES, receipt.get("out".equals(stream) ? "stdout" : "stderr").stringValue().length(), "the first bytes are kept");
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------ s: an interrupted run

    @Test
    void anInterruptedRunIsNotJudgedAsAChangedInputKeepsTheInterruptAndLeavesNoOutputFolder(@TempDir Path root) throws Exception {
        source(root, "Sleep.java", SLEEP);
        ObjectNode before = snapshot(root, "Sleep.java");
        java.util.concurrent.atomic.AtomicReference<Outcome> answer = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interruptKept = new java.util.concurrent.atomic.AtomicBoolean();
        Thread caller = Thread.ofPlatform().start(() -> {
            answer.set(runJava(root, before, 120000, "Sleep.java"));
            interruptKept.set(Thread.currentThread().isInterrupted());
        });
        Instant end = Instant.now().plusSeconds(90);
        while (!Files.exists(root.resolve("started.txt")) && Instant.now().isBefore(end)) {
            Thread.sleep(50);
        }
        assertTrue(Files.exists(root.resolve("started.txt")), "the command did not start in time");

        caller.interrupt();
        caller.join(Duration.ofSeconds(90));
        assertFalse(caller.isAlive(), "the interrupted run answered");

        JsonNode receipt = value(answer.get());
        assertSealed(receipt);
        // The files were read again after the interrupt: not being able to read them is not a change of the input.
        assertEquals("SPAWN_ERROR", receipt.get("status").stringValue(), receipt.toString());
        assertTrue(receipt.get("exitCode").isNull(), "the command was killed, it did not end by itself");
        assertEquals(receipt.get("snapshot").get("digest"), receipt.get("afterDigest"));
        assertTrue(interruptKept.get(), "the caller's interrupt is still set when run answers");
        ReleasedFolder.await(root);
    }
}
