// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import static io.github.jaeseongs95.sennard.personal.RunnerFixture.JAVA;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.JAVA_HEAP;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.alive;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.awaitPid;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.program;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.server.execution.OrphanProcesses.Outcome;
import io.github.jaeseongs95.sennard.server.execution.ProcessIdentity;

/**
 * What a restarted server does about the process of a record that was still running: the same process (its id and its start time) is found and killed with what it started; a
 * process that is gone, or whose id belongs to another process now, is left alone and reported as gone; one that cannot be told from another is not touched. The processes are real
 * JVMs started here (what the server before this one would have started), and the identity is read from the system the way the runner reads it.
 */
class LocalOrphanProcessesTest {
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

    private final LocalOrphanProcesses orphans = new LocalOrphanProcesses();

    private static Process start(Path folder, String program, String... args) throws IOException {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(JAVA, JAVA_HEAP, "-cp", ".", program));
        command.addAll(java.util.List.of(args));
        return new ProcessBuilder(command).directory(folder.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static ProcessIdentity identityOf(Process process) {
        return new ProcessIdentity(process.pid(), process.toHandle().info().startInstant().orElseThrow());
    }

    @Test
    void theSameProcessAndWhatItStartedAreKilled(@TempDir Path work) throws Exception {
        program(work, "Sleeper", SLEEPER);
        program(work, "Tree", TREE);
        Process tree = start(work, "Tree");
        try {
            long child = awaitPid(work.resolve("child.pid"));
            ProcessIdentity identity = identityOf(tree);

            assertEquals(Outcome.KILLED, orphans.terminate(identity));

            assertFalse(alive(tree.pid()));
            assertFalse(alive(child), "what the process started is killed with it");
        } finally {
            tree.destroyForcibly();
            tree.waitFor(30, TimeUnit.SECONDS);
        }
        ReleasedFolder.await(work);
    }

    @Test
    void aProcessThatIsGoneIsReportedGone(@TempDir Path work) throws Exception {
        program(work, "Sleeper", SLEEPER);
        Process sleeper = start(work, "Sleeper", "self.pid");
        awaitPid(work.resolve("self.pid"));
        ProcessIdentity identity = identityOf(sleeper);
        sleeper.destroyForcibly();
        sleeper.waitFor(30, TimeUnit.SECONDS);

        assertEquals(Outcome.GONE, orphans.terminate(identity));
        ReleasedFolder.await(work);
    }

    @Test
    void aProcessWithTheSameIdButAnotherStartTimeIsAnotherProcessAndIsNotTouched(@TempDir Path work) throws Exception {
        program(work, "Sleeper", SLEEPER);
        Process sleeper = start(work, "Sleeper", "self.pid");
        try {
            awaitPid(work.resolve("self.pid"));
            ProcessIdentity real = identityOf(sleeper);

            assertEquals(Outcome.GONE, orphans.terminate(new ProcessIdentity(real.pid(), real.startedAt().minusSeconds(3600))), "the id was another process an hour ago");
            assertTrue(sleeper.isAlive(), "the process that has the id now was not touched");
        } finally {
            sleeper.destroyForcibly();
            sleeper.waitFor(30, TimeUnit.SECONDS);
        }
        ReleasedFolder.await(work);
    }

    @Test
    void aLiveProcessWhoseRecordHasNoStartTimeCannotBeToldFromAnotherAndIsNotTouched(@TempDir Path work) throws Exception {
        program(work, "Sleeper", SLEEPER);
        Process sleeper = start(work, "Sleeper", "self.pid");
        try {
            awaitPid(work.resolve("self.pid"));

            assertEquals(Outcome.UNVERIFIABLE, orphans.terminate(new ProcessIdentity(sleeper.pid(), null)));
            assertTrue(sleeper.isAlive());
        } finally {
            sleeper.destroyForcibly();
            sleeper.waitFor(30, TimeUnit.SECONDS);
        }
        ReleasedFolder.await(work);
    }
}
