// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.github.jaeseongs95.sennard.personal.LocalProcessRunner;
import io.github.jaeseongs95.sennard.personal.ReleasedFolder;
import io.github.jaeseongs95.sennard.personal.RunnerFixture;
import io.github.jaeseongs95.sennard.server.execution.ExecutionIds;
import io.github.jaeseongs95.sennard.server.execution.ExecutionSnapshot;
import io.github.jaeseongs95.sennard.server.execution.ExecutionState;

/**
 * What the real runner leaves behind when a process of the command lost its parent before the runner's first listing saw it and keeps the pipes of the output open without
 * writing anything: the shell below starts the holder in a subshell that ends at once, so the holder has no parent of the command's by the time it is listed, and on Linux it is
 * not found through the command (it is not killed; see the runner's description). Before the run answers, the runner must have let go of everything it took for the output: the
 * pipes it opened and the JDK's process reaper thread (which waits for the monitor of a stream that is being read). The order is fixed by files the programs write and wait for,
 * and no time is asserted (a wait has a deadline only so that a failure ends the test).
 *
 * <p>Linux only: the shell, the subshell's parent handling and {@code /proc} are Linux's. The shell is an external program because the case is about what a command's shell
 * leaves behind; the holder is a JVM started with the test heap ({@code sennard.test.childHeap}).
 */
@EnabledOnOs(OS.LINUX)
class RunnerDetachedHolderTest {
    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();
    /** The heap of every JVM the tests start (the property is set on the Test tasks in java/build.gradle.kts). */
    private static final String JAVA_HEAP = "-Xmx" + System.getProperty("sennard.test.childHeap");
    /** Starts the program of the arguments after its own name in a subshell that ends at once (the program has no parent of the shell's), and ends when that program says it holds the pipes. */
    private static final String SHELL = "( \"$@\" & ); while [ ! -e holder.ready ]; do sleep 0.1; done";
    /**
     * Holds the pipes of the output it inherited without writing, says so in a file, and waits for the file go.txt, which the test makes after the run has answered. Then it writes
     * one byte to stdout and says in a file whether that write failed: a pipe the runner closed gives a broken pipe on the first write.
     */
    private static final String HOLDER = """
            public class Holder {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("holder.pid"), Long.toString(ProcessHandle.current().pid()));
                    java.nio.file.Files.writeString(java.nio.file.Path.of("holder.ready"), "ready");
                    while (!java.nio.file.Files.exists(java.nio.file.Path.of("go.txt"))) {
                        Thread.sleep(20);
                    }
                    try {
                        new java.io.FileOutputStream(java.io.FileDescriptor.out).write('x');
                        java.nio.file.Files.writeString(java.nio.file.Path.of("unbroken.txt"), "unbroken");
                    } catch (java.io.IOException brokenPipe) {
                        java.nio.file.Files.writeString(java.nio.file.Path.of("broken.txt"), "broken");
                    }
                }
            }
            """;

    /** The pipes this JVM has open, by the kernel's name for them ({@code pipe:[inode]}). */
    private static Set<String> openPipes() throws IOException {
        try (Stream<Path> descriptors = Files.list(Path.of("/proc/self/fd"))) {
            return descriptors.map(RunnerDetachedHolderTest::target).flatMap(Optional::stream).filter(name -> name.startsWith("pipe:")).collect(Collectors.toSet());
        }
    }

    /** What a descriptor is open on; empty for a descriptor that was closed between the listing and this call (the listing's own directory handle, for one). */
    private static Optional<String> target(Path descriptor) {
        try {
            return Optional.of(Files.readSymbolicLink(descriptor).toString());
        } catch (NoSuchFileException closedMeanwhile) {
            return Optional.empty();
        } catch (IOException unreadable) {
            throw new AssertionError("A descriptor of this JVM could not be read: " + descriptor, unreadable);
        }
    }

    /** The first of the files that exists, waiting for one of them (deadline only so that a failure ends the test). */
    private static String awaitOneOf(Path folder, String... names) throws InterruptedException {
        Instant end = Instant.now().plusSeconds(90);
        while (Instant.now().isBefore(end)) {
            for (String name : names) {
                if (Files.exists(folder.resolve(name))) {
                    return name;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("none of " + List.of(names) + " was written in time");
    }

    @Test
    void aSilentHolderThatLostItsParentLeavesNoPipeNoBlockedReaperAndGetsABrokenPipe(@TempDir Path state, @TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Holder.java"), HOLDER);
        long holder = -1;
        try (AnnotationConfigApplicationContext context = SkillCheckContext.open(state)) {
            LocalProcessRunner runner = context.getBean(LocalProcessRunner.class);
            Set<String> pipesBefore = openPipes();

            ExecutionSnapshot completed = runner.start(ExecutionIds.next(), RunnerFixture.request(List.of("/bin/sh", "-c", SHELL, "sh", JAVA, JAVA_HEAP, "Holder.java"), root, Duration.ofSeconds(120)))
                    .await(Duration.ofSeconds(180));
            holder = Long.parseLong(Files.readString(root.resolve("holder.pid")));

            assertEquals(ExecutionState.EXITED, completed.state(), completed::toString);
            assertEquals(0, completed.exitCode(), "the shell ended by itself once the holder held the pipes");
            assertTrue(ProcessHandle.of(holder).map(ProcessHandle::isAlive).orElse(false),
                    "the holder lost its parent before a listing saw it, so the runner did not kill it (if it did, a listing caught the subshell and this case proves nothing)");

            Set<String> leftOpen = openPipes();
            leftOpen.removeAll(pipesBefore);
            assertEquals(Set.of(), leftOpen, "the pipes the run opened for the output are closed when it answers: no reader is left parked on them");

            List<String> blocked = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.getName().startsWith("process reaper") && thread.getState() == Thread.State.BLOCKED).map(Thread::getName).toList();
            assertEquals(List.of(), blocked, "the JDK's reaper thread is not waiting for the monitor of a stream that a reader still holds");

            Files.writeString(root.resolve("go.txt"), "go");
            assertEquals("broken.txt", awaitOneOf(root, "broken.txt", "unbroken.txt"), "the holder's first write after the run got a broken pipe, so nothing it writes is read or kept");
        } finally {
            if (holder > 0) {
                ProcessHandle.of(holder).ifPresent(ProcessHandle::destroyForcibly);
                ProcessHandle.of(holder).ifPresent(handle -> handle.onExit().join());
            }
        }
        ReleasedFolder.await(root);
    }
}
