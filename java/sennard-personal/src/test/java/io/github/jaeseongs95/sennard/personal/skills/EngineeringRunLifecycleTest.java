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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.github.jaeseongs95.sennard.personal.LocalProcessRunner;
import io.github.jaeseongs95.sennard.personal.ReleasedFolder;
import io.github.jaeseongs95.sennard.server.skills.SkillCheckService;
import io.github.jaeseongs95.sennard.server.skills.SkillCheckService.Outcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the runner of {@code run} does about the life of the commands it starts, each case with a context of its own (the cases end or stop the runner):
 *
 * <pre>
 * q  the concurrency limit: with {@code sennard.run.max-concurrent} 2, two commands running and a third asked for is refused at once and starts nothing; when one has ended a new one runs
 * r  the server's stop kills the running command and the process it started, a run asked for after the stop is refused
 * s  the runner is started with the context and a run writes the output of its command to log files of a folder of its own under the state directory
 * </pre>
 */
class EngineeringRunLifecycleTest {
    private static final String INVALID = "INVALID_INPUT";
    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();
    /** The heap of every JVM the tests start (the property is set on the Test tasks in java/build.gradle.kts). */
    private static final String JAVA_HEAP = "-Xmx" + System.getProperty("sennard.test.childHeap");
    /** Writes a file to say it runs, then sleeps ten minutes. */
    private static final String SLEEP = """
            public class Sleep {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("started.txt"), "started");
                    Thread.sleep(600_000);
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
    /** Writes its own process id, starts another JVM of itself that sleeps, writes that one's process id, and sleeps too. */
    private static final String TREE = """
            public class Tree {
                public static void main(String[] args) throws Exception {
                    if (args.length > 0) {
                        Thread.sleep(600_000);
                        return;
                    }
                    java.nio.file.Files.writeString(java.nio.file.Path.of("parent.pid"), Long.toString(ProcessHandle.current().pid()));
                    String launcher = ProcessHandle.current().info().command().orElseThrow();
                    Process child = new ProcessBuilder(launcher, "@HEAP@", "Tree.java", "child").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    java.nio.file.Files.writeString(java.nio.file.Path.of("child.pid"), Long.toString(child.pid()));
                    Thread.sleep(600_000);
                }
            }
            """.replace("@HEAP@", JAVA_HEAP);

    private static Outcome run(SkillCheckService service, Path root, JsonNode snapshot, long timeoutMs, String program) {
        return service.run("test-engineering", "run", TestJson.object("root", root.toString(), "snapshot", snapshot, "timeout-ms", timeoutMs, "argv", TestJson.array(List.of(JAVA, JAVA_HEAP, program))));
    }

    private static ObjectNode prepare(SkillCheckService service, Path root, String file, String source) throws IOException {
        Files.writeString(root.resolve(file), source);
        Outcome outcome = service.run("test-engineering", "snapshot", TestJson.object("root", root.toString(), "files", TestJson.array(List.of(file))));
        assertNotNull(outcome.body().get("value"), () -> outcome.body().toString());
        return (ObjectNode) outcome.body().get("value").deepCopy();
    }

    private static void refused(Outcome outcome, String key) {
        assertEquals(2, outcome.exitCode(), () -> outcome.body().toString());
        assertEquals(INVALID, outcome.body().get("code").stringValue());
        assertEquals(key, outcome.body().get("messageKey").stringValue());
        assertNotEquals(key, outcome.body().get("message").stringValue(), () -> "the catalog has no sentence for " + key);
    }

    private static void awaitFile(Path file) throws InterruptedException {
        Instant end = Instant.now().plusSeconds(60);
        while (!Files.exists(file) && Instant.now().isBefore(end)) {
            Thread.sleep(50);
        }
        assertTrue(Files.exists(file), "the command did not start in time: " + file);
    }

    private static boolean alive(Path pidFile) throws IOException {
        return ProcessHandle.of(Long.parseLong(Files.readString(pidFile))).map(ProcessHandle::isAlive).orElse(false);
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------- q

    @Test
    void aRunAskedForBeyondTheLimitIsRefusedAtOnceStartsNothingAndAFreedPlaceIsUsedAgain(@TempDir Path state, @TempDir Path first, @TempDir Path second, @TempDir Path third) throws Exception {
        try (AnnotationConfigApplicationContext context = SkillCheckContext.open(state, Map.of("sennard.run.max-concurrent", "2")); ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            SkillCheckService service = context.getBean(SkillCheckService.class);
            ObjectNode sleepingOne = prepare(service, first, "Sleep.java", SLEEP);
            ObjectNode sleepingTwo = prepare(service, second, "Sleep.java", SLEEP);
            ObjectNode marker = prepare(service, third, "Marker.java", MARKER);

            Future<Outcome> one = pool.submit(() -> run(service, first, sleepingOne, 12000, "Sleep.java"));
            Future<Outcome> two = pool.submit(() -> run(service, second, sleepingTwo, 12000, "Sleep.java"));
            awaitFile(first.resolve("started.txt"));
            awaitFile(second.resolve("started.txt"));

            // Refused, not queued: a queued run would have waited for a place and then run, so it would have left ran.txt.
            refused(run(service, third, marker, 30000, "Marker.java"), "practices.runner.tooManyRuns");
            assertFalse(Files.exists(third.resolve("ran.txt")), "a refused run starts nothing");
            assertFalse(Files.exists(state.resolve("runs")), "the output of the two running commands is read from their pipes, not written under the state directory");

            assertEquals("TIMED_OUT", one.get(90, TimeUnit.SECONDS).body().get("value").get("status").stringValue());
            assertEquals("TIMED_OUT", two.get(90, TimeUnit.SECONDS).body().get("value").get("status").stringValue());
            Outcome again = run(service, third, marker, 60000, "Marker.java");
            assertEquals("PASS", again.body().get("value").get("status").stringValue(), () -> again.body().toString());
            assertTrue(Files.exists(third.resolve("ran.txt")), "the place the ended commands freed is used again");
            ReleasedFolder.await(first);
            ReleasedFolder.await(second);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------- r

    @Test
    void theStopOfTheServerKillsTheRunningCommandAndWhatItStartedAndRefusesLaterRuns(@TempDir Path state, @TempDir Path root, @TempDir Path later) throws Exception {
        long parent = -1;
        long child = -1;
        try (AnnotationConfigApplicationContext context = SkillCheckContext.open(state); ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            SkillCheckService service = context.getBean(SkillCheckService.class);
            ObjectNode tree = prepare(service, root, "Tree.java", TREE);
            ObjectNode marker = prepare(service, later, "Marker.java", MARKER);

            Future<Outcome> running = pool.submit(() -> run(service, root, tree, 200000, "Tree.java"));
            awaitFile(root.resolve("child.pid"));
            parent = Long.parseLong(Files.readString(root.resolve("parent.pid")));
            child = Long.parseLong(Files.readString(root.resolve("child.pid")));
            assertTrue(alive(root.resolve("parent.pid")) && alive(root.resolve("child.pid")), "both processes run before the stop");

            context.getBean(LocalProcessRunner.class).stop();

            Outcome outcome = running.get(60, TimeUnit.SECONDS);
            assertEquals("SPAWN_ERROR", outcome.body().get("value").get("status").stringValue(), () -> "the run did not end as a completed one: " + outcome.body());
            assertFalse(alive(root.resolve("parent.pid")), "the command is gone when stop returns");
            assertFalse(alive(root.resolve("child.pid")), "so is the process it started");
            assertFalse(context.getBean(LocalProcessRunner.class).isRunning());
            refused(run(service, later, marker, 30000, "Marker.java"), "practices.runner.serverIsStopping");
            assertFalse(Files.exists(later.resolve("ran.txt")));
            ReleasedFolder.await(root);
        } catch (ExecutionException | TimeoutException failed) {
            throw new AssertionError("the run did not answer after the stop", failed);
        } finally {
            if (parent > 0) {
                ProcessHandle.of(parent).ifPresent(ProcessHandle::destroyForcibly);
            }
            if (child > 0) {
                ProcessHandle.of(child).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------- s

    @Test
    void theRunnerIsStartedWithTheContextAndARunLogsItsOutputInAFolderOfItsOwnUnderTheStateDirectory(@TempDir Path state, @TempDir Path root) throws Exception {
        try (AnnotationConfigApplicationContext context = SkillCheckContext.open(state)) {
            assertTrue(context.getBean(LocalProcessRunner.class).isRunning(), "the runner was started with the context");
            SkillCheckService service = context.getBean(SkillCheckService.class);
            ObjectNode marker = prepare(service, root, "Marker.java", MARKER);

            Outcome outcome = run(service, root, marker, 60000, "Marker.java");

            assertEquals("PASS", outcome.body().get("value").get("status").stringValue(), () -> outcome.body().toString());
            assertTrue(Files.exists(root.resolve("ran.txt")));
            try (java.util.stream.Stream<java.nio.file.Path> folders = Files.list(state.resolve("executions"))) {
                java.util.List<java.nio.file.Path> made = folders.toList();
                assertEquals(1, made.size(), "one folder was made for the one run: " + made);
                assertTrue(Files.isRegularFile(made.get(0).resolve("stdout.log")) && Files.isRegularFile(made.get(0).resolve("stderr.log")), "a log file for each stream");
            }
        }
    }
}
