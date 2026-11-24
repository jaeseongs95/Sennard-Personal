// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import static io.github.jaeseongs95.sennard.personal.RunnerFixture.JAVA_HEAP;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.alive;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.awaitCondition;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.awaitFile;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.awaitPid;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.environment;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.java;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.program;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.request;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.server.execution.EnvironmentSpec;
import io.github.jaeseongs95.sennard.server.execution.ExecutionHandle;
import io.github.jaeseongs95.sennard.server.execution.ExecutionIds;
import io.github.jaeseongs95.sennard.server.execution.ExecutionRequest;
import io.github.jaeseongs95.sennard.server.execution.ExecutionSnapshot;
import io.github.jaeseongs95.sennard.server.execution.ExecutionState;
import io.github.jaeseongs95.sennard.server.execution.Refused;
import io.github.jaeseongs95.sennard.server.execution.StreamOutput;
import io.github.jaeseongs95.sennard.server.skills.SkillRunEnvironment;

/**
 * The runner of executions with real programs (small Java programs run by the test JVM's own {@code java}); no program of the operating system and no shell. The order of what
 * happens is fixed by files the programs write and wait for.
 *
 * <pre>
 * x1  an exit               a program that ends is EXITED with its exit code (0 and not 0), stdout and stderr kept apart, in memory and in a log file of its own each; the id, the process
 *                           (id and start time) and the times are there
 * x2  not started           a program that does not exist, a working directory that is not a folder: NOT_STARTED with its own id, no process, no log folder, found by its id, its place is free
 * x3  the time limit        a program that is still running at its limit is TIMED_OUT, and it and the process it started are gone
 * x4  cancel                a running program is KILLED by a cancel (it and what it started are gone), a cancel again changes nothing, a program that ended is not made KILLED
 * x5  the wait              a wait that is over before the end answers RUNNING with the output so far, the execution is found by its id and ends later; a wait that is long enough answers the end
 * x6  the limit             with one place, a second execution is refused at once (TOO_MANY_RUNS) and starts nothing; when the first has ended the place is used again
 * x7  the server's stop     kills the running program and what it started, refuses later executions, and the runner is not running
 * x8  output below limits   all of it is in memory (head only) and in the log file, nothing is marked
 * x9  output over the       the first 64 KiB and the last 256 KiB are in memory, the count of what is between is right, the log file has all of it
 *     memory
 * x10 the log limit         a stream that passes the limit of its log file: the program is not killed and ends as it would, the file stops at the limit and is marked, the end of the output is still in
 *                           memory, the other stream is not marked
 * x11 environment           of the server's only the names the request lists (no default: one not named is not there); the request's variables added and replacing one of the same name
 * x12 retention             the runner keeps the running and the last N finished executions
 * x13 an argument that      a batch file with an argument the Windows command interpreter would read as syntax is refused on every system, and nothing is made
 *     is unsafe
 * x14 no shell              a quote, a wildcard, {@code $PATH}, {@code %PATH%}, spaces and shell metacharacters in the arguments arrive in the program as they were written
 * x15 the stop of several   every running program (three, each with a process it started) is KILLED with what it started, and all have ended when stop returns
 * x16 running ids       the runner says which executions run now (the ones a retention pass must not delete): the one that runs, not the one that ended, and none after the cancel
 * x17 the given id      the execution has the id the caller gave (the runner makes none), run or not run; an id not written as an execution id, one the runner holds (running, or finished and
 *                       kept) and one whose folder is there are rejected (IllegalArgumentException) with no folder made or removed, nothing held and no place kept
 * </pre>
 */
class LocalProcessRunnerTest {
    private static final Duration LONG = Duration.ofMinutes(2);
    private static final String ENDS = """
            public class Ends {
                public static void main(String[] args) {
                    System.out.print("out-text");
                    System.err.print("err-text");
                    System.exit(Integer.parseInt(args[0]));
                }
            }
            """;
    /** Prints a line and flushes, says it runs in a file, waits for the file go.txt, prints another line and ends. */
    private static final String WAITER = """
            public class Waiter {
                public static void main(String[] args) throws Exception {
                    System.out.println("ready");
                    System.out.flush();
                    java.nio.file.Files.writeString(java.nio.file.Path.of("started.txt"), Long.toString(ProcessHandle.current().pid()));
                    while (!java.nio.file.Files.exists(java.nio.file.Path.of("go.txt"))) {
                        Thread.sleep(20);
                    }
                    System.out.println("released");
                }
            }
            """;
    /** Writes its process id to the file its argument names, says it runs, and sleeps ten minutes. */
    private static final String SLEEPER = """
            public class Sleeper {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
                    Thread.sleep(600_000);
                }
            }
            """;
    /** Starts a Sleeper that writes child.pid, writes its own id to parent.pid, and sleeps too. */
    private static final String TREE = """
            public class Tree {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("parent.pid"), Long.toString(ProcessHandle.current().pid()));
                    new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "@HEAP@", "-cp", ".", "Sleeper", "child.pid")
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    Thread.sleep(600_000);
                }
            }
            """.replace("@HEAP@", JAVA_HEAP);
    /**
     * Writes {@code args[1]} bytes to stdout ({@code args[0]} is "out") or stderr ("err"): lines "line 00000001" and so on, and a last line of x if the count does not end a line.
     * The test makes the same bytes itself ({@link #expected}).
     */
    private static final String WRITER = """
            public class Writer {
                public static void main(String[] args) throws Exception {
                    java.io.PrintStream target = "err".equals(args[0]) ? System.err : System.out;
                    long count = Long.parseLong(args[1]);
                    long written = 0;
                    int line = 0;
                    java.io.ByteArrayOutputStream chunk = new java.io.ByteArrayOutputStream();
                    while (written < count) {
                        byte[] bytes = (written + 14 <= count ? String.format("line %08d", ++line) + (char) 10 : "x".repeat((int) (count - written))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        chunk.write(bytes);
                        written += bytes.length;
                        if (chunk.size() >= 65536 || written >= count) {
                            chunk.writeTo(target);
                            target.flush();
                            chunk.reset();
                        }
                    }
                }
            }
            """;
    /** Prints the value of each variable its arguments name, one to a line, "&lt;unset&gt;" for one the program does not have. */
    private static final String ENV = """
            public class Env {
                public static void main(String[] args) {
                    for (String name : args) {
                        String value = System.getenv(name);
                        System.out.println(value == null ? "<unset>" : value);
                    }
                }
            }
            """;

    /** The bytes {@code Writer} writes for {@code count}. */
    private static byte[] expected(long count) {
        StringBuilder text = new StringBuilder();
        int line = 0;
        long written = 0;
        while (written < count) {
            String next = written + 14 <= count ? String.format("line %08d", ++line) + '\n' : "x".repeat((int) (count - written));
            text.append(next);
            written += next.getBytes(StandardCharsets.UTF_8).length;
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static ExecutionSnapshot end(ExecutionHandle handle) throws InterruptedException {
        ExecutionSnapshot done = handle.await(LONG);
        assertTrue(done.state().finished(), () -> "the execution did not end in time: " + done);
        return done;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ x1

    @Test
    void aProgramThatEndsIsExitedWithItsExitCodeAndItsOutputKeptApartByStream(@TempDir Path state, @TempDir Path work) throws Exception {
        program(work, "Ends", ENDS);
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            ExecutionHandle zero = fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Ends", "0"));
            ExecutionSnapshot done = end(zero);

            assertEquals(zero.id(), done.id());
            assertEquals(ExecutionState.EXITED, done.state());
            assertEquals(0, done.exitCode());
            assertEquals("out-text", done.stdout().head());
            assertEquals("err-text", done.stderr().head());
            assertEquals("", done.stdout().tail());
            assertEquals(8, done.stdout().totalBytes());
            assertFalse(done.outputOmitted());
            assertFalse(done.logTruncated());
            assertNotNull(done.endedAt());
            assertFalse(done.endedAt().isBefore(done.requestedAt()));
            assertNotNull(done.process());
            assertTrue(done.process().pid() > 0);
            assertNotNull(done.process().startedAt(), "this system tells when a process started");
            Path logs = Path.of(done.logLocation());
            assertEquals(state.resolve("executions").resolve(done.id()), logs, "the logs are in a folder of the execution under the state folder");
            assertEquals("out-text", Files.readString(logs.resolve(ExecutionSnapshot.STDOUT_LOG)));
            assertEquals("err-text", Files.readString(logs.resolve(ExecutionSnapshot.STDERR_LOG)));
            assertSame(done, zero.ended().get(), "the future holds the final snapshot");
            assertEquals(done, zero.snapshot());

            ExecutionSnapshot failed = end(fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Ends", "3")));
            assertEquals(ExecutionState.EXITED, failed.state());
            assertEquals(3, failed.exitCode());
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ x2

    @Test
    void aProgramThatCannotBeStartedIsNotStartedWithItsOwnIdAndLeavesNothingAndItsPlaceIsFree(@TempDir Path state, @TempDir Path work) throws Exception {
        program(work, "Ends", ENDS);
        try (RunnerFixture fixture = new RunnerFixture(state, 1, 32)) {
            ExecutionHandle missing = fixture.runner.start(ExecutionIds.next(), request(List.of(work.resolve("no-such-program").toString()), work, LONG));
            ExecutionSnapshot first = missing.snapshot();
            assertEquals(ExecutionState.NOT_STARTED, first.state());
            assertNotNull(first.failure());
            assertNull(first.process());
            assertNull(first.logLocation());
            assertNull(first.exitCode());
            assertTrue(missing.ended().isDone());

            ExecutionHandle nowhere = fixture.runner.start(ExecutionIds.next(), java(work.resolve("nowhere"), LONG, "Ends", "0"));
            assertEquals(ExecutionState.NOT_STARTED, nowhere.snapshot().state());
            assertNotNull(nowhere.snapshot().failure());

            assertNotEquals(missing.id(), nowhere.id());
            assertEquals(Optional.of(missing), fixture.runner.find(missing.id()));
            assertEquals(missing.snapshot(), missing.await(Duration.ZERO));
            assertFalse(Files.exists(state.resolve("executions").resolve(missing.id())), "no log folder for a program that never ran");
            assertFalse(Files.exists(state.resolve("executions").resolve(nowhere.id())));

            // the only place was not kept by them
            ExecutionSnapshot ran = end(fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Ends", "0")));
            assertEquals(ExecutionState.EXITED, ran.state());
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ x3

    @Test
    void aProgramStillRunningAtItsLimitIsTimedOutAndItAndWhatItStartedAreGone(@TempDir Path state, @TempDir Path work) throws Exception {
        program(work, "Sleeper", SLEEPER);
        program(work, "Tree", TREE);
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            ExecutionSnapshot done = end(fixture.runner.start(ExecutionIds.next(), java(work, Duration.ofSeconds(15), "Tree")));

            assertEquals(ExecutionState.TIMED_OUT, done.state());
            assertNull(done.exitCode());
            long parent = awaitPid(work.resolve("parent.pid"));
            long child = awaitPid(work.resolve("child.pid"));
            assertFalse(alive(parent), "the program is gone when the execution answers");
            assertFalse(alive(child), "so is the process it started");
        }
        ReleasedFolder.await(work);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ x4

    @Test
    void aCancelKillsTheRunningProgramAndWhatItStartedAndAProgramThatEndedIsNotMadeKilled(@TempDir Path state, @TempDir Path work) throws Exception {
        program(work, "Sleeper", SLEEPER);
        program(work, "Tree", TREE);
        program(work, "Ends", ENDS);
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            ExecutionHandle tree = fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Tree"));
            long parent = awaitPid(work.resolve("parent.pid"));
            long child = awaitPid(work.resolve("child.pid"));
            ExecutionSnapshot running = tree.snapshot();
            assertEquals(ExecutionState.RUNNING, running.state());
            assertNull(running.endedAt());
            assertEquals(parent, running.process().pid(), "the process of the execution is the program that was started");
            assertEquals(ProcessHandle.of(parent).orElseThrow().info().startInstant().map(instant -> instant.truncatedTo(ChronoUnit.MILLIS)),
                    Optional.of(running.process().startedAt().truncatedTo(ChronoUnit.MILLIS)), "and its start time is the system's");

            ExecutionSnapshot killed = tree.cancel();

            assertEquals(ExecutionState.KILLED, killed.state());
            assertNull(killed.exitCode());
            assertFalse(alive(parent));
            assertFalse(alive(child), "what the program started is killed with it");
            assertSame(killed, tree.ended().get());
            assertEquals(killed, tree.cancel(), "a cancel of a killed execution changes nothing");

            ExecutionHandle ended = fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Ends", "0"));
            ExecutionSnapshot exited = end(ended);
            assertEquals(exited, ended.cancel(), "a program that ended is left as it is: EXITED, not KILLED");
            assertEquals(ExecutionState.EXITED, ended.snapshot().state());
        }
        ReleasedFolder.await(work);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ x5

    @Test
    void aWaitThatIsOverBeforeTheEndAnswersRunningWithTheOutputSoFarAndTheExecutionIsFoundAndEndsLater(@TempDir Path state, @TempDir Path work) throws Exception {
        program(work, "Waiter", WAITER);
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            ExecutionHandle waiter = fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Waiter"));
            awaitFile(work.resolve("started.txt"));

            ExecutionSnapshot soon = waiter.await(Duration.ofMillis(100));
            assertEquals(ExecutionState.RUNNING, soon.state(), "the program waits for go.txt, so it has not ended");
            assertEquals(waiter.id(), soon.id());
            awaitCondition(() -> waiter.snapshot().stdout().head().contains("ready"), "the first line in the output of the running program");
            assertTrue(fixture.runner.find(waiter.id()).isPresent());
            assertEquals(ExecutionState.RUNNING, fixture.runner.find(waiter.id()).orElseThrow().snapshot().state());
            assertFalse(waiter.ended().isDone());
            assertEquals(ExecutionState.RUNNING, waiter.await(Duration.ofMillis(-5)).state(), "a wait that is not positive does not wait");

            Files.writeString(work.resolve("go.txt"), "go");
            ExecutionSnapshot done = waiter.await(LONG);

            assertEquals(ExecutionState.EXITED, done.state());
            assertEquals(0, done.exitCode());
            assertTrue(done.stdout().head().contains("ready") && done.stdout().head().contains("released"));
            assertEquals(done, fixture.runner.find(waiter.id()).orElseThrow().snapshot());
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ x6

    @Test
    void withOnePlaceASecondExecutionIsRefusedAtOnceAndWhenTheFirstEndedThePlaceIsUsedAgain(@TempDir Path state, @TempDir Path work) throws Exception {
        program(work, "Waiter", WAITER);
        program(work, "Ends", ENDS);
        try (RunnerFixture fixture = new RunnerFixture(state, 1, 32)) {
            ExecutionHandle first = fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Waiter"));
            awaitFile(work.resolve("started.txt"));

            Refused refused = assertThrows(Refused.class, () -> fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Ends", "0")));
            assertEquals(Refused.Reason.TOO_MANY_RUNS, refused.reason());
            assertEquals(1, refused.detail());

            Files.writeString(work.resolve("go.txt"), "go");
            assertEquals(ExecutionState.EXITED, end(first).state());
            assertEquals(ExecutionState.EXITED, end(fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Ends", "0"))).state(), "the place of the ended execution is free when its end is answered");
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ x7

    @Test
    void theStopOfTheServerKillsTheRunningProgramAndWhatItStartedAndRefusesLaterExecutions(@TempDir Path state, @TempDir Path work) throws Exception {
        program(work, "Sleeper", SLEEPER);
        program(work, "Tree", TREE);
        program(work, "Ends", ENDS);
        RunnerFixture fixture = new RunnerFixture(state);
        assertTrue(fixture.runner.isRunning());
        ExecutionHandle tree = fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Tree"));
        long parent = awaitPid(work.resolve("parent.pid"));
        long child = awaitPid(work.resolve("child.pid"));

        fixture.runner.stop();

        assertEquals(ExecutionState.KILLED, tree.snapshot().state());
        assertTrue(tree.ended().isDone(), "it has ended when stop returns");
        assertFalse(alive(parent));
        assertFalse(alive(child));
        assertFalse(fixture.runner.isRunning());
        Refused refused = assertThrows(Refused.class, () -> fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Ends", "0")));
        assertEquals(Refused.Reason.STOPPING, refused.reason());
        ReleasedFolder.await(work);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------- x8, x9, x10

    @Test
    void outputBelowTheMemoryIsAllInMemoryAndInTheLogFileAndNothingIsMarked(@TempDir Path state, @TempDir Path work) throws Exception {
        program(work, "Writer", WRITER);
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            long count = 100_000;
            ExecutionSnapshot done = end(fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Writer", "out", Long.toString(count))));

            assertEquals(ExecutionState.EXITED, done.state());
            StreamOutput out = done.stdout();
            assertEquals(count, out.totalBytes());
            assertEquals(0, out.omittedBytes());
            assertFalse(out.logTruncated());
            assertArrayEquals(expected(count), out.head().getBytes(StandardCharsets.UTF_8), "all of it, in the head");
            assertEquals("", out.tail());
            assertArrayEquals(expected(count), Files.readAllBytes(Path.of(done.logLocation()).resolve(ExecutionSnapshot.STDOUT_LOG)));
            assertEquals(StreamOutput.EMPTY, done.stderr());
        }
    }

    @Test
    void outputPastTheMemoryKeepsTheFirstAndTheLastBytesAndTheLogFileHasAllOfIt(@TempDir Path state, @TempDir Path work) throws Exception {
        program(work, "Writer", WRITER);
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            long count = 1_000_000;
            byte[] all = expected(count);
            ExecutionSnapshot done = end(fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Writer", "err", Long.toString(count))));

            assertEquals(ExecutionState.EXITED, done.state());
            assertEquals(0, done.exitCode(), "a program that writes more than the memory keeps is not stopped for it");
            StreamOutput err = done.stderr();
            assertEquals(count, err.totalBytes());
            assertEquals(count - StreamOutput.HEAD_BYTES - StreamOutput.TAIL_BYTES, err.omittedBytes());
            assertTrue(err.omitted());
            assertFalse(err.logTruncated());
            assertArrayEquals(Arrays.copyOfRange(all, 0, StreamOutput.HEAD_BYTES), err.head().getBytes(StandardCharsets.UTF_8), "the first bytes");
            assertArrayEquals(Arrays.copyOfRange(all, all.length - StreamOutput.TAIL_BYTES, all.length), err.tail().getBytes(StandardCharsets.UTF_8), "the last bytes");
            assertArrayEquals(all, Files.readAllBytes(Path.of(done.logLocation()).resolve(ExecutionSnapshot.STDERR_LOG)), "the file has every byte");
            assertEquals(StreamOutput.EMPTY, done.stdout());
            assertTrue(done.outputOmitted());
        }
    }

    @Test
    void aStreamThatPassesTheLimitOfItsLogFileDoesNotKillTheProgramTheFileStopsAtTheLimitAndIsMarkedAndTheEndIsStillInMemory(@TempDir Path state, @TempDir Path work) throws Exception {
        program(work, "Writer", WRITER);
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            long count = 5L * 1024 * 1024;
            ExecutionRequest request = new ExecutionRequest(java(work, LONG, "Writer", "out", Long.toString(count)).argv(), work, environment(), LONG, 1000);
            byte[] all = expected(count);

            ExecutionSnapshot done = end(fixture.runner.start(ExecutionIds.next(), request));

            assertEquals(ExecutionState.EXITED, done.state());
            assertEquals(0, done.exitCode(), "the program ended as it would have: it was not killed for what the file could not take");
            StreamOutput out = done.stdout();
            assertEquals(count, out.totalBytes(), "every byte was read, so the program was never blocked on a full pipe");
            assertTrue(out.logTruncated());
            assertTrue(done.logTruncated());
            assertArrayEquals(Arrays.copyOf(all, 1000), Files.readAllBytes(Path.of(done.logLocation()).resolve(ExecutionSnapshot.STDOUT_LOG)), "the file holds the first bytes up to its limit");
            assertArrayEquals(Arrays.copyOfRange(all, all.length - StreamOutput.TAIL_BYTES, all.length), out.tail().getBytes(StandardCharsets.UTF_8), "the end of the output is in memory");
            assertFalse(done.stderr().logTruncated(), "the other stream is not marked");
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ x11

    @Test
    void theEnvironmentIsOnlyWhatTheRequestNamesOfTheServersAndTheRequestsVariablesAreAddedAndWin(@TempDir Path state, @TempDir Path work) throws Exception {
        program(work, "Env", ENV);
        java.util.Set<String> listed = new TreeSet<>();
        SkillRunEnvironment.INHERITED_NAMES.forEach(name -> listed.add(name.toUpperCase(java.util.Locale.ROOT)));
        String name = System.getenv().entrySet().stream().filter(entry -> !listed.contains(entry.getKey().toUpperCase(java.util.Locale.ROOT)) && entry.getValue().matches("[ -~]+")
                && entry.getValue().equals(entry.getValue().strip())).map(Map.Entry::getKey).sorted().findFirst().orElseThrow(
                        () -> new AssertionError("the test JVM's own environment has a plain variable beyond the common ones, or this case proves nothing"));
        String value = System.getenv(name);
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            List<String> argv = List.of(RunnerFixture.JAVA, JAVA_HEAP, "-cp", ".", "Env", name, "SENNARD_RUNNER_TEST_ADDED");

            // the tests' environment names the variables a program of this system needs and not this one: the server's variable is not there, whatever the server's environment holds
            ExecutionSnapshot notNamed = end(fixture.runner.start(ExecutionIds.next(), request(argv, work, LONG)));
            assertEquals(List.of("<unset>", "<unset>"), notNamed.stdout().head().lines().toList(), "a variable of the server's that the request does not name does not reach the program");

            List<String> startingNamesAndThisOne = Stream.concat(SkillRunEnvironment.INHERITED_NAMES.stream(), Stream.of(name)).toList();
            ExecutionRequest naming = new ExecutionRequest(argv, work, new EnvironmentSpec(startingNamesAndThisOne, Map.of()), LONG, ExecutionRequest.MAX_LOG_BYTES);
            ExecutionSnapshot named = end(fixture.runner.start(ExecutionIds.next(), naming));
            assertEquals(List.of(value, "<unset>"), named.stdout().head().lines().toList(), "the one that is named is there, the one nobody set is not");

            ExecutionRequest overriding = new ExecutionRequest(argv, work, new EnvironmentSpec(SkillRunEnvironment.INHERITED_NAMES, Map.of(name, "overridden", "SENNARD_RUNNER_TEST_ADDED", "added")),
                    LONG, ExecutionRequest.MAX_LOG_BYTES);
            assertEquals(List.of("overridden", "added"), end(fixture.runner.start(ExecutionIds.next(), overriding)).stdout().head().lines().toList(), "added, and the same name replaced");

            // every variable of the server's but the one: the child still starts on any system, and what was left out is not there
            List<String> allButOne = System.getenv().keySet().stream().filter(each -> !each.equals(name)).toList();
            ExecutionRequest selecting = new ExecutionRequest(argv, work, new EnvironmentSpec(allButOne, Map.of("SENNARD_RUNNER_TEST_ADDED", "added")), LONG, ExecutionRequest.MAX_LOG_BYTES);
            ExecutionSnapshot selected = end(fixture.runner.start(ExecutionIds.next(), selecting));
            assertEquals(ExecutionState.EXITED, selected.state(), selected.stderr().head());
            assertEquals(List.of("<unset>", "added"), selected.stdout().head().lines().toList(), "of the server's only the selected ones, then the added: the one left out is not there");
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ x12

    @Test
    void theRunnerKeepsTheRunningAndTheLastFinishedExecutions(@TempDir Path state, @TempDir Path work) throws Exception {
        program(work, "Ends", ENDS);
        program(work, "Waiter", WAITER);
        try (RunnerFixture fixture = new RunnerFixture(state, 4, 2)) {
            ExecutionHandle waiting = fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Waiter"));
            awaitFile(work.resolve("started.txt"));
            ExecutionHandle one = fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Ends", "0"));
            end(one);
            ExecutionHandle two = fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Ends", "0"));
            end(two);
            ExecutionHandle three = fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Ends", "0"));
            end(three);

            assertEquals(Optional.empty(), fixture.runner.find(one.id()), "the oldest finished one is let go");
            assertTrue(fixture.runner.find(two.id()).isPresent());
            assertTrue(fixture.runner.find(three.id()).isPresent());
            assertTrue(fixture.runner.find(waiting.id()).isPresent(), "a running one is never let go");
            assertEquals(Optional.empty(), fixture.runner.find("no-such-id"));
            Files.writeString(work.resolve("go.txt"), "go");
            end(waiting);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ x13

    @Test
    void anArgumentOfABatchFileThatTheInterpreterWouldReadAsSyntaxIsRefusedOnEverySystemAndNothingIsMade(@TempDir Path state, @TempDir Path work) throws Exception {
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            Refused refused = assertThrows(Refused.class, () -> fixture.runner.start(ExecutionIds.next(), request(List.of("build.bat", "plain", "a&b"), work, LONG)));

            assertEquals(Refused.Reason.UNSAFE_ARGUMENT, refused.reason());
            assertEquals(2, refused.detail());
            assertFalse(Files.exists(state.resolve("executions")), "no folder was made for the refused execution");
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ x14

    /** Prints every argument it was given between brackets, one to a line. */
    private static final String ARGS = """
            public class Args {
                public static void main(String[] args) {
                    for (String argument : args) {
                        System.out.println("[" + argument + "]");
                    }
                }
            }
            """;

    @Test
    void theArgumentsArriveAsWrittenBecauseNoShellReadsThem(@TempDir Path state, @TempDir Path work) throws Exception {
        program(work, "Args", ARGS);
        // a variable of each system's shell, spaces, metacharacters of both shells, quotes of the other kind: nothing on any system reads these but the program
        List<String> given = List.of("$PATH", "%PATH%", "two  words", "a&b|c>d", "'quoted'", "x=y;z");
        // a double quote and a wildcard: what a system's own way of passing arguments does to them (Windows: the JDK does not escape a quote it was given, and the java launcher expands a
        // wildcard) is not the runner's; what is asked is that the runner adds nothing to it, so the program gets what the JDK's own ProcessBuilder gives it
        List<String> bySystem = List.of("say \"hi\"", "*");
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            List<String> plain = new java.util.ArrayList<>(List.of(RunnerFixture.JAVA, JAVA_HEAP, "-cp", ".", "Args"));
            plain.addAll(given);
            ExecutionSnapshot done = end(fixture.runner.start(ExecutionIds.next(), request(plain, work, LONG)));
            assertEquals(ExecutionState.EXITED, done.state(), done.stderr().head());
            assertEquals(given.stream().map(argument -> "[" + argument + "]").toList(), done.stdout().head().lines().toList());

            List<String> all = new java.util.ArrayList<>(plain);
            all.addAll(bySystem);
            Process direct = new ProcessBuilder(all).directory(work.toFile()).redirectErrorStream(true).start();
            direct.getOutputStream().close();
            String expected = new String(direct.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, direct.waitFor());
            ExecutionSnapshot viaRunner = end(fixture.runner.start(ExecutionIds.next(), request(all, work, LONG)));
            assertEquals(ExecutionState.EXITED, viaRunner.state(), viaRunner.stderr().head());
            assertEquals(expected.lines().toList(), viaRunner.stdout().head().lines().toList(), "the runner hands the arguments over exactly as ProcessBuilder does");
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ x15

    @Test
    void theStopOfTheServerAsksEveryRunningProgramToEndAndAnswersWhenAllHaveEnded(@TempDir Path state, @TempDir Path first, @TempDir Path second, @TempDir Path third) throws Exception {
        List<Path> folders = List.of(first, second, third);
        RunnerFixture fixture = new RunnerFixture(state);
        List<ExecutionHandle> trees = new java.util.ArrayList<>();
        long[] parents = new long[3];
        long[] children = new long[3];
        for (int each = 0; each < 3; each++) {
            program(folders.get(each), "Sleeper", SLEEPER);
            program(folders.get(each), "Tree", TREE);
            trees.add(fixture.runner.start(ExecutionIds.next(), java(folders.get(each), LONG, "Tree")));
            parents[each] = awaitPid(folders.get(each).resolve("parent.pid"));
            children[each] = awaitPid(folders.get(each).resolve("child.pid"));
        }

        fixture.runner.stop();

        for (int each = 0; each < 3; each++) {
            assertTrue(trees.get(each).ended().isDone(), "every execution has ended when stop returns");
            assertEquals(ExecutionState.KILLED, trees.get(each).snapshot().state());
            assertFalse(alive(parents[each]));
            assertFalse(alive(children[each]), "and what each started");
            ReleasedFolder.await(folders.get(each));
        }
    }

    @Test
    void theLogFolderOfEachExecutionIsItsOwnUnderTheStateFolder(@TempDir Path state, @TempDir Path work) throws Exception {
        program(work, "Ends", ENDS);
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            ExecutionSnapshot a = end(fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Ends", "0")));
            ExecutionSnapshot b = end(fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Ends", "0")));

            try (Stream<Path> folders = Files.list(state.resolve("executions"))) {
                assertEquals(new TreeSet<>(List.of(a.id(), b.id())), new TreeSet<>(folders.map(folder -> folder.getFileName().toString()).toList()));
            }
        }
    }

    @Test
    void theIdsOfTheRunningExecutionsAreTheOnesThatHaveNotEndedAndNoOtherAndAFinishedOneIsNotAmongThem(@TempDir Path state, @TempDir Path work) throws Exception {
        program(work, "Ends", ENDS);
        program(work, "Sleeper", SLEEPER);
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            assertEquals(java.util.Set.of(), fixture.runner.runningIds());
            ExecutionSnapshot finished = end(fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Ends", "0")));
            ExecutionHandle running = fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Sleeper", "p.pid"));
            awaitPid(work.resolve("p.pid"));

            assertEquals(java.util.Set.of(running.id()), fixture.runner.runningIds(), "the one that runs, and not the one that ended (which the runner still holds)");
            assertTrue(fixture.runner.find(finished.id()).isPresent());

            running.cancel();
            assertEquals(java.util.Set.of(), fixture.runner.runningIds());
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ x17

    @Test
    void theExecutionHasTheIdItWasGivenWhetherItRunsOrNotAndAnIdThatIsNotWrittenAsOneIsRejectedWithNothingMadeOrHeld(@TempDir Path state, @TempDir Path work) throws Exception {
        program(work, "Ends", ENDS);
        try (RunnerFixture fixture = new RunnerFixture(state, 1, 32)) {
            String given = ExecutionIds.next();
            ExecutionHandle ran = fixture.runner.start(given, java(work, LONG, "Ends", "0"));
            assertEquals(given, ran.id(), "the runner makes no id of its own");
            ExecutionSnapshot done = end(ran);
            assertEquals(given, done.id());
            assertEquals(state.resolve("executions").resolve(given).toString(), done.logLocation(), "the folder of the log files is named by the id that was given");
            String notStarted = ExecutionIds.next();
            ExecutionHandle none = fixture.runner.start(notStarted, request(List.of(work.resolve("no-such-program").toString()), work, LONG));
            assertEquals(notStarted, none.id());
            assertEquals(notStarted, none.snapshot().id());

            for (String bad : new String[] {"", "not-an-id", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", ExecutionIds.next() + "x", "../" + ExecutionIds.next(), null}) {
                assertThrows(IllegalArgumentException.class, () -> fixture.runner.start(bad, java(work, LONG, "Ends", "0")), String.valueOf(bad));
            }

            try (Stream<Path> folders = Files.list(state.resolve("executions"))) {
                assertEquals(new TreeSet<>(List.of(given)), new TreeSet<>(folders.map(folder -> folder.getFileName().toString()).toList()), "a rejected id made no folder");
            }
            assertEquals(java.util.Set.of(), fixture.runner.runningIds(), "and holds nothing");
            assertEquals(ExecutionState.EXITED, end(fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Ends", "0"))).state(), "the only place was not kept by a rejected id");
        }
    }

    @Test
    void anIdThatTheRunnerHoldsOrWhoseFolderIsThereAlreadyIsRejectedAndTheOtherIsNotTouchedAndNoPlaceIsKept(@TempDir Path state, @TempDir Path work) throws Exception {
        program(work, "Ends", ENDS);
        program(work, "Waiter", WAITER);
        program(work, "Sleeper", SLEEPER);
        try (RunnerFixture fixture = new RunnerFixture(state, 2, 1)) {
            String held = ExecutionIds.next();
            ExecutionHandle waiting = fixture.runner.start(held, java(work, LONG, "Waiter"));
            awaitFile(work.resolve("started.txt"));

            assertThrows(IllegalArgumentException.class, () -> fixture.runner.start(held, java(work, LONG, "Ends", "0")), "one that is running");
            assertEquals(ExecutionState.RUNNING, waiting.snapshot().state(), "and it goes on");
            assertEquals(java.util.Set.of(held), fixture.runner.runningIds());

            Files.writeString(work.resolve("go.txt"), "go");
            assertEquals(ExecutionState.EXITED, end(waiting).state());
            assertThrows(IllegalArgumentException.class, () -> fixture.runner.start(held, java(work, LONG, "Ends", "0")), "one that ended and that the runner still keeps");

            // an execution that was not started has no folder: only what the runner holds tells its id is in use
            String missing = ExecutionIds.next();
            assertEquals(ExecutionState.NOT_STARTED, fixture.runner.start(missing, request(List.of(work.resolve("no-such-program").toString()), work, LONG)).snapshot().state());
            assertThrows(IllegalArgumentException.class, () -> fixture.runner.start(missing, java(work, LONG, "Ends", "0")), "one that was not started and is still kept");

            String left = ExecutionIds.next();
            Path folder = Files.createDirectories(state.resolve("executions").resolve(left));
            Path file = Files.writeString(folder.resolve("stdout.log"), "somebody else's output");
            assertThrows(IllegalArgumentException.class, () -> fixture.runner.start(left, java(work, LONG, "Ends", "0")), "one whose folder is there, though the runner does not hold it");
            assertEquals("somebody else's output", Files.readString(file), "the folder that was there is not taken, and not removed with the start that failed");
            assertEquals(java.util.Set.of(), fixture.runner.runningIds());

            // three rejected starts and the two places are both free
            ExecutionHandle first = fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Sleeper", "one.pid"));
            ExecutionHandle second = fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Sleeper", "two.pid"));
            try {
                Refused refused = assertThrows(Refused.class, () -> fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Ends", "0")));
                assertEquals(Refused.Reason.TOO_MANY_RUNS, refused.reason(), "two ran: no place was lost to a rejected id");
            } finally {
                first.cancel();
                second.cancel();
            }
        }
        ReleasedFolder.await(work);
    }

    @Test
    void aLogFolderThatCannotBeHadBecauseAFileIsInItsPlaceIsNotStartedAndNotAnIdThatIsInUseAndKeepsNoPlace(@TempDir Path state, @TempDir Path work) throws Exception {
        program(work, "Ends", ENDS);
        Path logFolder = Files.writeString(state.resolve("executions"), "a file where the log folder should be");
        try (RunnerFixture fixture = new RunnerFixture(state, 1, 32)) {
            String first = ExecutionIds.next();
            ExecutionSnapshot refused = fixture.runner.start(first, java(work, LONG, "Ends", "0")).snapshot();
            assertEquals(ExecutionState.NOT_STARTED, refused.state(), "the same answer as for any log files that cannot be made, and no IllegalArgumentException for an id that is not in use");
            assertEquals(java.util.Set.of(), fixture.runner.runningIds());

            // the only place was given back: another start gets its answer, not Refused TOO_MANY_RUNS
            assertEquals(ExecutionState.NOT_STARTED, fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Ends", "0")).snapshot().state());
            assertEquals("a file where the log folder should be", Files.readString(logFolder), "and the file is left as it is");

            Files.delete(logFolder);
            assertEquals(ExecutionState.EXITED, end(fixture.runner.start(ExecutionIds.next(), java(work, LONG, "Ends", "0"))).state(), "once the place is free the start is taken");
        }
    }
}
