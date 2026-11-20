// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

/**
 * {@link ToolProcess} with real child JVMs ({@link ToolProcessProbe}); nothing here needs a shell or an OS tool.
 *
 * <pre>
 * Case                         Input                                   Expected
 * ends normally                say 0                                   Finished(0, "out-text" + stderr per mode)
 * ends with a code             say 7                                   Finished(7, ...)
 * deadline                     sleep 120s, deadline 5s                 TimedOut within the wait, child dead
 * deadline, grandchild         tree (child starts a sleeping child)    TimedOut, child and grandchild dead
 * large output                 flood 8 MiB                             Finished, 8 MiB exactly, no stall
 * output limit                 flood-forever, limit 1 MiB              Overflowed, child dead
 * output over limit at end     flood 2 MiB, limit 1 MiB                Overflowed
 * output at limit              flood 1 MiB, limit 1 MiB                Finished
 * environment                  env NAME with value / null              the value / removed
 * input bytes                  echo-input with 0..255 bytes            the same bytes back
 * directory                    cwd in a temp directory                 that directory
 * cannot start                 a program that is not there             IOException
 * stderr MERGE / DISCARD / INHERIT  say 0                              both / stdout only / stdout only
 * </pre>
 */
class ToolProcessTest {
    private static final Duration LONG = Duration.ofMinutes(2);

    @TempDir
    Path temporary;

    private static List<String> probe(String... arguments) {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx" + System.getProperty("sennard.test.childHeap"), "-Dsennard.test.childHeap=" + System.getProperty("sennard.test.childHeap"), "-cp",
                System.getProperty("sennard.test.classpath"), ToolProcessProbe.class.getName()));
        command.addAll(List.of(arguments));
        return command;
    }

    private static ToolProcess.Request request(ToolProcess.Stderr stderr, Duration timeout, String... arguments) {
        return ToolProcess.Request.of(probe(arguments), stderr, timeout);
    }

    private static ToolProcess.Finished finished(ToolProcess.Outcome outcome) {
        return switch (outcome) {
            case ToolProcess.Finished done -> done;
            case ToolProcess.TimedOut late -> throw new AssertionFailedError("the tool did not finish within its deadline");
            case ToolProcess.Overflowed big -> throw new AssertionFailedError("the tool wrote more than its limit");
        };
    }

    private static String text(ToolProcess.Finished done) {
        return new String(done.output(), StandardCharsets.UTF_8);
    }

    private static long pid(Path file) throws IOException {
        assertTrue(Files.exists(file), "the child had not started when it was killed: " + file);
        return Long.parseLong(Files.readString(file));
    }

    private static void assertDead(long pid) throws InterruptedException, ExecutionException, TimeoutException {
        var handle = ProcessHandle.of(pid);
        if (handle.isPresent()) {
            handle.get().onExit().get(10, TimeUnit.SECONDS);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "process " + pid + " is still alive");
    }

    @Test
    void aToolThatEndsReturnsItsExitCodeAndOutput() throws Exception {
        ToolProcess.Finished done = finished(ToolProcess.run(request(ToolProcess.Stderr.DISCARD, LONG, "say", "0")));
        assertEquals(0, done.exitCode());
        assertEquals("out-text", text(done));
    }

    @Test
    void aNonZeroExitCodeIsAResultNotAnError() throws Exception {
        ToolProcess.Finished done = finished(ToolProcess.run(request(ToolProcess.Stderr.DISCARD, LONG, "say", "7")));
        assertEquals(7, done.exitCode());
        assertEquals("out-text", text(done));
    }

    @Test
    void aToolPastItsDeadlineIsTimedOutAndKilled() throws Exception {
        Path pidFile = temporary.resolve("sleeper.pid");
        ToolProcess.Outcome outcome = ToolProcess.run(request(ToolProcess.Stderr.DISCARD, Duration.ofSeconds(5), "sleep", pidFile.toString()));
        // The child sleeps for two minutes: that the answer is TimedOut and not the child's own end is the proof that the deadline held, and the child is gone.
        assertEquals(new ToolProcess.TimedOut(), outcome);
        assertDead(pid(pidFile));
    }

    @Test
    void aTimedOutToolTakesWhatItStartedWithIt() throws Exception {
        Path childPid = temporary.resolve("child.pid");
        Path grandchildPid = temporary.resolve("grandchild.pid");
        ToolProcess.Outcome outcome = ToolProcess.run(request(ToolProcess.Stderr.DISCARD, Duration.ofSeconds(8), "tree", childPid.toString(),
                grandchildPid.toString()));
        assertEquals(new ToolProcess.TimedOut(), outcome);
        assertDead(pid(childPid));
        assertDead(pid(grandchildPid));
    }

    @Test
    void aLargeOutputDoesNotStopTheToolAndArrivesWhole() throws Exception {
        long size = 8L * 1024 * 1024;
        ToolProcess.Finished done = finished(ToolProcess.run(request(ToolProcess.Stderr.DISCARD, LONG, "flood", Long.toString(size))));
        assertEquals(0, done.exitCode());
        assertEquals(size, done.output().length);
    }

    @Test
    void aToolThatKeepsWritingPastTheLimitIsOverflowedAndKilled() throws Exception {
        Path pidFile = temporary.resolve("flooder.pid");
        ToolProcess.Outcome outcome = ToolProcess.run(request(ToolProcess.Stderr.DISCARD, LONG, "flood-forever", pidFile.toString()).limitedTo(1024 * 1024));
        assertEquals(new ToolProcess.Overflowed(), outcome);
        assertDead(pid(pidFile));
    }

    @Test
    void anOutputOverTheLimitWhenTheToolEndsIsOverflowedAndOneAtTheLimitIsNot() throws Exception {
        long limit = 1024 * 1024;
        assertEquals(new ToolProcess.Overflowed(), ToolProcess.run(request(ToolProcess.Stderr.DISCARD, LONG, "flood", Long.toString(2 * limit)).limitedTo(limit)));
        assertEquals(limit, finished(ToolProcess.run(request(ToolProcess.Stderr.DISCARD, LONG, "flood", Long.toString(limit)).limitedTo(limit))).output().length);
    }

    @Test
    void theEnvironmentIsOverriddenAndANullValueRemovesTheVariable() throws Exception {
        String name = "SENNARD_TOOL_PROCESS_TEST";
        Map<String, String> set = new HashMap<>();
        set.put(name, "given");
        assertEquals("given", text(finished(ToolProcess.run(request(ToolProcess.Stderr.DISCARD, LONG, "env", name).withEnvironment(set)))));
        assertEquals("<unset>", text(finished(ToolProcess.run(request(ToolProcess.Stderr.DISCARD, LONG, "env", name)))));
        // A variable this process has and the child inherits, that the JVM and the OS loader do not need: it is there, then removed by a null.
        Set<String> needed = Set.of("systemroot", "systemdrive", "windir", "path", "temp", "tmp", "comspec");
        String inherited = System.getenv().keySet().stream().filter(key -> key.matches("[A-Za-z_][A-Za-z0-9_]*") && !needed.contains(key.toLowerCase(Locale.ROOT)))
                .findFirst().orElseThrow(() -> new AssertionFailedError("this process has no variable to remove"));
        assertTrue(!text(finished(ToolProcess.run(request(ToolProcess.Stderr.DISCARD, LONG, "env", inherited)))).equals("<unset>"), inherited + " is inherited");
        Map<String, String> removed = new HashMap<>();
        removed.put(inherited, null);
        assertEquals("<unset>", text(finished(ToolProcess.run(request(ToolProcess.Stderr.DISCARD, LONG, "env", inherited).withEnvironment(removed)))));
    }

    @Test
    void inputBytesReachTheToolUnchanged() throws Exception {
        byte[] every = new byte[256];
        for (int i = 0; i < every.length; i++) {
            every[i] = (byte) i;
        }
        ToolProcess.Finished done = finished(ToolProcess.run(request(ToolProcess.Stderr.DISCARD, LONG, "echo-input").withInput(every)));
        assertEquals(0, done.exitCode());
        assertTrue(java.util.Arrays.equals(every, done.output()), "the bytes came back changed");
    }

    @Test
    void aToolWithoutInputSeesTheEndOfItAtOnce() throws Exception {
        ToolProcess.Finished done = finished(ToolProcess.run(request(ToolProcess.Stderr.DISCARD, LONG, "echo-input")));
        assertEquals(0, done.exitCode());
        assertEquals(0, done.output().length);
    }

    @Test
    void theToolRunsInTheDirectoryItWasGiven() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("work")).toRealPath();
        ToolProcess.Finished done = finished(ToolProcess.run(request(ToolProcess.Stderr.DISCARD, LONG, "cwd").inDirectory(directory)));
        assertEquals(directory, Path.of(text(done)).toRealPath());
    }

    @Test
    void aProgramThatIsNotThereIsAnIoException() {
        ToolProcess.Request missing = ToolProcess.Request.of(List.of(temporary.resolve("no-such-tool").toString()), ToolProcess.Stderr.DISCARD, LONG);
        assertThrows(IOException.class, () -> ToolProcess.run(missing));
    }

    @Test
    void stderrIsMergedDiscardedOrLeftToTheParent() throws Exception {
        String merged = text(finished(ToolProcess.run(request(ToolProcess.Stderr.MERGE, LONG, "say", "0"))));
        assertTrue(merged.contains("out-text") && merged.contains("err-text"), merged);
        assertEquals("out-text", text(finished(ToolProcess.run(request(ToolProcess.Stderr.DISCARD, LONG, "say", "0")))));
        assertEquals("out-text", text(finished(ToolProcess.run(request(ToolProcess.Stderr.INHERIT, LONG, "say", "0")))));
    }
}
