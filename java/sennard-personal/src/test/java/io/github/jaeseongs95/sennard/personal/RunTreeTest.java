// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the runner knows about the processes beneath the command it runs, with real processes and the order of events fixed by the test (no time is asserted):
 * the command ends, and a process it left running starts another one afterwards. On Linux and macOS a process whose parent has ended is not found through that parent any
 * more, so the new one is found only when the listing also starts at the processes found before (on Windows the parent link stays and both ways find it).
 */
class RunTreeTest {
    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();
    /** The heap of every JVM the tests start (the property is set on the Test tasks in java/build.gradle.kts). */
    private static final String JAVA_HEAP = "-Xmx" + System.getProperty("sennard.test.childHeap");
    /** The command: starts the middle process, writes its id, and ends when the test says so (the file root.end). */
    private static final String ROOT = """
            public class Root {
                public static void main(String[] args) throws Exception {
                    String launcher = ProcessHandle.current().info().command().orElseThrow();
                    Process middle = new ProcessBuilder(launcher, "@HEAP@", "Middle.java").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    java.nio.file.Files.writeString(java.nio.file.Path.of("middle.pid"), Long.toString(middle.pid()));
                    while (!java.nio.file.Files.exists(java.nio.file.Path.of("root.end"))) {
                        Thread.sleep(20);
                    }
                }
            }
            """.replace("@HEAP@", JAVA_HEAP);
    /** The process the command leaves running: starts the last process when the test says so (the file spawn), writes its id, and sleeps. */
    private static final String MIDDLE = """
            public class Middle {
                public static void main(String[] args) throws Exception {
                    while (!java.nio.file.Files.exists(java.nio.file.Path.of("spawn"))) {
                        Thread.sleep(20);
                    }
                    String launcher = ProcessHandle.current().info().command().orElseThrow();
                    Process last = new ProcessBuilder(launcher, "@HEAP@", "Sleeper.java").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    java.nio.file.Files.writeString(java.nio.file.Path.of("last.pid"), Long.toString(last.pid()));
                    Thread.sleep(600_000);
                }
            }
            """.replace("@HEAP@", JAVA_HEAP);
    private static final String SLEEPER = """
            public class Sleeper {
                public static void main(String[] args) throws Exception {
                    Thread.sleep(600_000);
                }
            }
            """;

    /** The process id a file will hold: waits until the file has a whole number in it (a file can be seen before its text is written). */
    private static long awaitPid(Path file) throws InterruptedException, IOException {
        Instant end = Instant.now().plusSeconds(90);
        while (Instant.now().isBefore(end)) {
            if (Files.exists(file)) {
                String text = Files.readString(file);
                if (text.matches("\\d+")) {
                    return Long.parseLong(text);
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the process did not write its id in time: " + file);
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    @Test
    void aProcessStartedByAProcessThatLostItsParentIsFoundAndKilledWithIt(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("Root.java"), ROOT);
        Files.writeString(folder.resolve("Middle.java"), MIDDLE);
        Files.writeString(folder.resolve("Sleeper.java"), SLEEPER);
        Process root = new ProcessBuilder(JAVA, JAVA_HEAP, "Root.java").directory(folder.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        long middle = -1;
        long last = -1;
        try {
            LocalProcessRunner.Child child = new LocalProcessRunner.Child(root);
            middle = awaitPid(folder.resolve("middle.pid"));
            child.sample();
            assertTrue(child.found(middle), "the process the command started is found while the command runs");

            Files.writeString(folder.resolve("root.end"), "end");
            root.onExit().get(90, TimeUnit.SECONDS);
            Files.writeString(folder.resolve("spawn"), "now");
            last = awaitPid(folder.resolve("last.pid"));
            assertTrue(alive(middle) && alive(last), "the command has ended and both processes it left run");

            child.sample();
            assertTrue(child.found(last), "what a left-running process started after the command ended is found through that process");

            child.killLeftovers();
            assertFalse(alive(middle), "the process the command left running is gone");
            assertFalse(alive(last), "so is the one it started after the command ended");
            ReleasedFolder.await(folder);
        } finally {
            root.destroyForcibly();
            for (long pid : new long[] {middle, last}) {
                if (pid > 0) {
                    ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                }
            }
        }
    }
}
