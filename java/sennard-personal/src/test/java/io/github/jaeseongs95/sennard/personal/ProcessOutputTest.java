// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the runner keeps of the output of a command and what it does about a process that holds the pipes of the output after the command ended. The processes are real JVMs; the
 * order of what happens is fixed by files the programs write and wait for, and no time is asserted (a wait has a deadline only so that a failure ends the test).
 */
class ProcessOutputTest {
    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();
    /** The heap of every JVM the tests start (the property is set on the Test tasks in java/build.gradle.kts). */
    private static final String JAVA_HEAP = "-Xmx" + System.getProperty("sennard.test.childHeap");
    /**
     * Starts Holder, which has the pipes of the command's output, waits until Holder says it wrote two mebibytes, writes one more line and ends. Holder is not killed by anything
     * and keeps the pipes open after this command ended.
     */
    private static final String LEADER = """
            public class Leader {
                public static void main(String[] args) throws Exception {
                    String launcher = ProcessHandle.current().info().command().orElseThrow();
                    Process holder = new ProcessBuilder(launcher, "@HEAP@", args[0] + ".java").redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT).start();
                    java.nio.file.Files.writeString(java.nio.file.Path.of("holder.pid"), Long.toString(holder.pid()));
                    while (!java.nio.file.Files.exists(java.nio.file.Path.of("wrote.txt"))) {
                        Thread.sleep(20);
                    }
                    System.out.print("the command's last line");
                    System.out.flush();
                }
            }
            """.replace("@HEAP@", JAVA_HEAP);
    /**
     * Writes two mebibytes to the pipe it inherited, says so in a file, and writes on until the write fails; the failure is written to a file too, and it ends. After the two
     * mebibytes it writes a chunk every few milliseconds, not as fast as it can: on Linux the JDK reads what is in the pipe of a process that has just ended until the pipe is
     * empty, and a writer that refills it as fast as it is read would keep that read going.
     */
    private static final String FLOODING_HOLDER = """
            public class FloodingHolder {
                public static void main(String[] args) throws Exception {
                    java.io.FileOutputStream out = new java.io.FileOutputStream(java.io.FileDescriptor.out);
                    byte[] chunk = new byte[8192];
                    java.util.Arrays.fill(chunk, (byte) 'x');
                    for (int written = 0; written < 2 * 1024 * 1024; written += chunk.length) {
                        out.write(chunk);
                    }
                    java.nio.file.Files.writeString(java.nio.file.Path.of("wrote.txt"), "wrote");
                    try {
                        while (true) {
                            out.write(chunk);
                            Thread.sleep(5);
                        }
                    } catch (java.io.IOException brokenPipe) {
                        java.nio.file.Files.writeString(java.nio.file.Path.of("broken.txt"), "broken");
                    }
                }
            }
            """;
    /** Says it holds the pipes in a file and sleeps ten minutes without writing. */
    private static final String QUIET_HOLDER = """
            public class QuietHolder {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("wrote.txt"), "holds");
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

    private static Process startLeader(Path folder, String holder) throws IOException {
        Files.writeString(folder.resolve("Leader.java"), LEADER);
        Process leader = new ProcessBuilder(JAVA, JAVA_HEAP, "Leader.java", holder).directory(folder.toFile()).start();
        leader.getOutputStream().close();
        return leader;
    }

    /**
     * A pipe that a process holds open and writes nothing to: the bytes it was given, then nothing. A read with nothing in the pipe waits for good, and closing the stream does not
     * wake it (the JDK on Linux), so a reader that makes such a read is left parked until {@link #freeParkedReads()}; one that asks {@code available()} first never does.
     */
    private static final class SilentPipe extends InputStream {
        private final ByteArrayInputStream given;
        private final CountDownLatch never = new CountDownLatch(1);
        private final AtomicInteger parkedReads = new AtomicInteger();
        private volatile boolean closed;

        SilentPipe(String given) {
            this.given = new ByteArrayInputStream(given.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public int available() throws IOException {
            if (closed) {
                throw new IOException("Stream closed");
            }
            return given.available();
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0];
        }

        @Override
        public int read(byte[] into, int offset, int length) throws IOException {
            if (given.available() > 0) {
                return given.read(into, offset, length);
            }
            parkedReads.incrementAndGet();
            try {
                never.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean closed() {
            return closed;
        }

        int parkedReads() {
            return parkedReads.get();
        }

        void freeParkedReads() {
            never.countDown();
        }
    }

    /** A pipe that a process writes to faster than it is read: there is always more in it. */
    private static final class FullPipe extends InputStream {
        private volatile boolean closed;

        @Override
        public int available() throws IOException {
            if (closed) {
                throw new IOException("Stream closed");
            }
            return READ_AT_A_TIME;
        }

        @Override
        public int read() {
            return 'x';
        }

        @Override
        public int read(byte[] into, int offset, int length) {
            int count = Math.min(length, READ_AT_A_TIME);
            Arrays.fill(into, offset, offset + count, (byte) 'x');
            return count;
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean closed() {
            return closed;
        }
    }

    private static final int READ_AT_A_TIME = 8192;

    @Test
    void aPipeThatAProcessHoldsOpenAndWritesNothingToIsReadAndClosedAndNoReaderWaitsOnIt() {
        SilentPipe silent = new SilentPipe("the command's last line");
        try {
            ProcessOutput output = new ProcessOutput(silent, new ByteArrayInputStream(new byte[0]), Duration.ofSeconds(1));

            output.finish();

            assertEquals("the command's last line", output.stdout().text(), "what was in the pipe when the command ended is kept");
            assertTrue(silent.closed(), "the reader closed the pipe it read, so the process that holds it gets a broken pipe");
            assertEquals(0, silent.parkedReads(), "no read was made that waits for a process that writes nothing: such a read does not end and holds the thread and the pipe");
        } finally {
            silent.freeParkedReads();
        }
    }

    @Test
    void aPipeThatIsNeverEmptyIsClosedWhenTheGraceIsOverAndWhatIsPastTheLimitIsDropped() {
        FullPipe full = new FullPipe();
        ProcessOutput output = new ProcessOutput(full, new ByteArrayInputStream(new byte[0]), Duration.ofMillis(50));

        output.finish();

        assertTrue(full.closed(), "released: the reader ended and closed the pipe although it was never empty");
        assertEquals(ProcessOutput.LIMIT, output.stdout().text().length(), "the first mebibyte is kept");
        assertTrue(output.stdout().truncated());
    }

    @Test
    void theReadersAreWaitedForOnlyOnceHoweverOftenTheRunFinishesTheOutput() {
        SilentPipe silent = new SilentPipe("x");
        try {
            ProcessOutput output = new ProcessOutput(silent, new ByteArrayInputStream(new byte[0]), Duration.ofSeconds(1));
            output.finish();
            int afterTheFirst = output.waits();
            assertTrue(afterTheFirst > 0, "the first call waited for the readers");

            output.finish().finish();

            assertEquals(afterTheFirst, output.waits(), "a call after the first does not wait again");
        } finally {
            silent.freeParkedReads();
        }
    }

    @Test
    void exactlyTheLimitIsKeptWholeAndOneByteMoreIsDroppedAndMarked() {
        byte[] exact = new byte[ProcessOutput.LIMIT];
        Arrays.fill(exact, (byte) 'x');
        ProcessOutput whole = new ProcessOutput(new ByteArrayInputStream(exact), new ByteArrayInputStream(new byte[0]), Duration.ofSeconds(30)).finish();
        assertEquals(ProcessOutput.LIMIT, whole.stdout().text().length());
        assertFalse(whole.stdout().truncated(), "exactly the limit is not past it");
        assertFalse(whole.truncated());

        byte[] over = Arrays.copyOf(exact, ProcessOutput.LIMIT + 1);
        over[ProcessOutput.LIMIT] = (byte) 'y';
        ProcessOutput cut = new ProcessOutput(new ByteArrayInputStream(new byte[0]), new ByteArrayInputStream(over), Duration.ofSeconds(30)).finish();
        assertEquals(ProcessOutput.LIMIT, cut.stderr().text().length(), "the first bytes up to the limit are kept");
        assertFalse(cut.stderr().text().contains("y"), "the byte past the limit is not kept");
        assertTrue(cut.stderr().truncated());
        assertFalse(cut.stdout().truncated(), "the other stream is not marked");
        assertTrue(cut.truncated());
    }

    @Test
    void aDetachedProcessThatHoldsThePipeDoesNotKeepTheReadOpenGetsABrokenPipeAndNothingPastTheLimitIsKept(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("FloodingHolder.java"), FLOODING_HOLDER);
        Process leader = startLeader(folder, "FloodingHolder");
        long holder = -1;
        try {
            ProcessOutput output = new ProcessOutput(leader.getInputStream(), leader.getErrorStream(), Duration.ofMillis(300));
            leader.onExit().get(90, TimeUnit.SECONDS);
            holder = awaitPid(folder.resolve("holder.pid"));
            assertTrue(Files.exists(folder.resolve("wrote.txt")), "the holder wrote past the limit before the command ended");

            // The holder has the pipes open and writes on, so the streams never reach their end: finish() answers because its grace is over, and it releases them.
            output.finish();

            ProcessOutput.Kept kept = output.stdout();
            assertEquals(ProcessOutput.LIMIT, kept.text().length(), "the first mebibyte is kept");
            assertTrue(kept.truncated());
            assertFalse(kept.text().contains("the command's last line"), "what was written after the limit is not in the result");
            assertEquals("", output.stderr().text());

            // The read end is closed: the holder, which never stops writing by itself, gets a broken pipe and ends.
            ProcessHandle.of(holder).ifPresent(handle -> handle.onExit().orTimeout(90, TimeUnit.SECONDS).join());
            assertTrue(Files.exists(folder.resolve("broken.txt")), "the holder ended because its write failed");
            ReleasedFolder.await(folder);
        } finally {
            leader.destroyForcibly();
            if (holder > 0) {
                ProcessHandle.of(holder).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    void theOutputOfTheCommandIsKeptWholeAlthoughADetachedProcessHoldsThePipe(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("QuietHolder.java"), QUIET_HOLDER);
        Process leader = startLeader(folder, "QuietHolder");
        long holder = -1;
        try {
            ProcessOutput output = new ProcessOutput(leader.getInputStream(), leader.getErrorStream(), Duration.ofSeconds(2));
            leader.onExit().get(90, TimeUnit.SECONDS);
            holder = awaitPid(folder.resolve("holder.pid"));

            output.finish();

            assertEquals("the command's last line", output.stdout().text());
            assertFalse(output.truncated());
            assertTrue(ProcessHandle.of(holder).map(ProcessHandle::isAlive).orElse(false), "the holder is not touched by the output: it was not found by the runner here");

            // The holder writes nothing, so a reader that waited for the end of the pipe would still be parked in its read, with the stream's monitor, and the stream could be asked
            // nothing. Both streams are closed and answer at once (the deadline is only so that a failure ends the test instead of hanging it).
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                assertThrows(IOException.class, leader.getInputStream()::available, "the pipe of stdout was closed by its reader");
                assertThrows(IOException.class, leader.getErrorStream()::available, "the pipe of stderr was closed by its reader");
            });
        } finally {
            leader.destroyForcibly();
            if (holder > 0) {
                ProcessHandle.of(holder).ifPresent(ProcessHandle::destroyForcibly);
                ProcessHandle.of(holder).ifPresent(handle -> handle.onExit().join());
            }
        }
        ReleasedFolder.await(folder);
    }
}
